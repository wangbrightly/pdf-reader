package app.pdfreader.settings

/**
 * 阅读设置：字号（sp）、行距倍数、内边距（dp）。三者都通过 SeekBar 连续调节，
 * 改动即时生效（见 MainActivity），并经 [ReaderSettingsPreferences] 持久化。
 *
 * 字号范围 12sp–32sp 是需求写死的（提示词原文）；行距倍数 1.0–2.0、边距 8dp–32dp
 * 是本增量按"够用、不离谱"给的合理区间，不是需求强制的数字。
 */
data class ReaderSettings(
    val fontSizeSp: Int = DEFAULT_FONT_SIZE_SP,
    val lineSpacingMultiplier: Float = DEFAULT_LINE_SPACING_MULTIPLIER,
    val paddingDp: Int = DEFAULT_PADDING_DP,
) {
    /**
     * 把可能越界的值收拢回合法区间。用于两个场景：
     * 1. 从 SharedPreferences 读出来的旧数据，如果以后调整了范围常量，旧值可能落在新区间外。
     * 2. SeekBar 理论上不会给出界外的 progress，但收拢一次不吃亏，防御性更强。
     */
    fun coerced(): ReaderSettings = ReaderSettings(
        fontSizeSp = fontSizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP),
        lineSpacingMultiplier = lineSpacingMultiplier.coerceIn(MIN_LINE_SPACING_MULTIPLIER, MAX_LINE_SPACING_MULTIPLIER),
        paddingDp = paddingDp.coerceIn(MIN_PADDING_DP, MAX_PADDING_DP),
    )

    companion object {
        const val MIN_FONT_SIZE_SP = 12
        const val MAX_FONT_SIZE_SP = 32
        const val DEFAULT_FONT_SIZE_SP = 16

        const val MIN_LINE_SPACING_MULTIPLIER = 1.0f
        const val MAX_LINE_SPACING_MULTIPLIER = 2.0f
        const val DEFAULT_LINE_SPACING_MULTIPLIER = 1.2f

        const val MIN_PADDING_DP = 8
        const val MAX_PADDING_DP = 32
        const val DEFAULT_PADDING_DP = 16
    }
}
