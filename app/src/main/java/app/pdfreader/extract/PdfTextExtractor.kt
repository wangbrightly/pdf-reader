package app.pdfreader.extract

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
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

    fun extractParagraphs(context: Context, file: File): List<String> {
        PDFBoxResourceLoader.init(context.applicationContext ?: context)
        PDDocument.load(file).use { document ->
            val stripper = LineCollectingStripper()
            stripper.getText(document)
            return linesToParagraphs(stripper.lines)
        }
    }

    private fun linesToParagraphs(lines: List<Line>): List<String> {
        if (lines.isEmpty()) return emptyList()
        if (lines.size == 1) return listOf(lines[0].text)

        val gaps = (1 until lines.size).map { lines[it].y - lines[it - 1].y }
        val typicalGap = gaps.sorted()[gaps.size / 2]
        val paragraphThreshold = typicalGap * 1.5f

        val paragraphs = mutableListOf<StringBuilder>()
        paragraphs.add(StringBuilder(lines[0].text))

        for (i in 1 until lines.size) {
            val gap = lines[i].y - lines[i - 1].y
            if (gap > paragraphThreshold) {
                paragraphs.add(StringBuilder(lines[i].text))
            } else {
                appendLine(paragraphs.last(), lines[i].text)
            }
        }
        return paragraphs.map { it.toString() }
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

    private data class Line(val text: String, val y: Float)

    private class LineCollectingStripper : PDFTextStripper() {
        val lines = mutableListOf<Line>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (text.isBlank()) return
            val y = textPositions.firstOrNull()?.yDirAdj ?: return
            lines.add(Line(fixRadicalVariants(text), y))
        }
    }
}
