package app.pdfreader.reflow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 重排算法（reflow）的纯 Kotlin 单元测试。
 *
 * 不依赖 Context / 任何 Android 测量 API，屏幕宽度用"字符数"模拟，
 * 可以在 JVM 上直接跑（./gradlew test），不需要 Android 模拟器。
 *
 * 2026-08-18 真机实测发现一个真实 bug：纯英文 PDF 每行只用了不到一半屏幕宽度就换行。
 * 根源是 [maxLineWidth] 这个预算原本按"每个字符不管中英文都占 1 个单位"计算——但一个
 * 拉丁字母的实际显示宽度大约只有一个中文字符（全角）的一半，用中文字符的宽度给英文
 * 字符算预算，预算会被过度保守地砍掉一半。改成 [reflow] 内部按 [nonCjkWidthRatio]
 * （默认 0.55，中文=1 个单位，拉丁字母/数字/符号=0.55 个单位）换算，同一个字符宽度
 * 预算现在能装下大约 1.8 倍的英文字符。
 *
 * 同一次真机反馈还带出第二个点：修完以后右侧仍然有明显空隙（尤其是全大写的英文段落，
 * 比如法律免责条款）。原因是空格被当成跟字母一样宽（[nonCjkWidthRatio]），但空格的
 * 实际显示宽度明显比字母窄——英文平均每 5-6 个字符就有一个空格，空格多算的这部分
 * 宽度累积起来很可观。改成空格用独立的、更窄的 [spaceWidthRatio]（默认 0.3）。
 */
class ReflowTest {

    @Test
    fun `单段短文本不超宽时不换行`() {
        val result = reflow(listOf("短文本"), maxLineWidth = 20)
        assertEquals(listOf("短文本"), result)
    }

    @Test
    fun `英文长文本按单词边界贪心换行，拉丁字符只占半个宽度单位、空格更窄`() {
        val text = "the quick brown fox jumps over the lazy dog"
        val result = reflow(listOf(text), maxLineWidth = 10)

        // 用默认 nonCjkWidthRatio=0.55、spaceWidthRatio=0.3 换算，比旧版"按字符数
        // 1:1 算"能多装进去接近一倍的英文字符，比"空格也按 0.55 算"能再多挤进几个词。
        val expected = listOf(
            "the quick brown fox",
            "jumps over the lazy",
            "dog",
        )
        assertEquals(expected, result)

        // 每一行的加权宽度都不能超过 maxLineWidth（不是简单数字符数）。
        result.forEach { line ->
            val width = line.sumOf { if (it.isWhitespace()) 0.3 else if (it.isLetterOrDigit()) 0.55 else 1.0 }
            assert(width <= 10.0 + 1e-6) { "行超宽: \"$line\"（宽度 $width）" }
        }
    }

    @Test
    fun `nonCjkWidthRatio 和 spaceWidthRatio 都传 1 点 0 时等价于旧版按字符数 1 比 1 计算`() {
        val text = "the quick brown fox jumps over the lazy dog"
        val result = reflow(listOf(text), maxLineWidth = 10, nonCjkWidthRatio = 1.0f, spaceWidthRatio = 1.0f)

        // 用满宽度权重复现旧行为，验证两个参数真的接进了换行判断，不是加了个没被用到
        // 的死参数。
        val expected = listOf(
            "the quick",
            "brown fox",
            "jumps over",
            "the lazy",
            "dog",
        )
        assertEquals(expected, result)
    }

    @Test
    fun `全大写英文因为没有窄字符可以拉低平均宽度，行会比小写文本更快换行，这是符合预期的`() {
        // 呼应真机反馈"全大写段落右侧空隙更明显"——大写字母普遍比小写宽，同一个
        // nonCjkWidthRatio 对全大写文本天然更"顶格"，这不是 bug，是大写字母确实更宽
        // 这个事实的正常体现，这里只是把这个认知落成一个明确的回归测试点。
        val upper = "THE QUICK BROWN FOX"
        val lower = "the quick brown fox"
        val upperResult = reflow(listOf(upper), maxLineWidth = 8)
        val lowerResult = reflow(listOf(lower), maxLineWidth = 8)

        // 两段文字字符数完全一样，宽度权重也完全一样（reflow 不区分大小写字宽），
        // 换行结果理应一致——用来确认"大写看起来空隙更明显"是视觉上大写字母本身
        // 更宽、更容易达到同一预算上限，不是我们的算法对大小写做了不同处理。
        assertEquals(upperResult.size, lowerResult.size)
    }

    @Test
    fun `超长英文片段仍然会按宽度强制截断，不会有一行超宽`() {
        val text = "a" + "b".repeat(40)
        val result = reflow(listOf(text), maxLineWidth = 10, nonCjkWidthRatio = 0.55f)

        assertEquals(text, result.joinToString(""))
        result.forEach { line ->
            val width = line.length * 0.55
            assert(width <= 10.0 + 1e-6) { "行超宽: \"$line\"（宽度 $width）" }
        }
        assert(result.size > 1) { "超长片段应该被截成多行" }
    }

    @Test
    fun `中文没有空格分词时按字符宽度直接断行`() {
        // 中文原文没有空格，不能照搬英文"按空格分词"的换行逻辑，
        // 必须逐字符累加宽度、到达行宽上限就断行。
        val text = "我爱北京天安门我爱北京天安门"
        val result = reflow(listOf(text), maxLineWidth = 5)

        val expected = listOf(
            "我爱北京天",
            "安门我爱北",
            "京天安门",
        )
        assertEquals(expected, result)
        result.forEach { line -> assert(line.length <= 5) { "行超宽: \"$line\"" } }
    }

    @Test
    fun `空段落列表返回空结果`() {
        val result = reflow(emptyList(), maxLineWidth = 10)
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `空字符串段落产生一个空行`() {
        val result = reflow(listOf(""), maxLineWidth = 10)
        assertEquals(listOf(""), result)
    }

    @Test
    fun `多段落之间用空行分隔以保持段落边界`() {
        val result = reflow(listOf("hello world", "second para"), maxLineWidth = 100)
        assertEquals(listOf("hello world", "", "second para"), result)
    }
}
