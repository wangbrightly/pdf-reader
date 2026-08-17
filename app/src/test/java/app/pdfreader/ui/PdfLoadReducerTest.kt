package app.pdfreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [PdfLoadReducer] 的单元测试——加载状态（Idle/Loading/Success/Error）的纯逻辑部分。
 *
 * 不依赖 Context / Activity 生命周期 / 任何 Android API，纯 Kotlin，普通 JUnit 就能跑，
 * 不需要 Robolectric。对应需求 5"抽取失败要弹提示，不能崩溃闪退"——这里保证的是
 * "任何异常都会被转换成 Error 状态而不是继续往外抛"这一层逻辑。
 */
class PdfLoadReducerTest {

    @Test
    fun `成功且有内容时产生 Success 状态`() {
        val result = Result.success(listOf("第一段", "第二段"))

        val state = PdfLoadReducer.fromResult(result)

        assertEquals(PdfLoadState.Success(listOf("第一段", "第二段")), state)
    }

    @Test
    fun `成功但内容为空列表时产生 Error 状态（不当作正常结果显示空白页）`() {
        val result = Result.success(emptyList<String>())

        val state = PdfLoadReducer.fromResult(result)

        assertTrue(state is PdfLoadState.Error)
    }

    @Test
    fun `失败且异常带 message 时 Error 状态携带该 message`() {
        val result = Result.failure<List<String>>(IOException("密码保护的 PDF 无法打开"))

        val state = PdfLoadReducer.fromResult(result)

        assertEquals(PdfLoadState.Error("密码保护的 PDF 无法打开"), state)
    }

    @Test
    fun `失败且异常没有 message 时 Error 状态使用默认提示语（不能是空字符串或崩溃）`() {
        val result = Result.failure<List<String>>(RuntimeException())

        val state = PdfLoadReducer.fromResult(result)

        assertTrue(state is PdfLoadState.Error)
        assertTrue((state as PdfLoadState.Error).message.isNotBlank())
    }
}
