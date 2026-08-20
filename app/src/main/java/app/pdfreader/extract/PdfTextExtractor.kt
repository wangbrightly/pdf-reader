package app.pdfreader.extract

import android.content.Context
import android.graphics.Bitmap
import app.pdfreader.ui.DisplayBlock
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix as PdfMatrix
import java.io.File
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.sign

/**
 * 从 PDF 文件中抽取正文文字，按段落切分成 [String] 列表，供 [app.pdfreader.reflow.reflow]
 * 重新排版使用。
 *
 * 实现方式：继承 `PDFTextStripper`，重写 `writeString(String, List<TextPosition>)`——
 * PdfBox 默认按"视觉行"调用一次这个回调，每次都带上这一行第一个字符的坐标。抽取完
 * 整个文档后，再用一个简单启发式把行合并成段落：比较相邻两行的纵坐标间距，如果某处
 * 间距明显大于全文的典型行距（中位数的 1.5 倍），就判定为段落边界——这对应常见 PDF
 * 里"段落之间多一行空白"的排版惯例，不追求覆盖所有版式，够用即可（见 SELECTION.md
 * 第 4 节兜底方案）。
 *
 * 合并行内文字时，中日韩字符之间不加空格（本来就没有词间空格），其余情况插入一个
 * 空格，避免英文断行处的单词被硬连在一起——这条判断规则和 [app.pdfreader.reflow] 里
 * 的 CJK 断行逻辑保持同一套认知模型。段落文字最终成型后还会跑一遍 [normalizeCjkSpacing]
 * 统一规范化间距（去掉中文字符间的多余空格、给中文和数字/字母之间补上恰好一个空格），
 * 见该函数 KDoc"用户真机实测反馈"一节。
 *
 * ## 已知问题：部首变体字符替换（2026-08-18 用真实中文 PDF 实测发现）
 *
 * PdfBox（继承自 Apache PDFBox）在某些字体的 CID→Unicode 映射有歧义时（同一个字形
 * 可以合法映射到"部首"或"汉字本字"两个不同码位，例如 U+2F17 康熙部首"⼗" 和
 * U+5341 汉字"十"），会挑中部首那一个，而不是汉字本字——这是 PDFBox 社区已知问题
 * （见 issues.apache.org/jira/browse/PDFBOX-55、sourceforge.net/p/pdfbox/bugs/72），
 * poppler/pdftotext 在同一份 PDF 上不会踩这个坑。用真实文件（非本项目生成的测试
 * fixture）实测：31 个康熙部首（U+2F00–U+2FDF）全部可以用 NFKC 归一化修复；另有
 * 5 个"CJK 部首补充"区（U+2E80–U+2EFF）的简体部首形式 NFKC 修不了（该区块没有
 * Unicode 兼容分解映射），需要手动表——[RADICALS_SUPPLEMENT_FIX] 只收了补充区里
 * "本身就是常用独立汉字"的那些（车/长/门/见/贝/韦/页/风/飞/马/鱼/鸟/卤/麦/黄/齐/
 * 齿/龙/龟），纯部首用字（钅/饣/纟/讠/辶等，从不单独成字）不收——这类字形只有在
 * PDF 显示的就是它对应的那个独立汉字时才会误命中这个 bug，本身不会在正常文本里
 * 单独出现，收了也用不上。
 *
 * ## 图片抽取（2026-08-18 增量）：按页归类的降级方案
 *
 * SELECTION.md 第 4 节兜底方案第 3 条要求"图片降级为独立浮动展示……按它们在原页面中
 * 大致所处的段落位置，插入到对应段落之间"，明确不追求"精确嵌入原位置"。落地成
 * [extractContent]：遍历每一页 `PDPage.getResources()` 里的 `PDImageXObject`
 * （`resources.getXObjectNames()` + `isImageXObject(name)` 判断类型），用
 * `PDImageXObject.getImage()` 直接拿到 `android.graphics.Bitmap`（这是 PdfBox-Android
 * 提供的现成转换，不需要自己写解码逻辑），"插在哪个段落之后"这个位置判断交给纯逻辑
 * [ImagePlacement]（该类 KDoc 里详细说明了为什么选"按页归类"而不是"页面内精确
 * 纵坐标"这个更复杂的方案）。
 *
 * 为了让"图片属于第几页"和"段落属于第几页"能对得上，[Line] 多了 [Line.page] 字段
 * （来自 `PDFTextStripper.getCurrentPageNo()`，本来就有，不需要新的机制），
 * [linesToParagraphs] 现在跨页时会强制切一次段落——这顺带修了一个潜在 bug：改动前
 * 只按 y 坐标间距判断段落边界，而 y 坐标每翻一页就从页顶重新开始，理论上会把"上一页
 * 最后一行"和"下一页第一行"错误拼接成同一段（本项目现有的单页测试 fixture 从未
 * 触发过这个路径，改动前的 40 个测试不受影响）。
 *
 * 单张图片抽取失败（解码异常、格式不支持等）只跳过那一张，不影响其余图片和全部文字，
 * 见 [buildInlineImages] 里的 `runCatching`。
 *
 * ## 内嵌图片朝向修正（2026-08-18 增量，真机反馈"部分图片方向不对"）
 *
 * **现象**（真机截图观察到，335 页 PDF 里一张"章节索引/问答列表"图片）：页码/竖线
 * 分隔符左右位置对调、章节号上下顺序颠倒，但截图里汉字字形本身仍是正常方向（不是
 * 每个字形本身镜像到认不出来那种）——同一份文档里另一张结构类似的图片方向正常，
 * 说明不是全局性的、每张图都错。诊断日志显示这张图"疑似表格页=0"，即没有走
 * [renderTableRegionImages] 整页渲染那条路，走的是下面说的内嵌图片抽取路径。
 *
 * **根因，用构造的 fixture 实测确认，不是纯推理**：`PDImageXObject`
 * 这类图片资源本身只是原始像素数据，它在页面上究竟怎么摆（缩放/旋转/翻转/位置）
 * 是由 content stream 里 `Do` 操作符执行那一刻的 CTM（当前变换矩阵，由前面一串
 * `cm` 累积）决定的——PDF 图片空间的约定是"原点在左上角、y 轴向下"（正好贴合
 * `Bitmap` 像素排列顺序），页面空间是 y 轴向上，两者之间唯一"必要"的转换是
 * "上下翻一次"；绝大多数 PDF 生产工具摆放图片时用的 CTM 形如 `[W 0 0 -H x y]`
 * （即恰好只做这唯一必要的翻转，不多不少），这时候"直接显示 `getImage()`
 * 拿到的原始 Bitmap，完全不管 CTM"就凑巧是对的——这也是这个 bug 长期不易被
 * 发现的原因。改动前的实现（遍历 `PDResources` 里的 `PDImageXObject`，直接
 * `getImage()`）等于无条件假设每张图片都恰好落在这种"标准摆法"里；一旦某张图片
 * 的 CTM 在这唯一必要的翻转之外还叠了一层翻转/90 度整数倍旋转（比如页面某个坐标轴
 * 的缩放分量是负的），"原始 Bitmap 直接显示"和"页面上应该看到的样子"就会对不上。
 * 用 [PdfTextExtractorImageOrientationTest] 程序化验证过：构造一张四色象限测试图，
 * 分别用 8 种不同 CTM（含水平镜像、垂直镜像、180°旋转、90°/270°旋转及其翻转变体）
 * 摆放，和一个独立写的"参照模型"（直接用 CTM 的 `a b c d e f` 算每个角在页面上
 * 该落在哪个象限，不复用 [applyCtmOrientation] 自己的推导代码）比对——两者在全部
 * 8 种轴对齐 CTM 下都一致。这里没有采用"和 `PDFRenderer` 整页渲染结果比对"这个
 * 一开始尝试的路子：实测发现在这套 Robolectric + PdfBox-Android 2.0.27.0 工具链下，
 * 对着一个从零现搭、只有一张图片的最小内存态文档调用 `PDFRenderer.renderImageWithDPI`
 * 渲染不出图片内容（整页几乎全透明，怀疑是这个版本渲染管线对"没有真实来源文件、
 * 内容极简"的文档有兼容性问题，没有深挖，跟本次任务无关）；另外用 `ImageIO` 现编的
 * PNG 经过 `PDImageXObject.createFromByteArray`→`getImage()` 这一圈解码后颜色也会
 * 错乱（怀疑是这个版本的 PNG 解码路径本身的独立 bug，同样跟本次任务无关，只是
 * 拖累了"拿真实文件当参照"这条路的可行性）——两个问题都在 fixture 构造/第三方库
 * 解码这一层，不在 [applyCtmOrientation] 的翻转/旋转逻辑本身，所以改成"独立参照
 * 模型直接比对纯 CTM 数学"，绕开这两个不相关的坑，见该测试文件顶部注释。
 *
 * **修法**：抽取逻辑不再遍历 `PDResources` 挖图片，改成用 [PageContentStreamEngine]
 * （继承 `PDFGraphicsStreamEngine`，2026-08-19 起同时也是表格检测用的矢量线段
 * 收集器，见后面"表格检测/图片抽取合并成一次遍历"一节）
 * 处理页面 content stream，在 `drawImage()` 回调里同时拿到图片和"画这张图那一刻"
 * 的 CTM（`graphicsState.currentTransformationMatrix`），交给 [applyCtmOrientation]
 * 换算成需要施加在 `Bitmap` 上的翻转/旋转操作。这个改动顺带修了两个改动前完全没
 * 覆盖的缺口（跟着 content stream 走的自然结果，不是专门加的逻辑）：内嵌图片
 * （`BI`/`ID`/`EI` 操作符的 `PDInlineImage`，`PDImage` 接口统一了两者）、以及
 * "同一张图片资源在同一页被 `Do` 多次、每次 CTM 不同"——旧实现按 `PDResources`
 * 的 name 集合遍历，这两种情况都会漏；新实现天然不会。
 *
 * **已知局限（有意的降级范围，不是遗漏）**：[applyCtmOrientation] 只处理"轴对齐"
 * 的 CTM——不旋转、水平镜像、垂直镜像、180°旋转，以及 90°/270°旋转（含各自再叠一层
 * 镜像），一共覆盖 D4 群全部 8 种朝向。真正的任意角度旋转或斜切（CTM 的 a/b/c/d
 * 四个线性分量都明显非零、不满足"两个为零"这个轴对齐条件）不在这次修复范围内，
 * 原样返回不做任何修正——理由有两条：一是这类摆放在真实 PDF 的"正文插图"场景里
 * 极罕见（本次真机反馈的现象本身也是镜像/整体倒转的模式，不是任意角度旋转）；
 * 二是任意角度旋转后的位图边缘必然引入插值，没法再用"取像素点精确比对"这种确定性
 * 方式验证正确性，复杂度和可验证性都不成比例。[表格检测那条整页渲染路径]
 * （[renderTableRegionImages]，用 [PDFRenderer.renderImageWithDPI]）不受这个 bug
 * 影响——那是页面级别的渲染 API，由 PdfBox-Android 自己的渲染引擎负责应用页面
 * 内所有 CTM（包括图片的），不是"抽取单个图片资源、自己拼位图"这条路径，两者用的
 * 是完全不同的代码，这个判断没有另外找 fixture 验证（renderImageWithDPI 是否正确
 * 处理 CTM 属于 PdfBox-Android 库自身职责范围，不是本项目代码，没有理由重新验证
 * 一个第三方库的核心渲染路径），只是读代码路径确认它和 [scanPages] 走的不是
 * 同一段逻辑。
 *
 * ## 表格检测（2026-08-18 增量，2026-08-19 从"整页降级"改成"区域裁剪"）：疑似表格
 * 的区域降级为图片，同页其余正文照常重排
 *
 * 提示词档案第 6 条要求"表格不重排，整体展示，支持双指缩放"——PDF 格式本身没有
 * "表格"这个结构化概念，"这块内容是不是表格"是个没有标准答案的启发式检测问题
 * （业界专门做这个的 Camelot/Tabula 都只能做到"启发式+带误判"，见任务描述）。这里
 * 不追求精确识别表格边界，采用两段降级：
 *
 * 1. **检测信号：矢量网格线，不是文字列对齐。** [scanPages] 对每一页跑一遍
 *    [PageContentStreamEngine]（继承 `PDFGraphicsStreamEngine`，能拿到页面 content
 *    stream 里 `re`/`m l S` 等图形操作符的线段坐标），把线段交给纯逻辑
 *    [TableGridDetector.tableRegionOrNull] 判断"这一页有没有网格，网格大致占地
 *    多大"。选网格线而不是"文字按列对齐"这个更简单的信号，是因为后者在多栏排版、
 *    目录页上误判率明显更高（一大段无关文字碰巧在几行里都能切出 3 列对齐点，这种
 *    巧合并不罕见）；网格线要求"多条横线和多条竖线互相交叉"，普通正文段落、多栏
 *    排版、目录页几乎不会画出这种矢量图形，误判率天然更低，符合"宁可漏检、不可
 *    错杀"的保守策略（见 [TableGridDetector] 类注释的完整阈值设计理由）。用真实
 *    fixture（`sample-with-table.pdf`）反编译验证过：Chromium 打印 `<table border>`
 *    时，表格边框是画成"细长填充矩形"而不是描边直线，[PageContentStreamEngine] 对
 *    `appendRectangle`/`strokePath`/`fillPath` 都做了处理，两种画法都能识别。
 *
 * 2. **降级策略：把表格所在的区域（不是整页）渲染成一张 Bitmap，区域之外的正文
 *    照常抽取+reflow。** 用户反馈"一页上既有表格又有正文时，能不能像 EPUB 阅读器
 *    那样文字是文字、表格是表格"——早期版本（2026-08-18~19 之间）检测到表格就把
 *    整页都渲染成图片，哪怕表格只占一小块，同页大段正文也跟着丢失"重排+调字号"
 *    的能力。改成 [renderTableRegionImages]：先用 [PDFRenderer.renderImageWithDPI]
 *    整页渲染（`PDFRenderer` 没有提供"只渲染页面某个区域"的公开 API），再裁剪到
 *    [TableGridDetector.tableRegionOrNull] 算出的包围盒（[tableCropRect]，加一圈
 *    内边距避免裁掉表格边缘文字）；文字抽取那一侧用 [isWithinTableBand] 排除掉
 *    落在这个区域纵向范围内的行——两处用的是同一套坐标换算和同一个内边距常量
 *    （[TABLE_REGION_PADDING_PT]），保证"图片裁到哪、文字排除到哪"是一致的，不会
 *    出现表格边缘文字既被裁进图片、又重复留在正文段落里。
 *
 *    **已知局限（有意的降级范围，不是遗漏）**：只按纵向范围排除文字，不判断横向
 *    位置——假设表格占满页面宽度的一个横向条带，遇到"表格和正文左右并排"这种排版
 *    会连正文一起误判成表格范围内、被裁掉；一页有多个互相分离的表格时，
 *    [TableGridDetector.tableRegionOrNull] 只算出一个涵盖全部疑似表格线段的包围盒，
 *    会把两个表格之间的正文也划进"表格区域"。两种情况都比"这次修复之前"（整页
 *    降级）要好（至少表格前后的正文能保住），但没有做到完全精确，真实文档这两种
 *    排版都相对少见，跟本类"保守但简单"的一贯原则一致。
 *
 *    渲染失败（内存不足、极端复杂页面等）时不强行让整份文档抽取失败，而是让这一页
 *    退回正常的文字抽取路径——见 [renderTableRegionImages] 里的 `runCatching`，这样
 *    "表格检测/渲染出问题"最坏情况下只是退化成"这页表格没能降级、还是按普通文字
 *    重排"，不会让用户连文字都看不到。
 *
 * ## 大纲/目录抽取（2026-08-18 增量）：有官方结构就直接读，没有就如实留空
 *
 * PDF 格式本身有一套标准的大纲（Outline，常俗称"书签"）结构，不像表格检测那样要
 * 自己发明启发式——`document.getDocumentCatalog().getDocumentOutline()` 返回
 * `PDDocumentOutline`（没有大纲时是 `null`，这是很常见的情况，尤其是普通文档/扫描件
 * 转出来的 PDF），子项通过 `PDOutlineNode.children()`（内部就是按 `getFirstChild()`/
 * `getNextSibling()` 遍历）拿到 `Iterable<PDOutlineItem>`，标题读 `getTitle()`
 * （可能是 `null`，见 [OutlineEntry.title] 处理），目标页用
 * `PDOutlineItem.findDestinationPage(PDDocument)` 解析——已读 PdfBox-Android 上游
 * 源码确认其行为：destination 为 `null` 且没有可用的 GoTo action 时返回 `null`；
 * 命名目标在文档编目查不到时也返回 `null`；只有遇到未知的 destination 类型才会抛
 * `IOException`。这两类"解析不出目标页"的情况都只跳过这一个目录项（[collectOutlineEntries]
 * 对每一项单独 `runCatching`），不让整份大纲、更不让整份文档的抽取失败——和图片/
 * 表格抽取失败的降级精神一致。
 *
 * `findDestinationPage` 返回的是 `PDPage` 对象本身，不是页码——用
 * `document.getPages().indexOf(page)` 换算成 0-based 下标，`+1` 得到和
 * [Line.page]/`Paragraph.page` 同一套编号（1-based）的页码，这样目录项的页码才能
 * 喂给 [app.pdfreader.ui.OutlineNavigation] 跟段落页码对齐。
 *
 * **没有大纲的 PDF 怎么处理**：[extractOutline] 返回空列表，[PdfContent.outline]
 * 就是空的——不用字体大小/加粗这类启发式去"猜"标题（这类猜测在表格检测那次已经
 * 证明误判率不低，目录功能给用户的预期是"点了准确跳转"，猜错了比没有更烂，任务
 * 描述里也明确要求不要这样做）。UI 层（[app.pdfreader.MainActivity]）据此把"目录"
 * 按钮禁用，如实反映"这份 PDF 没有内嵌目录"这个事实，不假装有目录。
 *
 * ## 页眉页脚水印过滤（2026-08-19 增量）
 *
 * 真机反馈一份"网页打印成 PDF"的文档，抽取出来的文字里夹着浏览器自动加的页眉页脚：
 * 打印时间、文档标题、来源网址、页码计数，几乎每页都有，读起来很打扰。用
 * [RunningFooterFilter]（纯逻辑，独立测试）识别、过滤掉——具体识别规则（含
 * 2026-08-19 补的"标题行"检测：靠长度+跨页重复率两道门槛，不是简单靠格式匹配）
 * 见该类 KDoc。过滤发生在 [linesToParagraphs] 之后、其余所有依赖段落下标的计算
 * （图片插入位置、目录页内定位）之前，这样下标体系从一开始就是"过滤后的段落列表"，
 * 不需要在后面的每一处计算里再去处理"要跳过被删掉的段落"。
 *
 * ## 表格检测/图片抽取合并成一次遍历（2026-08-19 性能优化）
 *
 * 用户反馈加载太慢，真机日志实测一份 136 页文档：检测表格 1.3s + 抽取内嵌图片
 * 2.6s，两者相加占了总加载时间（4.9s）的大头。根因是这两步各自独立跑一遍
 * `PDFGraphicsStreamEngine.processPage`——同一份 content stream 的操作符 token
 * 被完整解析了两遍，一遍只为了收集矢量线段（表格检测），另一遍只为了收集图片。
 *
 * 改成 [scanPages]：每页只跑一遍 [PageContentStreamEngine]（合并了原来独立的
 * `TableGridStreamEngine`/`ImageDrawStreamEngine`），一次遍历同时收集矢量线段和
 * 图片，返回 [PageScan]（每页各自的线段+图片）。原来 [detectTablePages] 单独判断
 * "是不是表格"的逻辑现在挪到 [scanPages] 调用方那一行 `filterValues`；原来
 * `extractImages` 需要"重新解析 content stream 拿图片"的部分不再需要，换成
 * [buildInlineImages]——纯粹的列表组装（配合 [ImagePlacement] 算插入位置），因为
 * 图片本身已经在 [scanPages] 阶段收集好了，组装这一步不再有 PDF 解析开销。
 */
