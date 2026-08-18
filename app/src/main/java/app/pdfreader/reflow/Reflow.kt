package app.pdfreader.reflow

/**
 * 纯 Kotlin 重排算法：把一组"阅读顺序正确的段落"按给定的行宽（用字符宽度单位模拟
 * 屏幕宽度）重新分行。不依赖 Context / 任何 Android 测量 API，可以在 JVM
 * 单元测试里直接跑，不需要 Android 模拟器。
 *
 * 返回值里，段落之间插入一个空字符串（""）作为分隔行，用来保留段落边界；
 * 段落内部按 [maxLineWidth] 贪心换行。
 *
 * 换行时区分两类"词"：
 * - 非中日韩字符组成的连续片段（比如英文单词）当作不可拆分的整体，
 *   只在空白处断行——这是英文按空格分词换行的常规做法。
 * - 中日韩统一表意文字等 CJK 字符，因为原文本身没有空格分词，
 *   每个字符都当作独立的换行点，直接按字符数断行。
 *
 * 如果单个不可拆分片段（比如超长英文单词/URL）本身就超过 [maxLineWidth]，
 * 会强制按宽度截断，避免那一行宽度失控。
 *
 * ## 宽度加权（2026-08-18 真机实测修复）
 *
 * [maxLineWidth] 的单位是"一个中文全角字符的宽度"。旧版把所有字符（不管中英文）都
 * 算成占 1 个单位，真机实测发现纯英文 PDF 因此每行只用了不到一半屏幕宽度就换行——
 * 拉丁字母/数字/符号的实际显示宽度大约只有中文全角字符的一半，用中文宽度给英文
 * 算预算，预算会被过度保守地砍半。改成非 CJK 字符只占 [nonCjkWidthRatio]（默认
 * 0.55）个单位，CJK 字符仍占 1 个单位——同一份宽度预算现在能装下的英文字符数接近
 * 之前的两倍，同时保持"宁可提前换行、不会超宽"这条一直以来的保守原则不变（0.55 是
 * 一个稍微留有余量的估计，不是精确到字体度量的值，因为 [reflow] 本身设计成不依赖
 * 任何具体字体，调用方如果能测出更准的比例可以自己传进来）。
 */
fun reflow(
    paragraphs: List<String>,
    maxLineWidth: Int,
    nonCjkWidthRatio: Float = DEFAULT_NON_CJK_WIDTH_RATIO,
): List<String> {
    require(maxLineWidth > 0) { "maxLineWidth 必须大于 0" }
    require(nonCjkWidthRatio > 0f) { "nonCjkWidthRatio 必须大于 0" }

    val result = mutableListOf<String>()
    paragraphs.forEachIndexed { index, paragraph ->
        if (index > 0) {
            result.add("")
        }
        result.addAll(reflowParagraph(paragraph, maxLineWidth, nonCjkWidthRatio))
    }
    return result
}

/** 非 CJK 字符相对一个 CJK 全角字符的宽度权重，见本文件顶部"宽度加权"一节。 */
const val DEFAULT_NON_CJK_WIDTH_RATIO = 0.55f

private fun charWidth(ch: Char, nonCjkWidthRatio: Float): Float =
    if (isCjk(ch)) 1f else nonCjkWidthRatio

private fun widthOf(text: String, nonCjkWidthRatio: Float): Float {
    var sum = 0f
    for (ch in text) sum += charWidth(ch, nonCjkWidthRatio)
    return sum
}

