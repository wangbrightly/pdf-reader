package app.pdfreader.extract

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
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
 * [renderTablePageImages] 整页渲染那条路，走的是下面说的内嵌图片抽取路径。
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
 * （[renderTablePageImages]，用 [PDFRenderer.renderImageWithDPI]）不受这个 bug
 * 影响——那是页面级别的渲染 API，由 PdfBox-Android 自己的渲染引擎负责应用页面
 * 内所有 CTM（包括图片的），不是"抽取单个图片资源、自己拼位图"这条路径，两者用的
 * 是完全不同的代码，这个判断没有另外找 fixture 验证（renderImageWithDPI 是否正确
 * 处理 CTM 属于 PdfBox-Android 库自身职责范围，不是本项目代码，没有理由重新验证
 * 一个第三方库的核心渲染路径），只是读代码路径确认它和 [scanPages] 走的不是
 * 同一段逻辑。
 *
 * ## 表格检测（2026-08-18 增量）：疑似表格的整页降级为图片
 *
 * 提示词档案第 6 条要求"表格不重排，整体展示，支持双指缩放"——PDF 格式本身没有
 * "表格"这个结构化概念，"这块内容是不是表格"是个没有标准答案的启发式检测问题
 * （业界专门做这个的 Camelot/Tabula 都只能做到"启发式+带误判"，见任务描述）。这里
 * 不追求精确识别表格边界，采用两段降级：
 *
 * 1. **检测信号：矢量网格线，不是文字列对齐。** [scanPages] 对每一页跑一遍
 *    [PageContentStreamEngine]（继承 `PDFGraphicsStreamEngine`，能拿到页面 content
 *    stream 里 `re`/`m l S` 等图形操作符的线段坐标），把线段交给纯逻辑
 *    [TableGridDetector.looksLikeTable] 判断"这一页是不是有网格"。选网格线而不是
 *    "文字按列对齐"这个更简单的信号，是因为后者在多栏排版、目录页上误判率明显更高
 *    （一大段无关文字碰巧在几行里都能切出 3 列对齐点，这种巧合并不罕见）；网格线
 *    要求"多条横线和多条竖线互相交叉"，普通正文段落、多栏排版、目录页几乎不会画
 *    出这种矢量图形，误判率天然更低，符合"宁可漏检、不可错杀"的保守策略（见
 *    [TableGridDetector] 类注释的完整阈值设计理由）。用真实 fixture
 *    （`sample-with-table.pdf`）反编译验证过：Chromium 打印 `<table border>` 时，
 *    表格边框是画成"细长填充矩形"而不是描边直线，[PageContentStreamEngine] 对
 *    `appendRectangle`/`strokePath`/`fillPath` 都做了处理，两种画法都能识别。
 *
 * 2. **降级策略：一旦一页疑似有表格，整页都不参与 reflow，改成渲染成一张 Bitmap。**
 *    检测"表格在页面内的精确边界"（哪几行哪几列真正属于表格、表格前后哪些文字不
 *    属于表格）本身复杂度和不确定性很高，与"够用的启发式"这个目标不成比例。所以
 *    一旦命中疑似表格信号，直接把这一整页用 [PDFRenderer.renderImageWithDPI]
 *    （PdfBox-Android 提供的整页渲染 API，和上游 Apache PDFBox 的 `PDFRenderer`
 *    同源）渲染成一张 [Bitmap]，这一页原本会抽取出的文字段落全部跳过——**代价**：
 *    如果这一页里表格和大段正文混排，正文也会跟着变成图片、丢失"重排+调字号"的
 *    能力，这是把"检测精度"换成"实现简单性和复用度"的妥协（复用现有
 *    [ExtractedImage]/`DisplayBlock.Image`/双指缩放机制，不需要发明新的展示类型）。
 *    渲染失败（内存不足、极端复杂页面等）时不强行让整份文档抽取失败，而是让这一页
 *    退回正常的文字抽取路径——见 [renderTablePageImages] 里的 `runCatching`，这样
 *    "表格检测/渲染出问题"最坏情况下只是退化成"这页表格没能整页降级、还是按普通
 *    文字重排"，不会让用户连文字都看不到。
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
 * [RunningFooterFilter]（纯逻辑，独立测试）识别、过滤掉——具体识别规则和"为什么
 * 故意不处理标题行"见该类 KDoc。过滤发生在 [linesToParagraphs] 之后、其余所有
 * 依赖段落下标的计算（图片插入位置、目录页内定位）之前，这样下标体系从一开始就是
 * "过滤后的段落列表"，不需要在后面的每一处计算里再去处理"要跳过被删掉的段落"。
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
            val pageScans = scanPages(document)
            val t1 = System.currentTimeMillis()
            val candidatePages = pageScans.filterValues { TableGridDetector.looksLikeTable(it.segments) }.keys
            val tablePageImages = renderTablePageImages(document, candidatePages)
            val renderedTablePages = tablePageImages.keys
            val t2 = System.currentTimeMillis()

            val stripper = LineCollectingStripper()
            stripper.getText(document)
            val t3 = System.currentTimeMillis()
            val nonTableLines = stripper.lines.filterNot { it.page in renderedTablePages }
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

            val inlineImages = buildInlineImages(pageScans, paragraphPages, excludePages = renderedTablePages)
            val t4 = System.currentTimeMillis()
            val tableImages = renderedTablePages.map { pageNo ->
                ExtractedImage(
                    bitmap = tablePageImages.getValue(pageNo),
                    afterParagraphIndex = ImagePlacement.afterParagraphIndex(paragraphPages, pageNo),
                )
            }
            // 大纲抽取失败（文档没有大纲、大纲结构异常等）不能让整份文档的抽取失败，
            // 见类注释"大纲/目录抽取"一节——outer runCatching 是最后一道保险，内部
            // collectOutlineEntries 对每个目录项也单独兜底。
            val outline = runCatching { extractOutline(document) }.getOrDefault(emptyList())
            android.util.Log.d(
                "PdfReaderDebug",
                "页数=${document.numberOfPages} 疑似表格页=${candidatePages.size} " +
                    "扫描页面(表格检测+图片抽取)=${t1 - t0}ms 渲染表格页=${t2 - t1}ms " +
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
    private data class PageScan(val segments: List<LineSegment>, val images: List<Bitmap>)

    /**
     * 对文档每一页跑一遍 [PageContentStreamEngine]，一次遍历同时拿到表格检测用的矢量
     * 线段和图片抽取用的 [Bitmap] 列表——见类注释"表格检测/图片抽取合并成一次遍历"
     * 一节（2026-08-19 性能优化）。单页扫描出异常（content stream 解析问题等）只让
     * 那一页退化成"没有线段、没有图片"（不判定为表格、也不产出内嵌图片），不让整份
     * 文档的抽取失败——延续原来两个引擎各自的降级精神，合并成一次遍历后用同一个
     * `runCatching` 覆盖两种情形。
     */
    private fun scanPages(document: PDDocument): Map<Int, PageScan> {
        val result = LinkedHashMap<Int, PageScan>(document.numberOfPages)
        for (pageIndex in 0 until document.numberOfPages) {
            val page = document.getPage(pageIndex)
            val scan = runCatching {
                val engine = PageContentStreamEngine(page)
                engine.processPage(page)
                PageScan(engine.segments, engine.images)
            }.getOrDefault(PageScan(emptyList(), emptyList()))
            result[pageIndex + 1] = scan
        }
        return result
    }

    /**
     * 把 [candidatePages] 里每一页整页渲染成 [Bitmap]（[TABLE_PAGE_RENDER_DPI]）。
     * 只有渲染成功的页码才会出现在返回值里——渲染失败的页码不进返回值，调用方
     * ([extractContent]) 就不会把那一页的文字行排除掉，等价于"这一页没有被判定为
     * 表格"，见类注释"表格检测"一节的降级说明。
     */
    private fun renderTablePageImages(document: PDDocument, candidatePages: Set<Int>): Map<Int, Bitmap> {
        if (candidatePages.isEmpty()) return emptyMap()
        val renderer = PDFRenderer(document)
        val result = mutableMapOf<Int, Bitmap>()
        for (pageNo in candidatePages) {
            val bitmap = runCatching {
                renderer.renderImageWithDPI(pageNo - 1, TABLE_PAGE_RENDER_DPI)
            }.getOrNull() ?: continue
            result[pageNo] = bitmap
        }
        return result
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
     * [excludePages] 是已经整页渲染成图片的表格页（见类注释"表格检测"一节）——这些
     * 页面的内嵌图片已经包含在整页渲染结果里了，不需要再单独展示一遍。
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
     */
    private class PageContentStreamEngine(page: PDPage) : PDFGraphicsStreamEngine(page) {
        val segments = mutableListOf<LineSegment>()
        val images = mutableListOf<Bitmap>()
        private val pendingSegments = mutableListOf<LineSegment>()
        private var currentX = 0f
        private var currentY = 0f
        private var subpathStartX = 0f
        private var subpathStartY = 0f

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
            // `re` 操作符：矩形四条边直接进 pendingSegments，等对应的 stroke/fill
            // 操作符提交（表格边框在 Chromium 输出里常见的画法就是细长填充矩形，
            // 见 TableGridDetector 类注释）。
            pendingSegments.add(LineSegment(p0.x, p0.y, p1.x, p1.y))
            pendingSegments.add(LineSegment(p1.x, p1.y, p2.x, p2.y))
            pendingSegments.add(LineSegment(p2.x, p2.y, p3.x, p3.y))
            pendingSegments.add(LineSegment(p3.x, p3.y, p0.x, p0.y))
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
            pendingSegments.add(LineSegment(currentX, currentY, x, y))
            currentX = x
            currentY = y
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            // 只推进"当前点"，不生成线段——见类 KDoc。
            currentX = x3
            currentY = y3
        }

        override fun closePath() {
            pendingSegments.add(LineSegment(currentX, currentY, subpathStartX, subpathStartY))
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
            val bitmap = runCatching { pdImage.image }.getOrNull() ?: return
            val ctm = graphicsState.currentTransformationMatrix
            images.add(applyCtmOrientation(bitmap, ctm))
        }

        // 表格网格检测不关心裁剪区域、阴影填充，当无操作处理。
        override fun clip(windingRule: Path.FillType) = Unit
        override fun shadingFill(shadingName: com.tom_roush.pdfbox.cos.COSName) = Unit
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