object PdfTextExtractor {

    /** 整页渲染表格页时使用的 DPI：兼顾清晰度（配合双指缩放放大后仍可读）和内存占用。 */
    private const val TABLE_PAGE_RENDER_DPI = 150f

    /**
     * 内嵌图片解码时，源图片长边超过这个像素数就降采样——见 [subsamplingFactor] KDoc
     * "为什么是 2000px"一节。
     */
    private const val MAX_IMAGE_DIMENSION_PX = 2000

    /** 见 [PageContentStreamEngine] 里 `segmentCollectionCapped` 的 KDoc。 */
    private const val MAX_SEGMENTS_PER_PAGE = 20_000

    /** 见 [Session.footerLearnedTitles] KDoc——页脚水印"标题类重复"检测的样本页数上限。 */
    private const val FOOTER_SAMPLE_PAGE_COUNT = 150

    /**
     * 内嵌图片长边（`max(width, height)`）超过 [MAX_IMAGE_DIMENSION_PX] 判定为"过大"，
     * 每次减半算出理论上该降到多少——纯数学，见 [decodeJpegWithNativeSubsampling]
     * KDoc"第三次尝试"一节，这次真的用上了。
     *
     * ## 真机踩坑：前两次尝试都失败了
     *
     * 起因：用户反馈一份 258 页、0 张表格页的文档打开要 25 秒，真机日志确认几乎全部
     * 耗时都在图片解码——`PDImage.getImage()` 默认按源图片原始分辨率整张解码，源图片
     * 是几千像素见方的扫描页时，解码成本跟屏幕实际显示尺寸（这台设备约 1440px 物理
     * 像素）完全不成比例。
     *
     * - **尝试 1**：用 `PDImage.getImage(Rect, Int)` 这个带降采样参数的重载，指望
     *   解码阶段就省下大图开销。真机实测这个重载在 subsampling>1 时稳定抛
     *   `y + height must be <= bitmap.height()`（试过 region 传 `null`、也试过显式传
     *   整张图的 `Rect`，结果一样）——这份文档全部 258 张图片解码失败，图片整个不见
     *   了，日志看着"加载快了 16 倍"其实是全丢了，不是真的变快。
     * - **尝试 2**：改回可靠的 `pdImage.image` 解码到原始分辨率，解码成功后再用
     *   `Bitmap.createScaledBitmap` 缩小。这次图片都在了（0 失败），但真机实测总耗时
     *   反而变成 36.3 秒，比原来的 25.4 秒还慢——解码本身仍然是全分辨率（真正的开销
     *   大头没有省掉），"解码后再缩小"这一步是纯粹叠加的额外成本，没有任何收益。
     *
     * 两次都证明"事后补救"（不管是解码阶段的降采样 API 还是解码后再缩小）走不通，
     * 但没有解释**为什么** `getImage(Rect, Int)` 会炸——反编译 PdfBox-Android
     * 2.0.27.0 的 `PDImageXObject` 字节码找到了根因：`getImage()`/`getImage(Rect,Int)`
     * 这两个公开方法最终都会调用同一个内部方法
     * `SampledImageReader.getRGBImage(PDImage, Rect, int subsampling, COSArray)`，
     * 唯一区别是传给它的 `subsampling` 参数值——也就是说尝试 1 踩到的那个异常，
     * 根子是 `SampledImageReader` 这个内部类处理 `subsampling>1` 时本身有 bug，
     * 不是"传参方式不对"，我传 `null` 还是显式 `Rect` 都会走到同一段有问题的代码，
     * 这条路径本身在这个版本上不可靠，不值得再花时间调传参方式。
     *
     * 详见 [decodeJpegWithNativeSubsampling] KDoc"第三次尝试"一节。
     */
    internal fun subsamplingFactor(width: Int, height: Int): Int {
        val longSide = maxOf(width, height)
        var factor = 1
        while (longSide / factor > MAX_IMAGE_DIMENSION_PX) factor *= 2
        return factor
    }

