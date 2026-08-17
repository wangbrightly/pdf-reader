package app.pdfreader.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ReaderSettings] 的纯 Kotlin 单元测试——字号 12sp–32sp、行距倍数、边距 dp 的范围收拢逻辑。
 *
 * 不依赖 Context / SharedPreferences，纯数据类 + 纯函数，普通 JUnit 就能跑。
 * 覆盖"从 SharedPreferences 读出一个越界值（比如以后调整了范围，或者数据被意外改坏）
 * 要收拢回合法区间，不能让越界值直接喂给 setTextSize() 之类的 Android API"这条防线。
 */
class ReaderSettingsTest {

    @Test
    fun `默认值本身就在合法区间内`() {
        val defaults = ReaderSettings()
        assertEquals(defaults, defaults.coerced())
    }

    @Test
    fun `字号低于 12sp 时收拢到 12sp`() {
        val settings = ReaderSettings(fontSizeSp = 5)
        assertEquals(12, settings.coerced().fontSizeSp)
    }

    @Test
    fun `字号高于 32sp 时收拢到 32sp`() {
        val settings = ReaderSettings(fontSizeSp = 999)
        assertEquals(32, settings.coerced().fontSizeSp)
    }

    @Test
    fun `字号在 12sp 到 32sp 之间时原样保留`() {
        val settings = ReaderSettings(fontSizeSp = 20)
        assertEquals(20, settings.coerced().fontSizeSp)
    }

    @Test
    fun `行距倍数越界时收拢到 1点0 到 2点0 之间`() {
        assertEquals(1.0f, ReaderSettings(lineSpacingMultiplier = 0.3f).coerced().lineSpacingMultiplier)
        assertEquals(2.0f, ReaderSettings(lineSpacingMultiplier = 9.9f).coerced().lineSpacingMultiplier)
    }

    @Test
    fun `边距越界时收拢到 8dp 到 32dp 之间`() {
        assertEquals(8, ReaderSettings(paddingDp = -10).coerced().paddingDp)
        assertEquals(32, ReaderSettings(paddingDp = 500).coerced().paddingDp)
    }
}
