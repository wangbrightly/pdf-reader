package app.pdfreader.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [ReaderSettingsPreferences] 的单元测试——用 SharedPreferences 持久化阅读设置。
 *
 * SharedPreferences 需要真实 Context，纯 JVM 单元测试拿不到，用 Robolectric 模拟
 * （同 IntentUriResolverTest / PdfTextExtractorTest 的理由）。
 *
 * 覆盖需求"字号设置要持久化……下次打开 App 时读出来用，不是每次都回到默认值"：
 * 存进去再读出来，必须拿到同样的值（round trip），这是"重启后保持"这条需求在
 * 单元测试层面能验证到的部分（真实的"杀进程重启"验证不了，但读写这一步能）。
 */
@RunWith(RobolectricTestRunner::class)
class ReaderSettingsPreferencesTest {

    @Test
    fun `从未保存过时读出默认设置`() {
        val context = RuntimeEnvironment.getApplication()

        val loaded = ReaderSettingsPreferences.load(context)

        assertEquals(ReaderSettings(), loaded)
    }

    @Test
    fun `保存后读出的设置和保存前一致（round trip）`() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ReaderSettings(
            fontSizeSp = 24,
            lineSpacingMultiplier = 1.5f,
            paddingDp = 20,
            blockSpacingDp = 24,
        )

        ReaderSettingsPreferences.save(context, settings)
        val loaded = ReaderSettingsPreferences.load(context)

        assertEquals(settings, loaded)
    }

    @Test
    fun `保存新值会覆盖上一次保存的值`() {
        val context = RuntimeEnvironment.getApplication()

        ReaderSettingsPreferences.save(context, ReaderSettings(fontSizeSp = 18))
        ReaderSettingsPreferences.save(context, ReaderSettings(fontSizeSp = 28))
        val loaded = ReaderSettingsPreferences.load(context)

        assertEquals(28, loaded.fontSizeSp)
    }
}
