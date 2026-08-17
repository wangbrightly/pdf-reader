package app.pdfreader.reflow

/**
 * 纯 Kotlin 重排算法：把一组"阅读顺序正确的段落"按给定的行宽（用字符数模拟
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
 * 会强制按字符数截断，避免那一行宽度失控。
 */
fun reflow(paragraphs: List<String>, maxLineWidth: Int): List<String> {
    require(maxLineWidth > 0) { "maxLineWidth 必须大于 0" }

    val result = mutableListOf<String>()
    paragraphs.forEachIndexed { index, paragraph ->
        if (index > 0) {
            result.add("")
        }
        result.addAll(reflowParagraph(paragraph, maxLineWidth))
    }
    return result
}

private fun reflowParagraph(paragraph: String, maxLineWidth: Int): List<String> {
    if (paragraph.isEmpty()) {
        return listOf("")
    }

    val tokens = tokenize(paragraph)
    val lines = mutableListOf<String>()
    val currentLine = StringBuilder()

    fun flushLine() {
        lines.add(currentLine.toString().trimEnd())
        currentLine.clear()
    }

    for (token in tokens) {
        if (token.isNotEmpty() && token.isBlank()) {
            // 空白 token：只有当前行非空时才可能保留（不让新行以空白开头）。
            if (currentLine.isEmpty()) continue
            if (currentLine.length + token.length <= maxLineWidth) {
                currentLine.append(token)
            } else {
                flushLine()
            }
            continue
        }

        if (token.length > maxLineWidth) {
            // 单个片段本身超宽（超长英文单词/URL），强制按字符数截断。
            var remaining = token
            while (remaining.isNotEmpty()) {
                if (currentLine.length >= maxLineWidth) {
                    flushLine()
                }
                val spaceLeft = maxLineWidth - currentLine.length
                val take = minOf(spaceLeft, remaining.length)
                currentLine.append(remaining.substring(0, take))
                remaining = remaining.substring(take)
            }
            continue
        }

        if (currentLine.length + token.length > maxLineWidth) {
            flushLine()
        }
        currentLine.append(token)
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
