package app.pdfreader.extract

/**
 * 中文排版间距规范化（俗称"盘古之白"规则）：中文字符和相邻的英文字母/数字之间应该
 * 有且只有一个空格；中文字符和中文字符之间不应该有空格。
 *
 * 2026-08-18 用户真机实测反馈两件事：一是排版里空格太多，二是想要中文和数字/字母间
 * 自动加空格——两条其实是同一个问题的两面。追查发现根源是 [PdfTextExtractor] 抽取
 * 阶段的旧逻辑（"CJK 边界不加空格，其余情况都加空格"）太粗糙：专业排版的中文 PDF
 * 常用"两端对齐"让一行文字撑满宽度，PDFBox 的 `PDFTextStripper` 可能把这种被拉伸的
 * 字间距误判成词间空格，插进两个中文字符正当中；同时又没有对"中文紧贴数字/字母"
 * 的情况做处理。这个函数在段落文字最终成型后统一跑一遍，替代零散的猜测，规则明确、
 * 可单测、幂等（跑两遍结果一样）。
 *
 * 具体规则：
 * - 中文字符（含中文标点，沿用 [PdfTextExtractor] 既有的 CJK 判定范围）之间：不管
 *   原来有没有空格，一律不留空格。
 * - 中文字符与英文字母/数字直接相邻（不管哪边在前）：不管原来有没有空格，规范化成
 *   恰好一个空格。
 * - 其余情况（比如英文单词之间、中文和其他符号之间）：不凭空插入新空格，但如果原来
 *   有一段连续空白，收成一个——这条只解决"太多空格"，不改变原文原本就没有空格的地方。
 */
fun normalizeCjkSpacing(text: String): String {
    if (text.isEmpty()) return text

    // 第一步：丢掉所有空白字符，只留下非空白字符本身，但对每一个非空白字符记录
    // "它和前一个非空白字符之间，原文里是否隔着至少一个空白"——不关心具体有几个
    // 连续空格，只关心"有没有"，多个空格和一个空格在这一步等价。开头的空白因为
    // 前面没有非空白字符可以依附，天然被丢弃，等价于"去掉开头空白"。
    //
    // 踩过的坑：第一版按"空白分隔的词块（core）"分组，只在词块之间判断要不要插
    // 空格——那样处理不了"血压127"这种整段话里完全没有空白、但中间恰好有中文
    // 紧贴数字的情况（"压"和"1"之间根本没有空白可以分词块）。改成不分词块，
    // 直接看"每一个相邻的非空白字符对"，中文和数字/字母之间该不该有空格，跟原文
    // 有没有空白完全无关——这样才能处理"原文压根没打算留空格，但因为紧贴中文所以
    // 必须补一个"的情况。
    data class NonBlankChar(val ch: Char, val hadGapBefore: Boolean)
    val chars = mutableListOf<NonBlankChar>()
    var sawWhitespaceSinceLastChar = false
    for (ch in text) {
        if (ch.isWhitespace()) {
            sawWhitespaceSinceLastChar = true
        } else {
            chars.add(NonBlankChar(ch, if (chars.isEmpty()) false else sawWhitespaceSinceLastChar))
            sawWhitespaceSinceLastChar = false
        }
    }
    if (chars.isEmpty()) return ""

    // 第二步：相邻两个非空白字符之间，按类别决定要不要空格——中文之间不留、中文和
    // 数字/字母之间恰好留一个，其余情况按原文是否有间隙决定（不凭空插入，但把多个
    // 空格收成一个）。
    val result = StringBuilder()
    result.append(chars[0].ch)
    for (i in 1 until chars.size) {
        val prevChar = chars[i - 1].ch
        val nextChar = chars[i].ch
        val prevCjk = isCjkChar(prevChar)
        val nextCjk = isCjkChar(nextChar)
        val needsSpace = when {
            prevCjk && nextCjk -> false
            prevCjk && nextChar.isLetterOrDigit() -> true
            nextCjk && prevChar.isLetterOrDigit() -> true
            else -> chars[i].hadGapBefore
        }
        if (needsSpace) result.append(' ')
        result.append(nextChar)
    }
    return result.toString()
}

/** 与 [PdfTextExtractor] 里的 `isCjk` 判定范围保持一致（含中文标点/全角字符）。 */
private fun isCjkChar(ch: Char): Boolean {
    val code = ch.code
    return code in 0x4E00..0x9FFF ||
        code in 0x3400..0x4DBF ||
        code in 0xF900..0xFAFF ||
        code in 0x3000..0x303F ||
        code in 0xFF00..0xFFEF
}
