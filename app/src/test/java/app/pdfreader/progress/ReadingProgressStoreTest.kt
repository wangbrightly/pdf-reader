package app.pdfreader.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [ReadingProgressStore] 的单元测试——按文件 key 存取阅读进度（2026-08-20 从"滚动
 * 比例"改成"页码"，见该类 KDoc 完整背景）。
 *
 * SharedPreferences 需要真实 Context，用 Robolectric 模拟（同
 * ReaderSettingsPreferencesTest 的理由）。
 */
@RunWith(RobolectricTestRunner::class)
class ReadingProgressStoreTest {

    @Test
    fun `没保存过时读出 null，而不是某个默认页码`() {
        val context = RuntimeEnvironment.getApplication()

        val loaded = ReadingProgressStore.loadPage(context, "some-key")

        // null 表示"没有这个文件的记录"，不能用某个固定页码当默认值——调用方要能
        // 区分"从未打开过"和"上次就是读到第 1 页"这两种情况。
        assertNull(loaded)
    }

    @Test
    fun `保存后读出的页码和保存前一致（round trip）`() {
        val context = RuntimeEnvironment.getApplication()

        ReadingProgressStore.savePage(context, "file-a", 42)
        val loaded = ReadingProgressStore.loadPage(context, "file-a")

        assertEquals(42, loaded)
    }

    @Test
    fun `不同文件的进度互不影响`() {
        val context = RuntimeEnvironment.getApplication()

        ReadingProgressStore.savePage(context, "file-a", 3)
        ReadingProgressStore.savePage(context, "file-b", 88)

        assertEquals(3, ReadingProgressStore.loadPage(context, "file-a"))
        assertEquals(88, ReadingProgressStore.loadPage(context, "file-b"))
    }

    @Test
    fun `保存新值会覆盖同一个 key 上一次保存的值`() {
        val context = RuntimeEnvironment.getApplication()

        ReadingProgressStore.savePage(context, "file-a", 1)
        ReadingProgressStore.savePage(context, "file-a", 99)
        val loaded = ReadingProgressStore.loadPage(context, "file-a")

        assertEquals(99, loaded)
    }

    @Test
    fun `页码会被收拢到至少是1`() {
        val context = RuntimeEnvironment.getApplication()

        ReadingProgressStore.savePage(context, "file-a", 0)
        val loaded = ReadingProgressStore.loadPage(context, "file-a")

        assertEquals(1, loaded)
    }
}
