package app.pdfreader.reflow

/**
 * 从 MainActivity.estimateLineWidthChars() 抽出来的核心算法：给定"可用宽度像素"
 * （屏幕宽度减去左右内边距）和"单字符宽度像素"（用较宽的 CJK 字符测量，保守估计），
 * 算出 [reflow] 要用的"每行字符数"。
 *
 * 纯数字输入输出，不依赖 Context / View / Paint，MainActivity 只负责用 Android API
 * 测量出这两个数字再调用本函数——这是字号/边距调节这个增量的核心逻辑：字号变了，
 * 单字符宽度就变了；边距变了，可用宽度就变了；这两种情况都要重新调用本函数拿到新的
 * 行宽，再重新跑一次 [reflow]。
 */
object LineWidthEstimator {
    const val MIN_LINE_WIDTH_CHARS = 10
    const val DEFAULT_LINE_WIDTH_CHARS = 30

    fun estimate(usableWidthPx: Int, charWidthPx: Float): Int {
        if (charWidthPx <= 0f) return DEFAULT_LINE_WIDTH_CHARS
        return (usableWidthPx / charWidthPx).toInt().coerceAtLeast(MIN_LINE_WIDTH_CHARS)
    }
}