    /**
     * ## 第三次尝试：绕开 PdfBox-Android 的图片解码封装，JPEG 图片直接用安卓原生解码器
     *
     * 前两次尝试都在用 PdfBox-Android 自己的解码路径（`SampledImageReader`）打转，
     * 见 [subsamplingFactor] KDoc"真机踩坑"一节确认的根因——那条内部路径处理
     * `subsampling>1` 本身有 bug，不管怎么调用都绕不开。这次换一条完全独立的路径：
     *
     * 1. `PDImage.getSuffix()`（反编译确认过：内容流的最后一层过滤器是 `DCTDecode`
     *    时返回 `"jpg"`，是 JPEG 编码——真实 PDF 里内嵌图片，尤其是扫描页，用 JPEG
     *    压缩非常普遍）判断是不是 JPEG；不是就直接返回 `null`，调用方回退到安全的
     *    `pdImage.image`。
     * 2. 是 JPEG 的话，用 `PDImage.createInputStream(listOf("DCTDecode", "DCT"))`
     *    拿到"解码到 DCTDecode 这一步为止、不应用 DCTDecode 本身"的字节流——反编译
     *    确认过 `PDStream.createInputStream(List<String> stopFilters)` 的语义就是
     *    "遇到名字在这个列表里的过滤器就停手，返回目前为止解码出来的字节"，`DCTDecode`
     *    是 JPEG 压缩这层过滤器的标准名字（`"DCT"` 是它的缩写形式，内嵌图片里两种
     *    写法都可能出现），停在这一步意味着拿到的就是**完整、未经改动的原始 JPEG
     *    字节**，可以直接交给安卓自己的 `BitmapFactory.decodeByteArray` 解码——这是
     *    一条跟 `SampledImageReader` 完全独立、平台自带、久经考验的解码路径，不共享
     *    任何代码，不会被同一个 bug 影响。
     * 3. `BitmapFactory.Options.inSampleSize` 设成 [subsamplingFactor] 算出来的倍数
     *    ——这一步是安卓平台自己的标准降采样机制，从图源本身按倍数跳过采样点直接
     *    解码，是真的在解码阶段省时间/省内存，不是"解码完整图再事后缩小"（尝试 2
     *    证明过后者没有收益）。
     *
     * 任何一步失败（`suffix` 不是 `"jpg"`、`createInputStream` 抛异常、
     * `decodeByteArray` 解码不出来）都返回 `null`，调用方 [PageContentStreamEngine
     * .drawImage] 回退到 `pdImage.image`——降级精神跟本类其它地方一致：这个优化路径
     * 走不通，最坏结果是退回"没有提速但正确"的原始行为，不会让图片本身消失或出错。
     *
     * 只处理 JPEG（`suffix=="jpg"`）不处理 PNG/TIFF/JBIG2 等其它格式：JPEG 是真实
     * 场景里最常见的大图来源（扫描页/照片），`BitmapFactory` 对 JPEG 的支持最成熟
     * 可靠；PNG 通常是无损压缩、体积和这里要解决的"大图"问题关联度较低，TIFF/JBIG2
     * 在不同安卓版本上的原生解码支持不稳定，没有把握，不在这次的范围内。
     *
     * ## 已修：CMYK JPEG 用这条路径解码会花屏（2026-08-20 真机反馈）
     *
     * 真机反馈一份扫描版图册，后半部分好几十页图片显示成对角线彩色噪点——诊断
     * 确认这些页每页只有 1 张原始图片（不涉及 [ImageStripStitcher]），问题出在
     * 解码本身。这是安卓 `BitmapFactory` 解码 CMYK 编码 JPEG 的已知老毛病：扫描/
     * 印刷行业常用 CMYK（4 通道）色彩空间存 JPEG，`BitmapFactory` 只认 RGB/YCbCr
     * （3 通道），把 4 通道数据硬当 3 通道读，色彩通道错位，视觉上就是这种对角线
     * 噪点/条纹。`pdImage.image`（PdfBox-Android 自己的解码路径）内部会调
     * `PDColorSpace.toRGBImage` 做色彩空间转换，能正确处理 CMYK，只是慢。
     *
     * ### 走过的弯路：一开始想用 `pdImage.colorSpace.numberOfComponents` 判断，行不通
     *
     * 第一版修法是解码前先查 `pdImage.colorSpace.numberOfComponents`，不是 3 就
     * 放弃快速路径。真机装上之后问题毫无变化——反编译这个版本的 pdfbox-android
     * 才发现根本原因：这个库压根没实现 `PDDeviceCMYK`，`PDColorSpace.create` 遇到
     * PDF 图片字典里的 `/ColorSpace /DeviceCMYK` 时会打一条"不支持，改用 DeviceRGB"
     * 的日志然后**直接返回 `PDDeviceRGB.INSTANCE`**——也就是说不管 JPEG 实际是几
     * 通道，这个库里 `colorSpace.numberOfComponents` 对 CMYK 图片永远报 3，这条
     * 判断在这个库上是个死胡同，完全测不出真实的颜色空间。
     *
     * ## 实际修法：不信 PDF 库的颜色空间抽象，直接读 JPEG 字节本身的 SOF 段
     *
     * 换成 [JpegComponentCount.of]（纯字节解析，见该类 KDoc 完整背景）：JPEG 编码
     * 本身的 SOF 标记里就带着"颜色分量数"这个字段，不需要经过任何 PDF 库的颜色
     * 空间抽象层，RGB/YCbCr 是 3，CMYK/YCCK 是 4——解析不出来（返回 `null`，比如
     * 数据被截断）时按"不确定"保守处理，不用快速路径，跟本函数其它失败分支的降级
     * 精神一致，有单元测试验证（[JpegComponentCountTest]、
     * [PdfTextExtractorJpegSubsamplingTest] 里"大 CMYK JPEG"那条）。
     *
     * **如实记录**：这次真机反馈的那份图册后来加诊断日志确认，花屏的那批图片
     * `suffix=png`，根本不是 JPEG，压根没走过这段代码——那份图册的花屏是另一个
     * 独立的 PNG 解码 bug（见 NOTES.md #19，还没修）。这条 CMYK JPEG 修复本身是
     * 对的、有真实场景、有单元测试验证，只是不是那次真机反馈的花屏成因，两件事
     * 分开记录，不要混为一谈。
     */
    private fun decodeJpegWithNativeSubsampling(pdImage: PDImage): Bitmap? = runCatching {
        if (pdImage.suffix != "jpg") return null
        val subsampling = subsamplingFactor(pdImage.width, pdImage.height)
        if (subsampling <= 1) return null
        val bytes = pdImage.createInputStream(listOf("DCTDecode", "DCT")).use { it.readBytes() }
        if (JpegComponentCount.of(bytes) != 3) return null
        val options = BitmapFactory.Options().apply { inSampleSize = subsampling }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    /** 见上方 KDoc"已知问题"一节。键是部首补充区码位，值是对应的常用独立汉字。 */
    private val RADICALS_SUPPLEMENT_FIX = mapOf(
        '⻋' to '车', // C-SIMPLIFIED CART
        '⻓' to '长', // C-SIMPLIFIED LONG
        '⻔' to '门', // C-SIMPLIFIED GATE
        '⻅' to '见', // C-SIMPLIFIED SEE
        '⻉' to '贝', // C-SIMPLIFIED SHELL
        '⻙' to '韦', // C-SIMPLIFIED TANNED LEATHER
        '⻚' to '页', // C-SIMPLIFIED LEAF
        '⻛' to '风', // C-SIMPLIFIED WIND
        '⻜' to '飞', // C-SIMPLIFIED FLY
        '⻢' to '马', // C-SIMPLIFIED HORSE
        '⻥' to '鱼', // C-SIMPLIFIED FISH
        '⻦' to '鸟', // C-SIMPLIFIED BIRD
        '⻧' to '卤', // C-SIMPLIFIED SALT
        '⻨' to '麦', // SIMPLIFIED WHEAT
        '⻩' to '黄', // SIMPLIFIED YELLOW
        '⻬' to '齐', // C-SIMPLIFIED EVEN
        '⻮' to '齿', // C-SIMPLIFIED TOOTH
        '⻰' to '龙', // C-SIMPLIFIED DRAGON
        '⻳' to '龟', // C-SIMPLIFIED TURTLE
    )

    /**
     * 修复上面 KDoc 说的部首变体字符问题。
     *
     * 注意：只对"落在康熙部首区间（U+2F00–U+2FDF）"的单字符调用 NFKC，不对整句话做
     * NFKC——踩过坑：NFKC 的兼容折叠范围比预期大，会顺手把中文全角标点（，？！（）：
     * 这些属于 Unicode Fullwidth Forms 区块）降级成英文半角标点，中文阅读器不该要
     * 这个副作用。逐字符判断范围后再决定要不要 normalize，标点和其余字符原样保留。
     */
    private fun fixRadicalVariants(text: String): String {
        val builder = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch.code in 0x2F00..0x2FDF ->
                    builder.append(Normalizer.normalize(ch.toString(), Normalizer.Form.NFKC))
                else -> builder.append(RADICALS_SUPPLEMENT_FIX[ch] ?: ch)
            }
        }
        return builder.toString()
    }

    /** 保留原有签名不变：只要文字段落，不要图片，供 [ReadingProgressKey]/reflow 等既有调用方使用。 */
    fun extractParagraphs(context: Context, file: File): List<String> =
        extractContent(context, file).paragraphs

    /**
     * 文字段落 + 图片（按页归类插入位置）一次性抽取，只解析一遍 PDF——见类注释
     * "图片抽取"一节。图片抽取失败不影响这次调用整体成功，见 [buildInlineImages]。
     */
    fun extractContent(context: Context, file: File): PdfContent {
        PDFBoxResourceLoader.init(context.applicationContext ?: context)
        PDDocument.load(file).use { document ->
            val t0 = System.currentTimeMillis()
            // 表格检测（矢量线段）和图片抽取（Bitmap）合并成一次遍历，见类注释
            // "表格检测/图片抽取合并成一次遍历"一节——这一步是原来"检测表格"+"抽取
            // 内嵌图片"两步耗时的大头，合并后省掉重复解析 content stream 的开销。
            val pageScans = scanPages(document, decodeImages = true)
            val t1 = System.currentTimeMillis()
            // 见类注释"表格区域裁剪"一节：不再是"这一页像不像表格"的布尔值，而是
            // "表格在这一页的大致包围盒"——只裁剪渲染表格本身，不连累同页正文。
            //
            // 真机踩坑：先按页面可见范围过滤一遍线段（onPageSegments），再交给
            // TableGridDetector——见 [isSegmentOnPage] KDoc，有的 PDF（真机反馈一份
            // 网页转 PDF 的长文）content stream 里混着大量完全在页面可见范围之外的
            // 装饰性矢量图形（诊断日志实测过：Y 坐标跨度能到 -4284~5076，MediaBox
            // 却是正常的 0~792，说明不是坐标系原点偏移，是内容流本身画了一堆不可见
            // 的东西），不过滤的话这些"看不见的线"也会被数进表格检测，算出离谱的
            // 包围盒（曾经真机复现过：一个章节的目录列表+好几段正文被整块误判成
            // 表格区域裁掉）。
            val tableRegions = pageScans.mapNotNull { (pageNo, scan) ->
                val page = document.getPage(pageNo - 1)
                val onPageSegments = scan.segments.filter {
                    isSegmentOnPage(it, page.mediaBox.width, page.mediaBox.height)
                }
                TableGridDetector.tableRegionOrNull(onPageSegments)?.let { pageNo to it }
            }.toMap()
            val tableRegionImages = renderTableRegionImages(document, tableRegions)
            // 只有真的渲染成功的页码才继续参与后面的"排除文字/排除内嵌图片"计算——
            // 渲染失败的页退回正常文字抽取，见 renderTableRegionImages KDoc。
            val renderedTableRegions = tableRegions.filterKeys { it in tableRegionImages.keys }
            val tableRegionPageHeights = renderedTableRegions.keys.associateWith {
                document.getPage(it - 1).mediaBox.height
            }
            val t2 = System.currentTimeMillis()

            val stripper = LineCollectingStripper()
            stripper.getText(document)
            val t3 = System.currentTimeMillis()
            val nonTableLines = stripper.lines.filterNot { line ->
                val region = renderedTableRegions[line.page]
                region != null && isWithinTableBand(line.y, region, tableRegionPageHeights.getValue(line.page))
            }
            val rawParagraphs = linesToParagraphs(nonTableLines)
            // 见 RunningFooterFilter 类注释：过滤掉"浏览器打印 PDF 自带的页眉页脚水印"
            // （纯网址/纯日期时间一行，以及跟它们同页出现的纯页码计数一行），不是书本身
            // 的正文——过滤发生在这里（linesToParagraphs 之后），后面所有基于段落下标
            // 的计算（图片插入位置、目录页内定位）都直接用过滤后的结果，不需要额外
            // 处理"下标要跳过被删掉的段落"这种麻烦。
            val footerNoiseIndices = RunningFooterFilter.noiseIndices(
                rawParagraphs.map { PageTextLine(it.text, it.page) },
            )
            val paragraphs = rawParagraphs.filterIndexed { index, _ -> index !in footerNoiseIndices }
            val paragraphPages = paragraphs.map { it.page }
            val paragraphTopYs = paragraphs.map { it.topY }

            val inlineImages = buildInlineImages(pageScans, paragraphPages, excludePages = renderedTableRegions.keys)
            val t4 = System.currentTimeMillis()
            val tableImages = renderedTableRegions.map { (pageNo, region) ->
                val pageHeight = tableRegionPageHeights.getValue(pageNo)
                val regionTopYDirAdj = pageHeight - region.maxY
                ExtractedImage(
                    bitmap = tableRegionImages.getValue(pageNo),
                    afterParagraphIndex = ImagePlacement.afterParagraphIndexForRegion(
                        paragraphPages,
                        paragraphTopYs,
                        pageNo,
                        regionTopYDirAdj,
                    ),
                )
            }
            // 大纲抽取失败（文档没有大纲、大纲结构异常等）不能让整份文档的抽取失败，
            // 见类注释"大纲/目录抽取"一节——outer runCatching 是最后一道保险，内部
            // collectOutlineEntries 对每个目录项也单独兜底。
            val outline = runCatching { extractOutline(document) }.getOrDefault(emptyList())
            android.util.Log.d(
                "PdfReaderDebug",
                "页数=${document.numberOfPages} 疑似表格页=${tableRegions.size} " +
                    "扫描页面(表格检测+图片抽取)=${t1 - t0}ms 渲染表格区域=${t2 - t1}ms " +
                    "抽取文字=${t3 - t2}ms 组装图片=${t4 - t3}ms 总计=${t4 - t0}ms",
            )
            return PdfContent(
                paragraphs.map { it.text },
                inlineImages + tableImages,
                outline,
                paragraphPages,
                paragraphTopYs,
            )
        }
    }

    /**
     * 递归遍历 `document.getDocumentCatalog().getDocumentOutline()`，把 `PDOutlineItem`
     * 树摊平成一份按先序遍历排列的 [OutlineEntry] 列表——具体 API 见类注释"大纲/目录
     * 抽取"一节。没有大纲（`getDocumentOutline()` 返回 `null`，PDF 里根本没有内嵌
     * 书签，很常见）时返回空列表，这是"没有大纲"的正常状态，不是错误。
     */
    private fun extractOutline(document: PDDocument): List<OutlineEntry> {
        val root = document.documentCatalog.documentOutline ?: return emptyList()
        val entries = mutableListOf<OutlineEntry>()
        collectOutlineEntries(document, root.children(), depth = 0, entries)
        return entries
    }

    /**
     * 单个目录项的处理拆成两个独立的 `runCatching`：解析这一项自己的目标页失败（比如
     * `findDestinationPage` 遇到未知的 destination 类型会抛 `IOException`，或者
     * 目标页解析出来是 `null`——命名目标查不到、既无 destination 又无 GoTo action
     * 都会走到这个分支）只跳过这一项本身，不影响它的兄弟项；即使这一项解析失败，也
     * 继续递归它的子项——单个目录项的问题不该连累其余目录项，是和 [buildInlineImages]
     * 里"单张图片失败不连累其它"同一种降级精神。
     */
    private fun collectOutlineEntries(
        document: PDDocument,
        items: Iterable<PDOutlineItem>,
        depth: Int,
        out: MutableList<OutlineEntry>,
    ) {
        for (item in items) {
            runCatching {
                val page = item.findDestinationPage(document)
                if (page != null) {
                    val pageIndex = document.pages.indexOf(page)
                    if (pageIndex >= 0) {
                        out.add(
                            OutlineEntry(
                                title = item.title.orEmpty(),
                                pageNumber = pageIndex + 1,
                                depth = depth,
                                targetTopY = targetTopYOrNull(item, page),
                            ),
                        )
                    }
                }
            }
            runCatching { collectOutlineEntries(document, item.children(), depth + 1, out) }
        }
    }

    /**
     * 目录项在目标页内更精确的垂直位置（"距页面顶部多少 pt"，跟 [Line.y]/
     * [Paragraph.topY] 同一套坐标系——都是 `yDirAdj`，原点在页面左上、往下增大），
     * 解析不出来就返回 `null`——见 [OutlineNavigation] KDoc"页内精确定位"一节，`null`
     * 时退化成"只按页跳转"，不是错误。
     *
     * `findDestinationPage` 只负责解析出目标页（`PDPage`），不暴露具体的 destination
     * 对象，所以这里要自己再走一遍"直接 destination，或者包在 GoTo action 里"这两条
     * 路径——跟 `findDestinationPage` 内部走的是同一套判断，只是它不往外暴露。只处理
     * `/XYZ` 类型的目标（`PDPageXYZDestination`，PDF 里最常见的"跳到某页某个具体位置"
     * 写法，Adobe/多数 PDF 生成器的默认导出格式）；`/Fit`/`/FitH` 等其它类型的目标本身
     * 就没有精确的 Y 坐标，不在这次的修复范围内，走 `null` 退化成按页跳转。
     *
     * `top < 0` 当作"没有设置"跳过（返回 `null`）：PDF 规范里 `/XYZ` 数组的 `top`
     * 字段允许是 PDF null（"保持当前位置不变"），PdfBox-Android 的 `getTop()` 签名是
     * 基本类型 `int`，读到 PDF null 时会返回 `-1`（实测确认：用
     * `PDPageXYZDestination()` 不设置 `top` 直接读 `getTop()`，两份不同页高的文档都
     * 返回 `-1`，不是想当然的 `0`——如果当初直接假设"0=未设置"会导致有大纲的正常
     * 文档也被误判成"页内坐标全部缺失"）。真实的 `top=0`（指向页面最底部，实践中
     * 几乎不会出现）会被保留，只有确认是 PDFBox 默认哨兵值的负数才会被跳过。
     */
    private fun targetTopYOrNull(item: PDOutlineItem, page: PDPage): Float? = runCatching {
        val destination = item.destination ?: (item.action as? PDActionGoTo)?.destination
        val xyz = destination as? PDPageXYZDestination ?: return null
        val top = xyz.top
        if (top < 0) return null
        page.mediaBox.height - top
    }.getOrNull()

    /** 一页 content stream 里同时收集出来的矢量线段（表格检测用）和图片（图片抽取用）。 */
    /**
     * [hasImages] 是"这一页有没有 `Do` 图片操作符"，不管 [decodeImages] 开没开都会
     * 记录（很便宜，只是个布尔标记）；[images] 只在 `decodeImages=true` 时才会真的
     * 有内容——见 [PageContentStreamEngine] KDoc。
     */
    private data class PageScan(val segments: List<LineSegment>, val images: List<Bitmap>, val hasImages: Boolean)

    /**
     * 对文档每一页跑一遍 [PageContentStreamEngine]，一次遍历同时拿到表格检测用的矢量
     * 线段和（[decodeImages] 为真时）图片抽取用的 [Bitmap] 列表——见类注释"表格检测/
     * 图片抽取合并成一次遍历"一节（2026-08-19 性能优化）。单页扫描出异常（content
     * stream 解析问题等）只让那一页退化成"没有线段、没有图片"（不判定为表格、也不
     * 产出内嵌图片），不让整份文档的抽取失败——延续原来两个引擎各自的降级精神，
     * 合并成一次遍历后用同一个 `runCatching` 覆盖两种情形。
     */
    private fun scanPages(document: PDDocument, decodeImages: Boolean): Map<Int, PageScan> {
        val result = LinkedHashMap<Int, PageScan>(document.numberOfPages)
        for (pageIndex in 0 until document.numberOfPages) {
            val page = document.getPage(pageIndex)
            val scan = runCatching {
                val engine = PageContentStreamEngine(page, decodeImages)
                engine.processPage(page)
                PageScan(engine.segments, ImageStripStitcher.stitchIfTiled(engine.images), engine.hasImages)
            }.onFailure {
                android.util.Log.d("PdfReaderDebug", "scanPages 第${pageIndex + 1}页失败：$it")
            }.getOrDefault(PageScan(emptyList(), emptyList(), false))
            result[pageIndex + 1] = scan
        }
        return result
    }

    /**
     * 把 [tableRegions] 里每一页整页渲染成 [Bitmap]（[TABLE_PAGE_RENDER_DPI]），再裁剪
     * 到表格本身的包围盒（加 [TABLE_REGION_PADDING_PT] 内边距，见 [tableCropRect]）——
     * 只有渲染+裁剪都成功的页码才会出现在返回值里，失败的页码不进返回值，调用方
     * ([extractContent]) 就不会把那一页的文字行排除掉，等价于"这一页没有被判定为
     * 表格"，见类注释"表格检测"一节的降级说明。
     *
     * 用"整页渲染再裁剪"而不是"只渲染表格那一小块区域"：`PDFRenderer` 没有提供
     * 直接渲染页面局部区域的公开 API（`renderImageWithDPI` 只能整页渲染），裁剪
     * 一张已经渲染好的 `Bitmap` 是安卓平台最基础的操作，比自己拼一套局部渲染逻辑
     * 简单可靠得多——多渲染的部分（表格区域之外的整页内容）会在裁剪后直接丢弃，
     * 多花的渲染成本相对"表格检测→裁剪"这条路径的其它开销可以接受。
     */
    private fun renderTableRegionImages(document: PDDocument, tableRegions: Map<Int, TableRegion>): Map<Int, Bitmap> {
        if (tableRegions.isEmpty()) return emptyMap()
        val renderer = PDFRenderer(document)
        val result = mutableMapOf<Int, Bitmap>()
        for ((pageNo, region) in tableRegions) {
            val bitmap = runCatching {
                val pageHeight = document.getPage(pageNo - 1).mediaBox.height
                val fullPage = renderer.renderImageWithDPI(pageNo - 1, TABLE_PAGE_RENDER_DPI)
                val crop = tableCropRect(region, pageHeight, TABLE_PAGE_RENDER_DPI, fullPage.width, fullPage.height)
                Bitmap.createBitmap(fullPage, crop.left, crop.top, crop.width(), crop.height())
            }.getOrNull() ?: continue
            result[pageNo] = bitmap
        }
        return result
    }

    /** 表格包围盒四周留的内边距（PDF 坐标系 pt）——见 [tableCropRect] KDoc。 */
    private const val TABLE_REGION_PADDING_PT = 6f

    /**
     * 纯几何计算：把 [region]（PDF 页面坐标系，y 轴向上）换算成整页渲染出来的
     * [bitmapWidth]x[bitmapHeight] 像素图里该裁剪的矩形（像素坐标系，y 轴向下、
     * 原点左上），四周留 [TABLE_REGION_PADDING_PT] 的内边距——表格边框线本身往往
     * 比单元格文字的可见范围更紧，不留一点余量容易把边界上的文字裁掉一点点。
     *
     * 换算公式：`scale = dpi / 72`（PDF 的 1pt = 1/72 英寸，`renderImageWithDPI`
     * 就是按这个换算出实际像素）；`pixelY = (pageHeightPt - pdfY) * scale`——PDF
     * 坐标系原点在左下、y 轴向上，像素坐标系原点在左上、y 轴向下，两者唯一的转换
     * 就是"用页高减一下"，这也是本项目其它地方（[targetTopYOrNull]、[isWithinTableBand]）
     * 处理这两套坐标系转换时反复用到的同一个公式，不是这里现推的。
     *
     * 用 [Rect] 的坐标全部 `coerceIn` 卡在 `[0, bitmapWidth/bitmapHeight]` 范围内
     * ——内边距、四舍五入误差都可能让算出来的坐标略微超出图片实际范围，
     * `Bitmap.createBitmap` 对越界坐标会直接抛异常，裁剪一张已经渲染好的大图不该
     * 因为几个像素的误差整个失败。
     */
    internal fun tableCropRect(
        region: TableRegion,
        pageHeightPt: Float,
        dpi: Float,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): Rect {
        val scale = dpi / 72f
        val left = ((region.minX - TABLE_REGION_PADDING_PT) * scale).toInt().coerceIn(0, bitmapWidth - 1)
        val top = ((pageHeightPt - region.maxY - TABLE_REGION_PADDING_PT) * scale).toInt()
            .coerceIn(0, bitmapHeight - 1)
        val right = ((region.maxX + TABLE_REGION_PADDING_PT) * scale).toInt().coerceIn(left + 1, bitmapWidth)
        val bottom = ((pageHeightPt - region.minY + TABLE_REGION_PADDING_PT) * scale).toInt()
            .coerceIn(top + 1, bitmapHeight)
        return Rect(left, top, right, bottom)
    }

    /**
     * 纯逻辑：[lineYDirAdj]（[Line.y]，距页面顶部多少 pt）是不是落在表格区域的纵向
     * 范围内（同样加 [TABLE_REGION_PADDING_PT] 内边距，跟 [tableCropRect] 裁剪的
     * 范围保持一致——不能裁剪的时候留了内边距、排除文字的时候却没留，那样表格边缘
     * 的文字会被裁进图片里、又同时留在文字段落里，重复显示）。只判断纵向范围，
     * 不判断横向——见 [TableGridDetector] 类注释"已知局限"一节，这次的实现假设
     * 表格占满页面宽度的一个横向条带，不处理"表格和正文左右并排"这种布局。
     */
    internal fun isWithinTableBand(lineYDirAdj: Float, region: TableRegion, pageHeightPt: Float): Boolean {
        val topYDirAdj = pageHeightPt - region.maxY - TABLE_REGION_PADDING_PT
        val bottomYDirAdj = pageHeightPt - region.minY + TABLE_REGION_PADDING_PT
        return lineYDirAdj in topYDirAdj..bottomYDirAdj
    }

    /** [isSegmentOnPage] 允许线段端点超出页面边界一点点的容差（pt）。 */
    private const val PAGE_BOUNDS_TOLERANCE_PT = 1f

    /**
     * 判断一条 [LineSegment] 是不是落在页面可见范围（`[0, pageWidth] x [0, pageHeight]`，
     * 留一点点 [PAGE_BOUNDS_TOLERANCE_PT] 容差）内——真机踩坑，见类注释"表格区域
     * 裁剪"一节：真机反馈一份网页转 PDF 的长文，某一页目录列表+好几段正文被整块
     * 误判成表格区域裁掉，诊断日志实测确认这一页 content stream 里混着大量矢量线段
     * 的坐标跨度极大（Y 从 -4284 到 5076），而这份文档的 `MediaBox` 是完全正常的
     * `0~792`——排除了"页面坐标系原点偏移"这个猜测，说明这些线段本身就画在页面可见
     * 范围之外（大概率是生成这份 PDF 的工具把整个长网页当一块连续画布，每一"页"
     * 的 content stream 里其实包含了对整块画布的绘制指令，只是通过页面自身的
     * `MediaBox`/裁剪范围只显示其中一段——具体是哪个工具、哪种机制没有继续深挖，
     * 不影响这里的修法）。这些"看不见的线"不应该参与表格检测：用户读到的、以为
     * 是表格的东西，只能是页面可见范围内画出来的内容。
     *
     * 两个端点都要在范围内才算"在页面上"——只有一个端点在范围内的线段（比如一条
     * 从页面内延伸到页面外的线）保守地当作"不在页面上"丢弃，不去猜它在页面内的
     * 那一截该在哪，这类线段本来就不是真表格线该有的样子。
     */
    internal fun isSegmentOnPage(segment: LineSegment, pageWidth: Float, pageHeight: Float): Boolean {
        val minX = -PAGE_BOUNDS_TOLERANCE_PT
        val minY = -PAGE_BOUNDS_TOLERANCE_PT
        val maxX = pageWidth + PAGE_BOUNDS_TOLERANCE_PT
        val maxY = pageHeight + PAGE_BOUNDS_TOLERANCE_PT
        fun inBounds(x: Float, y: Float) = x in minX..maxX && y in minY..maxY
        return inBounds(segment.x1, segment.y1) && inBounds(segment.x2, segment.y2)
    }

    /**
     * 内部用：段落文字 + 这个段落所在的页码（页码从 1 起，用于图片按页归类）。
     * [topY] 是这个段落第一行的 [Line.y]（距页面顶部多少 pt），供目录页内精确定位用。
     */
    private data class Paragraph(val text: String, val page: Int, val topY: Float)

    private fun linesToParagraphs(lines: List<Line>): List<Paragraph> {
        if (lines.isEmpty()) return emptyList()
        if (lines.size == 1) return listOf(Paragraph(lines[0].text, lines[0].page, lines[0].y))

        val gaps = (1 until lines.size).map { lines[it].y - lines[it - 1].y }
        val typicalGap = gaps.sorted()[gaps.size / 2]
        val paragraphThreshold = typicalGap * 1.5f

        val texts = mutableListOf<StringBuilder>()
        val pages = mutableListOf<Int>()
        val topYs = mutableListOf<Float>()
        texts.add(StringBuilder(lines[0].text))
        pages.add(lines[0].page)
        topYs.add(lines[0].y)

        for (i in 1 until lines.size) {
            val gap = lines[i].y - lines[i - 1].y
            val pageChanged = lines[i].page != lines[i - 1].page
            // 跨页强制切段落：y 坐标每翻一页就从页顶重新开始，纯按 gap 判断在跨页处
            // 没有意义，见类注释"图片抽取"一节。
            if (pageChanged || gap > paragraphThreshold) {
                texts.add(StringBuilder(lines[i].text))
                pages.add(lines[i].page)
                topYs.add(lines[i].y)
            } else {
                appendLine(texts.last(), lines[i].text)
            }
        }
        return texts.indices.map { Paragraph(normalizeCjkSpacing(texts[it].toString()), pages[it], topYs[it]) }
    }

    /**
     * 把 [scanPages] 已经收集好的图片，配合 [ImagePlacement] 算出每张图片该插在哪个
     * 段落之后——纯粹的列表组装，不再需要重新解析 content stream（那部分开销已经在
     * [scanPages] 里付过了），所以这一步很快。
     *
     * [excludePages] 是检测到表格区域、已经裁剪渲染出表格图片的页（见类注释"表格
     * 检测"一节）——这一整页的内嵌图片都跳过，不细分"是不是真的落在表格区域内"：
     * 表格区域内的内嵌图片确实已经包含在裁剪结果里，需要跳过；表格区域外的内嵌
     * 图片理论上可以继续单独展示，但 [PageContentStreamEngine] 目前不记录每张
     * 内嵌图片在页面上的坐标（只记录朝向修正用的 CTM），没法判断"这张图在不在表格
     * 区域内"，索性整页跳过——比"表格区域裁剪"改动之前（那时候是整页文字+整页
     * 图片都跳过）范围已经小了一圈，是有意的简化，不是遗漏。
     */
    private fun buildInlineImages(
        pageScans: Map<Int, PageScan>,
        paragraphPages: List<Int>,
        excludePages: Set<Int>,
    ): List<ExtractedImage> {
        val images = mutableListOf<ExtractedImage>()
        for ((pageNo, scan) in pageScans) {
            if (pageNo in excludePages) continue
            val afterIndex = ImagePlacement.afterParagraphIndex(paragraphPages, pageNo)
            for (bitmap in scan.images) {
                images.add(ExtractedImage(bitmap, afterIndex))
            }
        }
        return images
    }

    /**
     * 见类注释"内嵌图片朝向修正"一节。把 CTM 的线性部分（`a`/`b`/`c`/`d`，忽略跟朝向
     * 无关的平移分量 `e`/`f`）换算成需要施加在 [bitmap] 上的修正矩阵。
     *
     * ## 2026-08-19 勘误：原纸面推导的基准假设是反的
     *
     * 最初的版本靠纸面推导，假定"不翻转"的基准 CTM 是 `a>0、d<0`（即 `R0=diag(1,-1)`
     * 要跟 CTM 复合）。真机上一本扫描书全书上下+左右都反了，用这个假设"修完"之后
     * 依然是反的——说明假设本身就错了，不是又漏了一层。改用真正独立的验证方式：
     * 不再自己推导数学，用 Python 手写最小 PDF（4 色象限测试图 + 明确指定的 CTM），
     * 拿 poppler 的 `pdftoppm`（跟 PDFBox 完全独立的另一套 PDF 渲染器）渲染出"标准
     * 答案"，再用 PIL 采样具体像素颜色，逐个跟本函数的输出比对：
     *
     * - CTM `[100 0 0 150 50 50]`（`a>0 d>0`）→ ground truth 证明原始 `Bitmap`
     *   **不需要任何修正**——也就是说"不翻转"的基准其实是 `a>0、d>0`，不是
     *   `a>0、d<0`。
     * - CTM `[100 0 0 -150 50 200]`（`a>0 d<0`）→ ground truth 证明**需要垂直
     *   翻转**——跟原假设正好相反。
     * - CTM `[0 100 -100 0 150 50]`（90° 旋转分支）→ ground truth 证明修正矩阵
     *   应为 `[[0,1],[-1,0]]`，而旧代码在这个输入下算出的是 `[[0,-1],[-1,0]]`
     *   （`m01` 符号反了，`m10` 是对的）。
     *
     * 据此改用的基准和分支逻辑见下面 `when` 分支里的具体判断，不再重复整套矩阵推导——
     * 这次的教训是"纸面推导容易在符号方向上出错，且不会自证"，往后如果再改这块，
     * 应该继续用"独立渲染器 + 具体像素采样"这种能被证伪的方式验证，而不是再推一遍
     * 公式。三份测试 PDF 和 poppler 渲染结果的构造脚本未纳入仓库（纯验证用途，见
     * commit message）。
     *
     * 只处理"轴对齐"的情形——`b≈0` 且 `c≈0`（纯翻转，含不翻转）、或 `a≈0` 且
     * `d≈0`（90°/270° 旋转，可能叠加翻转），覆盖 D4 群全部 8 种朝向；任何其它情形
     * （任意角度旋转/斜切）原样返回、不修正——这是有意的降级范围，见类注释"已知局限"
     * 一节。
     *
     * 拆成 [orientationMatrixOrNull]（纯矩阵计算，不碰 `Bitmap` 像素）+ 这个函数
     * （拿到矩阵后调 `Bitmap.createBitmap` 做真正的像素重采样）两步——不是过度设计，
     * 是被测试逼出来的：写 [PdfTextExtractorImageOrientationTest] 时发现，本机
     * Robolectric 环境下 `Bitmap.createBitmap(src,x,y,w,h,matrix,filter)` 这个重载
     * 的影子实现（Shadow）不会真的按 `matrix` 重采样像素，返回的是一张空白位图
     * （程序化验证过：拿同一个翻转矩阵直接调这个重载，输出全黑，而
     * `matrix.mapPoints(...)` 这种纯数学、不涉及像素重采样的调用在同一环境下结果
     * 完全正确）——这是 Robolectric 对 Canvas 像素级绘制这类操作的影子实现精度
     * 限制，不是真机上的行为（真机上 `Bitmap.createBitmap` 带 `Matrix` 参数是
     * Android 平台最基础、最成熟的位图变换 API，广泛用于任意图片旋转/翻转场景，
     * 不需要也没有条件在这个项目里重新验证平台本身的正确性）。拆开之后，
     * [orientationMatrixOrNull] 这个真正包含"翻转/旋转判断逻辑"的部分可以只用
     * `mapPoints` 验证（可靠），而"矩阵拿去重采样像素对不对"这一步交给 Android
     * 平台自己的职责范围。
     */
    internal fun applyCtmOrientation(bitmap: Bitmap, ctm: PdfMatrix): Bitmap {
        val matrix = orientationMatrixOrNull(ctm, bitmap.width, bitmap.height) ?: return bitmap
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /**
     * 见 [applyCtmOrientation] KDoc：只做矩阵计算，不碰任何 `Bitmap` 像素。返回
     * `null` 表示"不需要修正"——包括真正的 canonical（无翻转无旋转）、CTM 退化成
     * 零矩阵、以及非轴对齐（不在本次修复范围内）这三种情况，调用方统一处理成
     * "原样返回"。
     *
     * 用 [PdfTextExtractorImageOrientationTest] 验证过：对 8 种轴对齐 CTM，这个
     * 函数算出的矩阵用 `mapPoints` 作用在 `Bitmap` 四个角的像素坐标上，得到的
     * 落点和"从 CTM 独立算出来的预期象限映射"这个参照模型完全一致——见该测试
     * 文件顶部注释，为什么改成跟参照模型比对而不是跟 `PDFRenderer` 整页渲染比对。
     *
     * 可见性是 `internal` 不是 `private`：只是为了让同一模块的单元测试能直接调用、
     * 独立验证这个函数的翻转/旋转逻辑本身，不代表这是对外公开 API，不违反任务
     * 边界里"不改现有类公开签名"的要求（这是新函数，不是修改已有签名）。
     */
    internal fun orientationMatrixOrNull(ctm: PdfMatrix, width: Int, height: Int): Matrix? {
        val a = ctm.scaleX
        val b = ctm.shearY
        val c = ctm.shearX
        val d = ctm.scaleY
        // 判断"是否为零"用相对容差：真实 PDF 里理论上的 0 经过浮点运算常常会留下
        // 极小残余误差（比如 1e-6 量级），不能用 `== 0f` 精确比较；容差跟着分量本身
        // 的量级走，避免大尺寸图片（CTM 分量数值本身就是几百上千）把噪声判断成信号。
        val eps = (1e-3f * maxOf(abs(a), abs(b), abs(c), abs(d))).coerceAtLeast(1e-6f)

        val m00: Float
        val m01: Float
        val m10: Float
        val m11: Float
        when {
            // 四个分量都接近零：退化矩阵（图片被缩放成几乎不可见），没有意义去修正。
            abs(a) < eps && abs(b) < eps && abs(c) < eps && abs(d) < eps -> return null
            abs(b) < eps && abs(c) < eps -> {
                // 不旋转，只可能翻转：基准是 a>0、d>0（见上方"2026-08-19 勘误"）。
                m00 = if (a < 0) -1f else 1f
                m01 = 0f
                m10 = 0f
                m11 = if (d > 0) 1f else -1f
            }
            abs(a) < eps && abs(d) < eps -> {
                // 90°/270° 旋转（可能叠加翻转）。
                m00 = 0f
                m01 = -sign(c)
                m10 = -sign(b)
                m11 = 0f
            }
            else -> return null // 非轴对齐：不在本次修复范围内，原样返回。
        }
        if (m00 == 1f && m01 == 0f && m10 == 0f && m11 == 1f) return null // 无需修正

        val cx = width / 2f
        val cy = height / 2f
        return Matrix().apply {
            setValues(floatArrayOf(m00, m01, 0f, m10, m11, 0f, 0f, 0f, 1f))
            val mapped = floatArrayOf(cx, cy)
            mapPoints(mapped)
            // 旋转/翻转默认绕原点，重新平移一次让图片中心还落在原来的中心位置，
            // 不然内容会跑出 Bitmap 的可见范围。
            postTranslate(cx - mapped[0], cy - mapped[1])
        }
    }

    /** 把新的一行接到当前段落末尾：CJK 边界不加空格，其余情况加一个空格。 */
    private fun appendLine(paragraph: StringBuilder, nextLine: String) {
        if (paragraph.isEmpty() || nextLine.isEmpty()) {
            paragraph.append(nextLine)
            return
        }
        val lastChar = paragraph.last()
        val firstChar = nextLine.first()
        if (!isCjk(lastChar) && !isCjk(firstChar)) {
            paragraph.append(' ')
        }
        paragraph.append(nextLine)
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0xF900..0xFAFF ||
            code in 0x3000..0x303F ||
            code in 0xFF00..0xFFEF
    }

    /** 内部用：一行文字 + 纵坐标 + 所在页码（页码从 1 起，来自 `getCurrentPageNo()`）。 */
    private data class Line(val text: String, val y: Float, val page: Int)

    private class LineCollectingStripper : PDFTextStripper() {
        val lines = mutableListOf<Line>()

        init {
            // 2026-08-19 真机修复：PDFTextStripper 默认按 content stream 里的绘制顺序
            // 输出文字，不是按视觉上的从上到下、从左到右顺序——这对单栏正文通常没区别
            // （大部分 PDF 生成器本来就是按视觉顺序画的），但"表单/表格模板"这类常见
            // 生成方式（先整体画一批标签、再单独一批填值，或者分栏绘制）会导致行内
            // 乃至跨行的文字顺序错乱。真机复现：一份体检报告 PDF 里"年龄: 43岁"被拆成
            // "43 岁年龄:"，一份技术规格表里同一行的好几个数值被打乱顺序拼在一起——
            // 两份文档开这个开关后都恢复了正常的"标签在前、值在后"顺序，验证有效。
            // 见 NOTES.md 相关条目：一开始以为要专门造一套"无边框表格检测"的复杂逻辑，
            // 结果真正的根因只是这一行没打开，比想象的简单得多。
            sortByPosition = true
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (text.isBlank()) return
            val y = textPositions.firstOrNull()?.yDirAdj ?: return
            lines.add(Line(fixRadicalVariants(text), y, currentPageNo))
        }
    }

    /**
     * PDFBox 图形流引擎适配层：一次遍历同时做两件事——见类注释"表格检测/图片抽取
     * 合并成一次遍历"一节（2026-08-19 性能优化）：
     *
     * 1. 把画路径用的操作符（`m`/`l`/`c`/`re`/`S`/`f`/`B`……）转成一批 [LineSegment]，
     *    交给纯逻辑 [TableGridDetector] 判断（原来独立的 `TableGridStreamEngine`）。
     * 2. 处理 `Do`/`BI…EI` 操作符（画图片）——[drawImage] 捕获画这张图那一刻的 CTM
     *    （`graphicsState.currentTransformationMatrix`），交给 [applyCtmOrientation]
     *    修正朝向（原来独立的 `ImageDrawStreamEngine`）。
     *
     * 这两件事互不干扰：PDF content stream 里矢量路径操作符和图片操作符是分开触发
     * 不同回调的，合并只是省掉"同一份 content stream 重新解析一遍 token"的开销，
     * 不改变各自的判断逻辑。
     *
     * 只有在路径被真正"画出来"（[strokePath]/[fillPath]/[fillAndStrokePath]，对应
     * PDF 的 `S`/`f`/`B` 等操作符）时，累积在 [pendingSegments] 里的线段才会提交进
     * [segments]；纯粹用于裁剪、从未描边/填充的路径（[endPath]，对应 `n` 操作符）会
     * 被直接丢弃——这样"看不见的裁剪路径"不会污染网格判断。
     *
     * 曲线（[curveTo]）只把终点当作直线的端点纳入路径追踪（用于正确维护"当前点"），
     * 不生成线段——表格网格线是直线，不会是贝塞尔曲线，忽略曲线本身的走向不影响
     * 判断，也避免把任意曲线误当成网格线的一部分。
     *
     * 单张图片转换失败（`getImage()` 抛异常）只跳过那一张，不中断整页处理——延续
     * [buildInlineImages] 一贯的降级精神。
     *
     * [decodeImages] 是 2026-08-19"按需加载"增量补的开关：[Session] 的即时可用阶段
     * （见 [Session] KDoc）只需要知道"这一页有没有图片"（用于占位），不需要真的解码
     * 出 [Bitmap]——`pdImage.image`/[decodeJpegWithNativeSubsampling] 这一步本身就是
     * 图片解码里最耗时的部分，`decodeImages=false` 时直接跳过，只记一下
     * [hasImages]，图片矢量线段（表格检测用）不受影响，两者是内容流里两种不同的
     * 操作符，互不干扰。
     */
    private class PageContentStreamEngine(page: PDPage, private val decodeImages: Boolean) :
        PDFGraphicsStreamEngine(page) {
        val segments = mutableListOf<LineSegment>()
        val images = mutableListOf<Bitmap>()
        var hasImages = false
            private set
        private val pendingSegments = mutableListOf<LineSegment>()

        /**
         * 2026-08-20 真机反馈修复：一份矢量图形极端密集的文档（地图/图表类扫描件）
         * 真机测出能把 App 拖进持续 GC、最终 OutOfMemoryError 的地步——[segments]
         * 是无上限增长的 `MutableList`，content stream 里每一条 `m`/`l`/`re` 都会
         * 往里加，正常表格的网格线是几十条量级，但复杂矢量美术/地图内容单页能画出
         * 几十万条路径，全部收集下来内存直接爆掉。真表格的网格线数量远低于这个
         * 上限，加这条安全阀不会影响任何真实表格的检测结果，只会让"这页明显不是
         * 表格、是复杂图形"的页提前放弃收集（那一页仍然正常抽取文字/图片，只是不
         * 参与表格检测——跟本类一贯的"宁可漏检、不可拖垮 App"降级精神一致）。
         */
        private var segmentCollectionCapped = false
        private var currentX = 0f
        private var currentY = 0f
        private var subpathStartX = 0f
        private var subpathStartY = 0f

        /** 见 [segmentCollectionCapped] KDoc——所有往 [pendingSegments] 加线段的地方都走这里。 */
        private fun addPendingSegment(segment: LineSegment) {
            if (segmentCollectionCapped) return
            if (segments.size + pendingSegments.size >= MAX_SEGMENTS_PER_PAGE) {
                segmentCollectionCapped = true
                pendingSegments.clear()
                return
            }
            pendingSegments.add(segment)
        }

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
            // `re` 操作符：矩形四条边直接进 pendingSegments，等对应的 stroke/fill
            // 操作符提交（表格边框在 Chromium 输出里常见的画法就是细长填充矩形，
            // 见 TableGridDetector 类注释）。
            addPendingSegment(LineSegment(p0.x, p0.y, p1.x, p1.y))
            addPendingSegment(LineSegment(p1.x, p1.y, p2.x, p2.y))
            addPendingSegment(LineSegment(p2.x, p2.y, p3.x, p3.y))
            addPendingSegment(LineSegment(p3.x, p3.y, p0.x, p0.y))
            currentX = p0.x
            currentY = p0.y
            subpathStartX = p0.x
            subpathStartY = p0.y
        }

        override fun moveTo(x: Float, y: Float) {
            currentX = x
            currentY = y
            subpathStartX = x
            subpathStartY = y
        }

        override fun lineTo(x: Float, y: Float) {
            addPendingSegment(LineSegment(currentX, currentY, x, y))
            currentX = x
            currentY = y
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            // 只推进"当前点"，不生成线段——见类 KDoc。
            currentX = x3
            currentY = y3
        }

        override fun closePath() {
            addPendingSegment(LineSegment(currentX, currentY, subpathStartX, subpathStartY))
            currentX = subpathStartX
            currentY = subpathStartY
        }

        override fun endPath() {
            // 对应 `n`（只裁剪不画）：丢弃尚未提交的线段，见类 KDoc。
            pendingSegments.clear()
        }

        override fun strokePath() {
            segments.addAll(pendingSegments)
            pendingSegments.clear()
        }

        override fun fillPath(windingRule: Path.FillType) {
            segments.addAll(pendingSegments)
            pendingSegments.clear()
        }

        override fun fillAndStrokePath(windingRule: Path.FillType) {
            segments.addAll(pendingSegments)
            pendingSegments.clear()
        }

        override fun getCurrentPoint(): PointF = PointF(currentX, currentY)

        override fun drawImage(pdImage: PDImage) {
            hasImages = true
            if (!decodeImages) return
            // 见 decodeJpegWithNativeSubsampling KDoc"第三次尝试"一节——只对 JPEG
            // 编码、且长边确实超标的图片生效；不满足条件（不是 JPEG、没超标、原生
            // 解码本身失败）都回退到一直可靠的 `pdImage.image` 原始分辨率解码。
            val bitmap = decodeJpegWithNativeSubsampling(pdImage)
                ?: runCatching { pdImage.image }.getOrNull()
                ?: return
            val ctm = graphicsState.currentTransformationMatrix
            images.add(applyCtmOrientation(bitmap, ctm))
        }

        // 表格网格检测不关心裁剪区域、阴影填充，当无操作处理。
        override fun clip(windingRule: Path.FillType) = Unit
        override fun shadingFill(shadingName: com.tom_roush.pdfbox.cos.COSName) = Unit
    }

    /**
     * 一页的展示内容——[Session.loadPage] 的返回值，见该方法 KDoc"文字/图片真正按需
     * 加载"一节完整背景。[blocks] 只会是 [DisplayBlock.Text]/[DisplayBlock.Image]
     * 这两种（不会是 [DisplayBlock.Placeholder]——"这一页还没加载出来"这件事由调用方
     * 自己决定要不要显示占位符，不是 [loadPage] 的返回值该表达的状态），按页内顺序
     * 排列（段落/图片穿插，复用 [ImagePlacement] 定位逻辑，跟 [extractContent] 里
     * 同一套摆放规则）。
     */
    data class PageContent(val blocks: List<DisplayBlock>)

    /**
     * 按需加载版本的抽取入口——2026-08-19 增量。用户反馈打开速度明显慢于 WPS，真机
     * 日志确认耗时大头集中在图片解码/表格区域渲染，文字抽取本身一直很快（见
     * [extractContent] 历次改动记录的耗时日志）。
     *
     * 跟 [extractContent] 的区别：[extractContent] 一次性做完所有耗时工作（文字+
     * 表格检测+全部图片解码）才返回一份完整的 [PdfContent]；[Session] 打开后立刻
     * 可以读到文字（[paragraphs]/[paragraphPages]/[paragraphTopY]，跟 [PdfContent]
     * 同名字段语义一致）和"哪些展示位置需要加载图片/表格区域"
     * （[pendingMediaPageByAfterIndex]），但图片/表格区域本身的 [Bitmap] 解码推迟到
     * 调用方主动调 [loadPageMedia] 才做——调用方（UI 层）可以先把文字全部显示出来，
     * 图片/表格区域先放占位符，再按页（比如从当前可见页开始）后台调
     * [loadPageMedia]，加载完再把占位符换成真实内容。
     *
     * ## 重新扫描而不是缓存解码句柄
     *
     * [loadPageMedia] 被调用时会重新跑一遍那一页的 [PageContentStreamEngine]（这次
     * `decodeImages=true`），而不是复用构造时 [pageScans]（那次 `decodeImages=false`，
     * 图片部分没有真的解码，没有句柄可复用）。重新扫一遍一页的 content stream（不含
     * 图片解码本身）开销只有几毫秒量级（真机日志佐证：258 页文档在图片全部解码失败
     * 快速跳过的极端情况下，"扫描页面"总耗时不到 2 秒，摊到每页只有几毫秒）——比起
     * "让图片解码句柄跨页面处理保持有效"这种要求对 PdfBox-Android 内部实现做更强
     * 假设、没有把握是否支持的设计，重新扫一遍更简单可靠，多付的这点开销可以接受。
     *
     * ## 已知局限：表格区域的文字排除不等真的渲染成功就先做了
     *
     * [extractContent] 排除表格区域内的文字行之前，会先真的把该区域渲染出来，只有
     * 渲染成功才排除对应文字，渲染失败就退回正常文字抽取（见 [extractContent] 里
     * `renderedTableRegions` 那一步）——这是因为 [extractContent] 反正要把所有页都
     * 渲染一遍，"先渲染再决定要不要排除文字"不多花代价。[Session] 做不到这一点：
     * 即时可用阶段的核心意义就是不去渲染任何图片/表格区域，所以只能仅凭表格区域的
     * 几何范围（不实际渲染）就决定排除哪些文字。真机测试中没有观察到表格区域渲染
     * 失败的案例（这台设备上失败率是 0），如果确实发生（内存不足等极端情况），
     * [loadPageMedia] 对应位置会解码失败、占位符停在"加载中"状态，而不是像
     * [extractContent] 那样退回显示原文字——这是一个刻意接受的、比 [extractContent]
     * 更差一点的边界情况处理，真机没有实际测到过这个边界情况被触发。
     *
     * ## 2026-08-20 增量：文字/图片真正按需加载——[loadPage] 是新的核心入口
     *
     * 真机反馈一份 4232 页的文档打开会 `OutOfMemoryError`（NOTES.md #21）——根因不是
     * 单页内容复杂，是"一次性抽完全部页面的文字"（[paragraphs]/[paragraphPages]/
     * [paragraphTopY] 这套字段，[init] 块里一次性算好）这个模式，遇到几千页量级的
     * 文档时内存和时间都撑不住。完整方案见 `/Users/mac/.claude/plans/fizzy-snuggling-cloud.md`
     * （RecyclerView 窗口式重构，条目粒度 = 页，[MainActivity] 只在真正翻到某一页时
     * 才调 [loadPage]）。
     *
     * [loadPage] 是分步实施的第 2 步：新增的按页加载入口，跟旧的
     * [paragraphs]/[pendingMediaPageByAfterIndex]/[loadPageMedia] 这套"一次性抽完
     * 全部"的字段暂时并存——[MainActivity] 还没有切换到 `RecyclerView`（第 3 步）
     * 之前继续依赖旧字段，所以这一步不删除、不修改它们，只新增。等第 3 步
     * `MainActivity` 改造完、不再依赖旧字段后，[init] 块里"一次性扫描全部页面"这部分
     * 才能真正删除——**这一步本身还不能修好 4232 页 OOM 的问题**，`Session` 构造时
     * 仍然会做全文档扫描，[loadPage] 只是新加的、还没被真正用起来的能力。
     *
     * ### 页脚水印检测的改造：全文档已知 -> 样本学习
     *
     * 旧的 [RunningFooterFilter.noiseIndices] 判断"标题类重复"水印需要看到跨页的
     * 重复率，按需加载后不再有"全文档所有行"可用。[footerLearnedTitles]
     * 在 `Session` 打开时用一个页数有限的样本（[FOOTER_SAMPLE_PAGE_COUNT]）跑一次
     * [RunningFooterFilter.learnTitleLikeNoiseTexts]，学出"这些具体文字算噪音"，
     * [loadPage] 内部调 [RunningFooterFilter.pageNoiseIndices] 直接查表——见
     * [RunningFooterFilter] 类注释"样本学习 + 按页应用"一节的完整设计理由和已知局限。
     */
    class Session private constructor(private val document: PDDocument) : java.io.Closeable {
        private val tConstructStart = System.currentTimeMillis()

        val pageCount: Int = document.numberOfPages

        init {
            // 2026-08-20 真机诊断确认过一份 4232 页文档会在后面的阶段 OOM（NOTES.md
            // #21）——这一步本身几乎不耗时，但打出页数很关键：只有这行出现、后面
            // 没有任何阶段日志时，说明卡在了 PDDocument.load 本身（文件拷贝/PDF
            // 结构解析），不是 Session 内部的哪个具体阶段，帮排查省一轮猜测。
            android.util.Log.d("PdfReaderDebug", "Session 开始构造 页数=$pageCount")
        }

        val outline: List<OutlineEntry> = runCatching { extractOutline(document) }.getOrDefault(emptyList())

        private val pageScans: Map<Int, PageScan> = scanPages(document, decodeImages = false)

        private val tAfterScan = System.currentTimeMillis()

        /** 表格区域按页缓存——[loadPageMedia] 复用同一份，不用重新跑一遍检测。 */
        private val tableRegions: Map<Int, TableRegion> = pageScans.mapNotNull { (pageNo, scan) ->
            val page = document.getPage(pageNo - 1)
            val onPageSegments = scan.segments.filter {
                isSegmentOnPage(it, page.mediaBox.width, page.mediaBox.height)
            }
            TableGridDetector.tableRegionOrNull(onPageSegments)?.let { pageNo to it }
        }.toMap()

        private val tAfterTableRegions = System.currentTimeMillis()

        private val tablePageHeights: Map<Int, Float> = tableRegions.keys.associateWith {
            document.getPage(it - 1).mediaBox.height
        }

        val paragraphs: List<String>
        val paragraphPages: List<Int>
        val paragraphTopY: List<Float>

        /**
         * 哪个展示块下标之后需要插入媒体占位符，值是需要在那个位置加载的页码列表
         * （通常只有一个，见类注释"已知局限"一节旁的边界情况）——表格区域优先于
         * 内嵌图片（一页两者只会算进一个，跟 [extractContent] 里"检测到表格区域的
         * 页不再单独抽取内嵌图片"是同一个降级精神）。UI 层遍历这个表，在对应展示块
         * 下标插入占位符，之后调 [loadPageMedia] 换成真内容。
         */
        val pendingMediaPageByAfterIndex: Map<Int, List<Int>>

        init {
            val stripper = LineCollectingStripper()
            stripper.getText(document)
            val t1 = System.currentTimeMillis()
            val nonTableLines = stripper.lines.filterNot { line ->
                val region = tableRegions[line.page]
                region != null && isWithinTableBand(line.y, region, tablePageHeights.getValue(line.page))
            }
            val rawParagraphs = linesToParagraphs(nonTableLines)
            val footerNoiseIndices = RunningFooterFilter.noiseIndices(
                rawParagraphs.map { PageTextLine(it.text, it.page) },
            )
            val filtered = rawParagraphs.filterIndexed { index, _ -> index !in footerNoiseIndices }
            paragraphs = filtered.map { it.text }
            paragraphPages = filtered.map { it.page }
            paragraphTopY = filtered.map { it.topY }

            val pending = mutableMapOf<Int, MutableList<Int>>()
            for ((pageNo, scan) in pageScans) {
                val region = tableRegions[pageNo]
                val hasMedia = region != null || scan.hasImages
                if (!hasMedia) continue
                val afterIndex = if (region != null) {
                    val regionTopYDirAdj = tablePageHeights.getValue(pageNo) - region.maxY
                    ImagePlacement.afterParagraphIndexForRegion(
                        paragraphPages,
                        paragraphTopY,
                        pageNo,
                        regionTopYDirAdj,
                    )
                } else {
                    ImagePlacement.afterParagraphIndex(paragraphPages, pageNo)
                }
                pending.getOrPut(afterIndex) { mutableListOf() }.add(pageNo)
            }
            pendingMediaPageByAfterIndex = pending
            val t2 = System.currentTimeMillis()
            android.util.Log.d(
                "PdfReaderDebug",
                "Session.init 页数=$pageCount 疑似表格页=${tableRegions.size} " +
                    "扫描页面(不解码图片)=${tAfterScan - tConstructStart}ms " +
                    "表格区域检测=${tAfterTableRegions - tAfterScan}ms " +
                    "抽取文字(PDFTextStripper)=${t1 - tAfterTableRegions}ms " +
                    "段落切分+页脚过滤+占位符定位=${t2 - t1}ms " +
                    "总计=${t2 - tConstructStart}ms 待加载位置数=${pending.size}",
            )
        }

        /**
         * 真正做耗时工作的地方：解码出这一页表格区域（如果有）或内嵌图片的真实
         * [Bitmap]——一页要么算进表格区域要么算内嵌图片，不会两者都有（见
         * [pendingMediaPageByAfterIndex] 类注释）。每一页只应该被加载一次，调用方
         * 负责这件事，这里不做缓存/去重，重复调用会重复做一遍耗时工作。
         */
        fun loadPageMedia(pageNo: Int): List<Bitmap> {
            val region = tableRegions[pageNo]
            if (region != null) {
                val cropped = runCatching {
                    val pageHeight = tablePageHeights.getValue(pageNo)
                    val renderer = PDFRenderer(document)
                    val fullPage = renderer.renderImageWithDPI(pageNo - 1, TABLE_PAGE_RENDER_DPI)
                    val crop = tableCropRect(region, pageHeight, TABLE_PAGE_RENDER_DPI, fullPage.width, fullPage.height)
                    Bitmap.createBitmap(fullPage, crop.left, crop.top, crop.width(), crop.height())
                }.getOrNull()
                return listOfNotNull(cropped)
            }
            val page = document.getPage(pageNo - 1)
            return runCatching {
                val engine = PageContentStreamEngine(page, decodeImages = true)
                engine.processPage(page)
                ImageStripStitcher.stitchIfTiled(engine.images)
            }.getOrDefault(emptyList())
        }

        /**
         * 见类注释"页脚水印检测的改造"一节：只在样本范围内（前 [FOOTER_SAMPLE_PAGE_COUNT]
         * 页，文档不够这么多页就取全部）跑一次 [RunningFooterFilter
         * .learnTitleLikeNoiseTexts]，学出的文本集合供 [loadPage] 按页查表用。样本
         * 抽取失败（极端情况，比如这几页本身有问题）不影响 `Session` 整体可用，退化
         * 成"没学到任何标题类噪音"，等价于这份文档不做标题类水印过滤——比抽取失败
         * 让整个 `Session` 都打不开更符合"宁可漏检"的一贯降级精神。
         */
        private val footerLearnedTitles: Set<String> = runCatching {
            val sampleEndPage = minOf(FOOTER_SAMPLE_PAGE_COUNT, pageCount)
            if (sampleEndPage < 1) return@runCatching emptySet()
            val stripper = LineCollectingStripper()
            stripper.startPage = 1
            stripper.endPage = sampleEndPage
            stripper.getText(document)
            RunningFooterFilter.learnTitleLikeNoiseTexts(stripper.lines.map { PageTextLine(it.text, it.page) })
        }.getOrDefault(emptySet())

        /**
         * 按需加载的核心入口——见类注释"文字/图片真正按需加载"一节完整背景。只处理
         * [pageNo] 这一页：抽这一页的文字、测这一页有没有表格区域、按需解码这一页的
         * 图片，返回值已经是可以直接渲染的 [PageContent]（不需要调用方再拼装）。
         *
         * 跟 [loadPageMedia] 一样"重新扫描而不是缓存解码句柄"（见该方法 KDoc 完整
         * 理由）——[loadPage] 内部按需再调一次 [PageContentStreamEngine]（先
         * `decodeImages=false` 判断有没有表格/内嵌图片，是内嵌图片的话再
         * `decodeImages=true` 重新扫一遍拿真正的 [Bitmap]），这一页的开销跟
         * [loadPageMedia] 是同一个量级（几十毫秒），不会因为"按页调用"就变慢。
         *
         * 表格区域优先于内嵌图片（跟 [pendingMediaPageByAfterIndex] 类注释、
         * [extractContent] 是同一个降级精神）：检测到表格区域就不再抽取这一页的
         * 内嵌图片（假设都已经包含在裁剪出来的表格图片里）。
         */
        fun loadPage(pageNo: Int): PageContent {
            val page = document.getPage(pageNo - 1)
            val pageHeight = page.mediaBox.height

            val scanResult = runCatching {
                val engine = PageContentStreamEngine(page, decodeImages = false)
                engine.processPage(page)
                engine.segments to engine.hasImages
            }.getOrDefault(emptyList<LineSegment>() to false)
            val onPageSegments = scanResult.first.filter { isSegmentOnPage(it, page.mediaBox.width, pageHeight) }
            val tableRegion = TableGridDetector.tableRegionOrNull(onPageSegments)
            val hasImages = scanResult.second

            val stripper = LineCollectingStripper()
            stripper.startPage = pageNo
            stripper.endPage = pageNo
            runCatching { stripper.getText(document) }
            val nonTableLines = if (tableRegion != null) {
                stripper.lines.filterNot { isWithinTableBand(it.y, tableRegion, pageHeight) }
            } else {
                stripper.lines
            }
            val rawParagraphs = linesToParagraphs(nonTableLines)
            val noiseIndices = RunningFooterFilter.pageNoiseIndices(
                rawParagraphs.map { PageTextLine(it.text, it.page) },
                footerLearnedTitles,
            )
            val filtered = rawParagraphs.filterIndexed { index, _ -> index !in noiseIndices }

            val blocks = mutableListOf<DisplayBlock>()
            if (tableRegion != null) {
                val cropped = runCatching {
                    val renderer = PDFRenderer(document)
                    val fullPage = renderer.renderImageWithDPI(pageNo - 1, TABLE_PAGE_RENDER_DPI)
                    val crop = tableCropRect(tableRegion, pageHeight, TABLE_PAGE_RENDER_DPI, fullPage.width, fullPage.height)
                    Bitmap.createBitmap(fullPage, crop.left, crop.top, crop.width(), crop.height())
                }.getOrNull()
                val regionTopYDirAdj = pageHeight - tableRegion.maxY
                val afterIndex = ImagePlacement.afterParagraphIndexForRegion(
                    filtered.map { it.page },
                    filtered.map { it.topY },
                    pageNo,
                    regionTopYDirAdj,
                )
                if (afterIndex == -1) cropped?.let { blocks.add(DisplayBlock.Image(it)) }
                filtered.forEachIndexed { index, paragraph ->
                    blocks.add(DisplayBlock.Text(paragraph.text))
                    if (index == afterIndex) cropped?.let { blocks.add(DisplayBlock.Image(it)) }
                }
            } else {
                filtered.forEach { blocks.add(DisplayBlock.Text(it.text)) }
                if (hasImages) {
                    // 图片插在这一页最后一个段落之后——跟 extractContent/旧 Session 的
                    // "同页图片统一插在该页最后一个段落之后（按页归类）"是同一条约定，
                    // 单页范围内 ImagePlacement.afterParagraphIndex 天然只会算出
                    // "最后一个段落之后"这一个结果，不需要真的调用它。
                    val images = runCatching {
                        val engine = PageContentStreamEngine(page, decodeImages = true)
                        engine.processPage(page)
                        ImageStripStitcher.stitchIfTiled(engine.images)
                    }.getOrDefault(emptyList())
                    images.forEach { blocks.add(DisplayBlock.Image(it)) }
                }
            }
            return PageContent(blocks)
        }

        override fun close() {
            document.close()
        }

        companion object {
            fun open(context: Context, file: File): Session {
                PDFBoxResourceLoader.init(context.applicationContext ?: context)
                return Session(PDDocument.load(file))
            }
        }
    }
}

