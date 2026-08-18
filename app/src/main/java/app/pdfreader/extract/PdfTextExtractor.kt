package app.pdfreader.extract

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
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
 * 的 CJK 断行逻辑保持同一套认知模型。
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
 */
object PdfTextExtractor {

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
            val stripper = LineCollectingStripper()
            stripper.getText(document)
            val paragraphs = linesToParagraphs(stripper.lines)
            val images = extractImages(document, paragraphs)
            return PdfContent(paragraphs.map { it.text }, images)
        }
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
        return texts.indices.map { Paragraph(texts[it].toString(), pages[it]) }
    }

    /**
     * 遍历每一页的 `PDResources`，把里面的 `PDImageXObject` 转成 [Bitmap]，配合
     * [ImagePlacement] 算出每张图该插在哪个段落之后。单张图片转换失败（`getImage()`
     * 抛异常）只跳过那一张，不让整个文档抽取失败——这是"降级"精神的延续：宁可漏掉
     * 一张图，也不能因为一张坏图让用户连文字都看不到。
     */
    private fun extractImages(document: PDDocument, paragraphs: List<Paragraph>): List<ExtractedImage> {
        val paragraphPages = paragraphs.map { it.page }
        val images = mutableListOf<ExtractedImage>()
        for (pageIndex in 0 until document.numberOfPages) {
            val page: PDPage = document.getPage(pageIndex)
            val pageNo = pageIndex + 1
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

/** [PdfTextExtractor.extractContent] 的返回值：文字段落 + 图片，按"插在哪个段落之后"关联。 */
data class PdfContent(val paragraphs: List<String>, val images: List<ExtractedImage>)
