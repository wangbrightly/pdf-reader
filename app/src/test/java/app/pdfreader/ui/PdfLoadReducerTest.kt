package app.pdfreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [PdfLoadReducer] 的单元测试——加载状态（Idle/Loading/Success/Error）的纯逻辑部分。
 *
 * 不依赖 Context / Activity 生命周期 / 任何 Android API，纯 Kotlin，普通 JUnit 就能跑，
 * 不需要 Robolectric。
 *
 * 2026-08-20：[PdfLoadReducer.fromResult] 的输入从 `Result<List<DisplayBlock>>`
 * 改成 `Result<Int>`（页数）——文字/图片真正按需加载上线后，"打开成功"不再需要
 * 预先算出任何展示块，见 [PdfLoadState.Success] 类 KDoc 完整背景。
 */
class PdfLoadReducerTest {

    @Test
    fun `成功且页数大于0时产生 Success 状态`() {
        val result = Result.success(42)

        val state = PdfLoadReducer.fromResult(result)

        assertEquals(PdfLoadState.Success(42), state)
    }

    @Test
    fun `成功但页数是0时产生 Error 状态（不当作正常结果显示空白页）`() {
        val result = Result.success(0)

        val state = PdfLoadReducer.fromResult(result)

        assertTrue(state is PdfLoadState.Error)
    }

    @Test
    fun `失败且异常带 message 时 Error 状态携带该 message`() {
        val result = Result.failure<Int>(IOException("密码保护的 PDF 无法打开"))

        val state = PdfLoadReducer.fromResult(result)

        assertEquals(PdfLoadState.Error("密码保护的 PDF 无法打开"), state)
    }

    @Test
    fun `失败且异常没有 message 时 Error 状态使用默认提示语（不能是空字符串或崩溃）`() {
        val result = Result.failure<Int>(RuntimeException())

        val state = PdfLoadReducer.fromResult(result)

        assertTrue(state is PdfLoadState.Error)
        assertTrue((state as PdfLoadState.Error).message.isNotBlank())
    }
}
