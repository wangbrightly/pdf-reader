package app.pdfreader.progress

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [LastOpenedFileStore] 的单元测试——记住"最近一次成功打开的文件"，用来在冷启动时
 * 自动恢复，见该类 KDoc 完整背景（MIUI 锁屏清理杀进程导致阅读中的文档无声消失）。
 *
 * SharedPreferences 需要真实 Context，用 Robolectric 模拟（同 ReadingProgressStoreTest
 * 的理由）。
 */
@RunWith(RobolectricTestRunner::class)
class LastOpenedFileStoreTest {

    @Test
    fun `没保存过时读出 null`() {
        val context = RuntimeEnvironment.getApplication()

        assertNull(LastOpenedFileStore.load(context))
    }

    @Test
    fun `保存后读出的 Uri 和保存前一致（round trip）`() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fa.pdf")

        LastOpenedFileStore.save(context, uri)

        assertEquals(uri, LastOpenedFileStore.load(context))
    }

    @Test
    fun `保存新值会覆盖上一次保存的值`() {
        val context = RuntimeEnvironment.getApplication()
        val first = Uri.parse("content://provider/document/1")
        val second = Uri.parse("content://provider/document/2")

        LastOpenedFileStore.save(context, first)
        LastOpenedFileStore.save(context, second)

        assertEquals(second, LastOpenedFileStore.load(context))
    }

    @Test
    fun `clear 之后读出 null`() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://provider/document/1")

        LastOpenedFileStore.save(context, uri)
        LastOpenedFileStore.clear(context)

        assertNull(LastOpenedFileStore.load(context))
    }
}