/**
 * 一张从 PDF 里抽取出来的图片，配合"降级为独立浮动展示"的产品目标（见 [PdfTextExtractor]
 * 类注释"图片抽取"一节）。
 *
 * @param bitmap 已经转换好、可以直接喂给 `ImageView` 显示的位图。
 * @param afterParagraphIndex 应该插入在 [PdfContent.paragraphs] 的哪个下标之后
 *   （0-based）；`-1` 表示插在所有段落之前。具体计算逻辑见 [ImagePlacement]。
 */
data class ExtractedImage(val bitmap: Bitmap, val afterParagraphIndex: Int)

/**
 * 一条大纲（目录/书签）项，来自 PDF 自带的 `PDDocumentOutline` 结构——见
 * [PdfTextExtractor] 类注释"大纲/目录抽取"一节。
 *
 * @param title 目录项标题。
 * @param pageNumber 跳转目标页码（1-based，和 [PdfContent.paragraphPages] 用同一套
 *   编号），已经解析、保证能在这份文档里定位到一个真实存在的页。
 * @param depth 嵌套深度，最外层是 0，子项是 1，子项的子项是 2，以此类推——不限制
 *   层数，够用即可（见任务描述"至少要能区分一级/二级"）。
 * @param targetTopY 目录项在目标页内的垂直位置（距页面顶部多少 pt，跟
 *   [PdfContent.paragraphTopY] 同一套坐标系），解析不出来时是 `null`——见
 *   [PdfTextExtractor.targetTopYOrNull]。`null` 时 [app.pdfreader.ui.OutlineNavigation]
 *   退化成"只按页跳转"，跳到目标页第一个段落，不是错误。
 */
data class OutlineEntry(val title: String, val pageNumber: Int, val depth: Int, val targetTopY: Float? = null)

/**
 * [PdfTextExtractor.extractContent] 的返回值：文字段落 + 图片，按"插在哪个段落之后"
 * 关联；[outline] 是大纲（目录）项列表，没有大纲时是空列表；[paragraphPages] 是每个
 * 段落所在的页码（与 [paragraphs] 一一对应，页码从 1 起）、[paragraphTopY] 是每个
 * 段落第一行距页面顶部多少 pt（同样与 [paragraphs] 一一对应），两者一起供
 * [app.pdfreader.ui.OutlineNavigation] 把"目录项指向第几页/页内哪个位置"换算成"该
 * 滚动到哪个展示块"——新字段都给了默认值 `emptyList()`，不破坏其余不关心大纲/页码的
 * 调用方（目前没有别处直接用位置参数构造 [PdfContent]，但保留默认值让以后新增调用方
 * 更安全）。
 */
data class PdfContent(
    val paragraphs: List<String>,
    val images: List<ExtractedImage>,
    val outline: List<OutlineEntry> = emptyList(),
    val paragraphPages: List<Int> = emptyList(),
    val paragraphTopY: List<Float> = emptyList(),
)
