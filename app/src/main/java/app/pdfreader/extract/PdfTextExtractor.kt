package app.pdfreader.extract

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.text.Normalizer

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
 * 见 [extractImages] 里的 `runCatching`。
 *
 * ## 表格检测（2026-08-18 增量）：疑似表格的整页降级为图片
 *
 * 提示词档案第 6 条要求"表格不重排，整体展示，支持双指缩放"——PDF 格式本身没有
 * "表格"这个结构化概念，"这块内容是不是表格"是个没有标准答案的启发式检测问题
 * （业界专门做这个的 Camelot/Tabula 都只能做到"启发式+带误判"，见任务描述）。这里
 * 不追求精确识别表格边界，采用两段降级：
 *
 * 1. **检测信号：矢量网格线，不是文字列对齐。** [detectTablePages] 对每一页跑一遍
 *    [TableGridStreamEngine]（继承 `PDFGraphicsStreamEngine`，能拿到页面 content
 *    stream 里 `re`/`m l S` 等图形操作符的线段坐标），把线段交给纯逻辑
 *    [TableGridDetector.looksLikeTable] 判断"这一页是不是有网格"。选网格线而不是
 *    "文字按列对齐"这个更简单的信号，是因为后者在多栏排版、目录页上误判率明显更高
 *    （一大段无关文字碰巧在几行里都能切出 3 列对齐点，这种巧合并不罕见）；网格线
 *    要求"多条横线和多条竖线互相交叉"，普通正文段落、多栏排版、目录页几乎不会画
 *    出这种矢量图形，误判率天然更低，符合"宁可漏检、不可错杀"的保守策略（见
 *    [TableGridDetector] 类注释的完整阈值设计理由）。用真实 fixture
 *    （`sample-with-table.pdf`）反编译验证过：Chromium 打印 `<table border>` 时，
 *    表格边框是画成"细长填充矩形"而不是描边直线，[TableGridStreamEngine] 对
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
     * "图片抽取"一节。图片抽取失败不影响这次调用整体成功，见 [extractImages]。
     */
    fun extractContent(context: Context, file: File): PdfContent {
        PDFBoxResourceLoader.init(context.applicationContext ?: context)
        PDDocument.load(file).use { document ->
            val t0 = System.currentTimeMillis()
            // 先渲染疑似表格页（成功的才计入"跳过文字抽取"名单，见类注释"表格检测"
            // 一节——渲染失败时宁可让这页退回正常文字抽取，也不让内容整页消失）。
            val candidatePages = detectTablePages(document)
            val t1 = System.currentTimeMillis()
            val tablePageImages = renderTablePageImages(document, candidatePages)
            val renderedTablePages = tablePageImages.keys
            val t2 = System.currentTimeMillis()

            val stripper = LineCollectingStripper()
            stripper.getText(document)
            val t3 = System.currentTimeMillis()
            val nonTableLines = stripper.lines.filterNot { it.page in renderedTablePages }
            val paragraphs = linesToParagraphs(nonTableLines)
            val paragraphPages = paragraphs.map { it.page }

            val inlineImages = extractImages(document, paragraphs, excludePages = renderedTablePages)
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
                    "检测表格=${t1 - t0}ms 渲染表格页=${t2 - t1}ms 抽取文字=${t3 - t2}ms " +
                    "抽取内嵌图片=${t4 - t3}ms 总计=${t4 - t0}ms",
            )
            return PdfContent(paragraphs.map { it.text }, inlineImages + tableImages, outline, paragraphPages)
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
     * 继续递归它的子项——单个目录项的问题不该连累其余目录项，是和 [extractImages]
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
                        out.add(OutlineEntry(title = item.title.orEmpty(), pageNumber = pageIndex + 1, depth = depth))
                    }
                }
            }
            runCatching { collectOutlineEntries(document, item.children(), depth + 1, out) }
        }
    }

    /**
     * 对文档每一页跑一遍 [TableGridStreamEngine]，收集"疑似表格"的页码（1-based）。
     * 单页检测出异常（个别页面 content stream 有解析问题）只跳过那一页的判断，不让
     * 整份文档的抽取失败——和 [extractImages] 里"单张图片失败不连累其它"是同一种
     * 降级精神。
     */
    private fun detectTablePages(document: PDDocument): Set<Int> {
        val tablePages = mutableSetOf<Int>()
        for (pageIndex in 0 until document.numberOfPages) {
            val page = document.getPage(pageIndex)
            val looksLikeTable = runCatching {
                val engine = TableGridStreamEngine(page)
                engine.processPage(page)
                TableGridDetector.looksLikeTable(engine.segments)
            }.getOrDefault(false)
            if (looksLikeTable) tablePages.add(pageIndex + 1)
        }
        return tablePages
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
                val rotation = document.getPage(pageNo - 1).rotation
                android.util.Log.d("PdfReaderDebug", "表格页 $pageNo 的 page.rotation=$rotation")
                renderer.renderImageWithDPI(pageNo - 1, TABLE_PAGE_RENDER_DPI)
            }.getOrNull() ?: continue
            result[pageNo] = bitmap
        }
        return result
    }

    /** 内部用：段落文字 + 这个段落所在的页码（页码从 1 起，用于图片按页归类）。 */
    private data class Paragraph(val text: String, val page: Int)

    private fun linesToParagraphs(lines: List<Line>): List<Paragraph> {
        if (lines.isEmpty()) return emptyList()
        if (lines.size == 1) return listOf(Paragraph(lines[0].text, lines[0].page))

        val gaps = (1 until lines.size).map { lines[it].y - lines[it - 1].y }
        val typicalGap = gaps.sorted()[gaps.size / 2]
        val paragraphThreshold = typicalGap * 1.5f

        val texts = mutableListOf<StringBuilder>()
        val pages = mutableListOf<Int>()
        texts.add(StringBuilder(lines[0].text))
        pages.add(lines[0].page)

        for (i in 1 until lines.size) {
            val gap = lines[i].y - lines[i - 1].y
            val pageChanged = lines[i].page != lines[i - 1].page
            // 跨页强制切段落：y 坐标每翻一页就从页顶重新开始，纯按 gap 判断在跨页处
            // 没有意义，见类注释"图片抽取"一节。
            if (pageChanged || gap > paragraphThreshold) {
                texts.add(StringBuilder(lines[i].text))
                pages.add(lines[i].page)
            } else {
                appendLine(texts.last(), lines[i].text)
            }
        }
        return texts.indices.map { Paragraph(normalizeCjkSpacing(texts[it].toString()), pages[it]) }
    }

    /**
     * 遍历每一页的 `PDResources`，把里面的 `PDImageXObject` 转成 [Bitmap]，配合
     * [ImagePlacement] 算出每张图该插在哪个段落之后。单张图片转换失败（`getImage()`
     * 抛异常）只跳过那一张，不让整个文档抽取失败——这是"降级"精神的延续：宁可漏掉
     * 一张图，也不能因为一张坏图让用户连文字都看不到。
     *
     * [excludePages] 是已经整页渲染成图片的表格页（见类注释"表格检测"一节）——这些
     * 页面的内嵌图片已经包含在整页渲染结果里了，不需要再单独抽取一遍、重复显示。
     */
    private fun extractImages(
        document: PDDocument,
        paragraphs: List<Paragraph>,
        excludePages: Set<Int>,
    ): List<ExtractedImage> {
        val paragraphPages = paragraphs.map { it.page }
        val images = mutableListOf<ExtractedImage>()
        for (pageIndex in 0 until document.numberOfPages) {
            val pageNo = pageIndex + 1
            if (pageNo in excludePages) continue
            val page: PDPage = document.getPage(pageIndex)
            val afterIndex = ImagePlacement.afterParagraphIndex(paragraphPages, pageNo)
            val resources = page.resources ?: continue
            for (name in resources.xObjectNames) {
                if (!resources.isImageXObject(name)) continue
                val bitmap = runCatching {
                    (resources.getXObject(name) as PDImageXObject).image
                }.getOrNull() ?: continue
                images.add(ExtractedImage(bitmap, afterIndex))
            }
        }
        return images
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
     * PDFBox 图形流引擎适配层：把一页 content stream 里画路径用的操作符（`m`/`l`/`c`/
     * `re`/`S`/`f`/`B`……）转成一批 [LineSegment]，交给纯逻辑 [TableGridDetector] 判断。
     * 只关心"画了哪些直线段"，不关心颜色/线宽/是否真的可见（够用即可，见类注释
     * "表格检测"一节）。
     *
     * 只有在路径被真正"画出来"（[strokePath]/[fillPath]/[fillAndStrokePath]，对应
     * PDF 的 `S`/`f`/`B` 等操作符）时，累积在 [pendingSegments] 里的线段才会提交进
     * [segments]；纯粹用于裁剪、从未描边/填充的路径（[endPath]，对应 `n` 操作符）会
     * 被直接丢弃——这样"看不见的裁剪路径"不会污染网格判断。
     *
     * 曲线（[curveTo]）只把终点当作直线的端点纳入路径追踪（用于正确维护"当前点"），
     * 不生成线段——表格网格线是直线，不会是贝塞尔曲线，忽略曲线本身的走向不影响
     * 判断，也避免把任意曲线误当成网格线的一部分。
     */
    private class TableGridStreamEngine(page: PDPage) : PDFGraphicsStreamEngine(page) {
        val segments = mutableListOf<LineSegment>()
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

        // 表格网格检测不关心裁剪区域、图片、阴影填充，全部当无操作处理。
        override fun clip(windingRule: Path.FillType) = Unit
        override fun shadingFill(shadingName: com.tom_roush.pdfbox.cos.COSName) = Unit
        override fun drawImage(pdImage: PDImage) = Unit
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
 */
data class OutlineEntry(val title: String, val pageNumber: Int, val depth: Int)

/**
 * [PdfTextExtractor.extractContent] 的返回值：文字段落 + 图片，按"插在哪个段落之后"
 * 关联；[outline] 是大纲（目录）项列表，没有大纲时是空列表；[paragraphPages] 是每个
 * 段落所在的页码（与 [paragraphs] 一一对应，页码从 1 起），供
 * [app.pdfreader.ui.OutlineNavigation] 把"目录项指向第几页"换算成"该滚动到哪个
 * 展示块"——两个新字段都给了默认值 `emptyList()`，不破坏其余不关心大纲/页码的调用方
 * （目前没有别处直接用位置参数构造 [PdfContent]，但保留默认值让以后新增调用方更安全）。
 */
data class PdfContent(
    val paragraphs: List<String>,
    val images: List<ExtractedImage>,
    val outline: List<OutlineEntry> = emptyList(),
    val paragraphPages: List<Int> = emptyList(),
)
