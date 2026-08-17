package app.pdfreader.reflow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LineWidthEstimator] 的纯 Kotlin 单元测试。
 *
 * 从 MainActivity.estimateLineWidthChars() 里抽出来的核心算法：给定"可用宽度像素"和
 * "单字符宽度像素"，算出 reflow() 要用的"每行字符数"。不依赖 Context / View / Paint，
 * 输入输出都是普通数字，可以在 JVM 上直接跑，不需要 Robolectric。
 *
 * 之所以要单独抽出来测：这是字号调节这个增量的核心逻辑——字号变了，contentText.paint
 * 量出来的单字宽度就变了，同样的屏幕宽度能容纳的字符数也要跟着变，MainActivity 需要在
 * 每次字号/边距变化后重新调用这个函数，再用新的结果重新跑一次 reflow()。
 */
class LineWidthEstimatorTest {

    @Test
    fun `字符更宽时同样屏幕宽度算出的每行字符数更少`() {
        val narrowCharResult = LineWidthEstimator.estimate(usableWidthPx = 1000, charWidthPx = 20f)
        val wideCharResult = LineWidthEstimator.estimate(usableWidthPx = 1000, charWidthPx = 40f)

        assertEquals(50, narrowCharResult)
        assertEquals(25, wideCharResult)
        assert(wideCharResult < narrowCharResult) { "字号变大（字符变宽）时每行字符数应该变少" }
    }

    @Test
    fun `整除不尽时向下取整（避免超宽）`() {
        // 1000 / 30 = 33.33...，向下取整为 33，不能是 34（34 会超宽）。
        val result = LineWidthEstimator.estimate(usableWidthPx = 1000, charWidthPx = 30f)
        assertEquals(33, result)
    }

    @Test
    fun `字符宽度为 0 或负数时返回默认值（避免除以0）`() {
        assertEquals(LineWidthEstimator.DEFAULT_LINE_WIDTH_CHARS, LineWidthEstimator.estimate(1000, 0f))
        assertEquals(LineWidthEstimator.DEFAULT_LINE_WIDTH_CHARS, LineWidthEstimator.estimate(1000, -5f))
    }

    @Test
    fun `屏幕极窄时不会低于最小行宽`() {
        val result = LineWidthEstimator.estimate(usableWidthPx = 5, charWidthPx = 50f)
        assertEquals(LineWidthEstimator.MIN_LINE_WIDTH_CHARS, result)
    }
}
