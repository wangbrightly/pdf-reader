package app.pdfreader.ui

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [IntentUriResolver] 的单元测试。
 *
 * android.content.Intent / android.net.Uri 在纯 JVM 单元测试用的 android.jar 里都是
 * "throw new RuntimeException(Stub!)" 的桩实现，必须用 Robolectric 跑（同
 * PdfTextExtractorTest 的理由）。
 *
 * 覆盖的场景是"用户从别的 App 用……打开一个 PDF 分享过来"——系统会用 ACTION_VIEW
 * 隐式 Intent 启动 MainActivity，Uri 放在 Intent.data 里。App 内点击"打开 PDF"按钮
 * 走的是 ActivityResultContracts.OpenDocument()，直接拿到 Uri，不经过这个函数，
 * 所以不需要为那条路径写测试。
 */
@RunWith(RobolectricTestRunner::class)
class IntentUriResolverTest {

    @Test
    fun `intent 为 null 时返回 null`() {
        assertNull(IntentUriResolver.resolvePdfUri(null))
    }

    @Test
    fun `ACTION_VIEW 且带数据时返回该 Uri`() {
        val uri = Uri.parse("content://com.example.provider/document/1")
        val intent = Intent(Intent.ACTION_VIEW).setData(uri)

        assertEquals(uri, IntentUriResolver.resolvePdfUri(intent))
    }

    @Test
    fun `ACTION_VIEW 但没有数据时返回 null`() {
        val intent = Intent(Intent.ACTION_VIEW)

        assertNull(IntentUriResolver.resolvePdfUri(intent))
    }

    @Test
    fun `非 ACTION_VIEW 的 intent（比如正常图标启动）返回 null`() {
        val uri = Uri.parse("content://com.example.provider/document/1")
        val intent = Intent(Intent.ACTION_MAIN).setData(uri)

        assertNull(IntentUriResolver.resolvePdfUri(intent))
    }
}
