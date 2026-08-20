package app.pdfreader.settings

import android.content.Context

/**
 * [ReaderSettings] 的持久化：存到 SharedPreferences。
 *
 * 数据量极小（三个数字），不引入 DataStore 这种量级的依赖——SharedPreferences 是这个
 * 场景下最简单、够用的方案（同步写小量 KV，不需要 DataStore 的协程/Flow/多进程安全）。
 *
 * 对应需求"字号设置要持久化……下次打开 App 时读出来用，不是每次都回到默认值"：
 * [load] 在读不到任何已存值时返回 [ReaderSettings] 的默认值；[save] 每次都完整覆盖存一份。
 */
object ReaderSettingsPreferences {
    private const val PREFS_NAME = "reader_settings"
    private const val KEY_FONT_SIZE_SP = "font_size_sp"
    private const val KEY_LINE_SPACING_MULTIPLIER = "line_spacing_multiplier"
    private const val KEY_PADDING_DP = "padding_dp"
    private const val KEY_BLOCK_SPACING_DP = "block_spacing_dp"

    fun load(context: Context): ReaderSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ReaderSettings(
            fontSizeSp = prefs.getInt(KEY_FONT_SIZE_SP, ReaderSettings.DEFAULT_FONT_SIZE_SP),
            lineSpacingMultiplier = prefs.getFloat(
                KEY_LINE_SPACING_MULTIPLIER,
                ReaderSettings.DEFAULT_LINE_SPACING_MULTIPLIER,
            ),
            paddingDp = prefs.getInt(KEY_PADDING_DP, ReaderSettings.DEFAULT_PADDING_DP),
            blockSpacingDp = prefs.getInt(KEY_BLOCK_SPACING_DP, ReaderSettings.DEFAULT_BLOCK_SPACING_DP),
        ).coerced()
    }

    fun save(context: Context, settings: ReaderSettings) {
        val coerced = settings.coerced()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FONT_SIZE_SP, coerced.fontSizeSp)
            .putFloat(KEY_LINE_SPACING_MULTIPLIER, coerced.lineSpacingMultiplier)
            .putInt(KEY_PADDING_DP, coerced.paddingDp)
            .putInt(KEY_BLOCK_SPACING_DP, coerced.blockSpacingDp)
            .apply()
    }
}