private fun reflowParagraph(paragraph: String, maxLineWidth: Int, nonCjkWidthRatio: Float): List<String> {
    if (paragraph.isEmpty()) {
        return listOf("")
    }

    val tokens = tokenize(paragraph)
    val lines = mutableListOf<String>()
    val currentLine = StringBuilder()
    var currentWidth = 0f

    fun flushLine() {
        lines.add(currentLine.toString().trimEnd())
        currentLine.clear()
        currentWidth = 0f
    }

    for (token in tokens) {
        val tokenWidth = widthOf(token, nonCjkWidthRatio)

        if (token.isNotEmpty() && token.isBlank()) {
            // 空白 token：只有当前行非空时才可能保留（不让新行以空白开头）。
            if (currentLine.isEmpty()) continue
            if (currentWidth + tokenWidth <= maxLineWidth) {
                currentLine.append(token)
                currentWidth += tokenWidth
            } else {
                flushLine()
            }
            continue
        }

        if (tokenWidth > maxLineWidth) {
            // 单个片段本身超宽（超长英文单词/URL），强制按宽度截断：逐字符累加，
            // 累到接近预算上限就断开，避免那一行宽度失控。
            //
            // 踩过的坑：第一版只在"currentWidth >= maxLineWidth"（严格到达上限）时才
            // 换行——但一段截完往往刚好卡在预算边缘以内（比如 9.9/10），离满还差
            // 一点点，循环没有触发换行就直接往同一行硬塞下一段的第一个字符（"至少取
            // 一个字符"那条兜底规则），导致这一行实际宽度冲破预算。改成每次要往
            // 当前行追加新的一截之前，先看看"当前行剩余空间"连一个字符都装不下的话
            // 就先换行，而不是等宽度严格打满才换。
            var remaining = token
            while (remaining.isNotEmpty()) {
                if (currentLine.isNotEmpty() &&
                    currentWidth + charWidth(remaining.first(), nonCjkWidthRatio) > maxLineWidth
                ) {
                    flushLine()
                }
                val spaceLeft = maxLineWidth - currentWidth
                var takeCount = 0
                var accWidth = 0f
                for (ch in remaining) {
                    val w = charWidth(ch, nonCjkWidthRatio)
                    if (takeCount > 0 && accWidth + w > spaceLeft) break
                    accWidth += w
                    takeCount++
                }
                // 至少取一个字符，避免预算小到连一个字符都装不下时死循环
                // （只有全新空行才会走到这个兜底，正常情况下上面已经先换过行了）。
                takeCount = takeCount.coerceAtLeast(1).coerceAtMost(remaining.length)
                val take = remaining.substring(0, takeCount)
                currentLine.append(take)
                currentWidth += widthOf(take, nonCjkWidthRatio)
                remaining = remaining.substring(takeCount)
            }
            continue
        }

        if (currentWidth + tokenWidth > maxLineWidth) {
            flushLine()
        }
        currentLine.append(token)
        currentWidth += tokenWidth
    }

    if (currentLine.isNotEmpty() || lines.isEmpty()) {
        flushLine()
    }

    return lines
}

/** 把段落切成"可断行的最小单位"：CJK 字符逐字独立、空白独立、其余连续片段合并。 */
private fun tokenize(text: String): List<String> {
    val tokens = mutableListOf<String>()
    val buffer = StringBuilder()
    var mode = -1 // 0 = 空白，2 = 其他连续片段（CJK 不进入 buffer，逐字符直接输出）

    fun flushBuffer() {
        if (buffer.isNotEmpty()) {
            tokens.add(buffer.toString())
            buffer.clear()
        }
    }

    for (ch in text) {
        when {
            isCjk(ch) -> {
                flushBuffer()
                tokens.add(ch.toString())
                mode = -1
            }
            ch.isWhitespace() -> {
                if (mode != 0) {
                    flushBuffer()
                    mode = 0
                }
                buffer.append(ch)
            }
            else -> {
                if (mode != 2) {
                    flushBuffer()
                    mode = 2
                }
                buffer.append(ch)
            }
        }
    }
    flushBuffer()
    return tokens
}

private fun isCjk(ch: Char): Boolean {
    val code = ch.code
    return code in 0x4E00..0x9FFF ||   // CJK 统一表意文字
        code in 0x3400..0x4DBF ||       // 扩展 A
        code in 0xF900..0xFAFF ||       // 兼容表意文字
        code in 0x3000..0x303F ||       // CJK 标点符号
        code in 0xFF00..0xFFEF          // 全角字符/半角片假名区
}
