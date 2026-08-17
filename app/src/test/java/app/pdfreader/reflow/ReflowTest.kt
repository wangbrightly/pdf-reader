package app.pdfreader.reflow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 重排算法（reflow）的纯 Kotlin 单元测试。
 *
 * 不依赖 Context / 任何 Android 测量 API，屏幕宽度用“字符数”模拟，
 * 可以在 JVM 上直接跑（./gradlew test），不需要 Android 模拟器。
 */
class ReflowTest {

    @Test
    fun `单段短文本不超宽时不换行`() {
        val result = reflow(listOf("短文本"), maxLineWidth = 20)
        assertEquals(listOf("短文本"), result)
    }

    @Test
    fun `英文长文本按单词边界贪心换行`() {
        val text = "the quick brown fox jumps over the lazy dog"
        val result = reflow(listOf(text), maxLineWidth = 10)

        val expected = listOf(
            "the quick",
            "brown fox",
            "jumps over",
            "the lazy",
            "dog",
        )
        assertEquals(expected, result)

        // 每一行都不能超过 maxLineWidth。
        result.forEach { line -> assert(line.length <= 10) { "行超宽: \"$line\"" } }
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
