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
 * 预算现在能装下大约 1.8 倍的英文字符。以下几个测试点覆盖了这条修复。
 */
class ReflowTest {

    @Test
    fun `单段短文本不超宽时不换行`() {
        val result = reflow(listOf("短文本"), maxLineWidth = 20)
        assertEquals(listOf("短文本"), result)
    }

    @Test
    fun `英文长文本按单词边界贪心换行，拉丁字符只占半个宽度单位`() {
        val text = "the quick brown fox jumps over the lazy dog"
        val result = reflow(listOf(text), maxLineWidth = 10)

        // 用默认 nonCjkWidthRatio=0.55 换算，10 个宽度单位≈18 个拉丁字符，
        // 比旧版"按字符数 1:1 算"能多装进去将近一倍的英文字符。
        val expected = listOf(
            "the quick brown",
            "fox jumps over the",
            "lazy dog",
        )
        assertEquals(expected, result)

        // 每一行的加权宽度都不能超过 maxLineWidth（不是简单数字符数）。
        result.forEach { line ->
            val width = line.sumOf { if (it.isWhitespace() || it.isLetterOrDigit()) 0.55 else 1.0 }
            assert(width <= 10.0) { "行超宽: \"$line\"（宽度 $width）" }
        }
    }

    @Test
    fun `nonCjkWidthRatio 传 1 点 0 时等价于旧版按字符数 1 比 1 计算`() {
        val text = "the quick brown fox jumps over the lazy dog"
        val result = reflow(listOf(text), maxLineWidth = 10, nonCjkWidthRatio = 1.0f)

        // 用满宽度权重复现旧行为，验证 nonCjkWidthRatio 参数真的接进了换行判断，
        // 不是加了个没被用到的死参数。
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
