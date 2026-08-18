package app.pdfreader.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [ReadingProgressStore] 的单元测试——按文件 key 存取阅读进度（滚动比例）。
 *
 * SharedPreferences 需要真实 Context，用 Robolectric 模拟（同
 * ReaderSettingsPreferencesTest 的理由）。
 */
@RunWith(RobolectricTestRunner::class)
class ReadingProgressStoreTest {

    @Test
    fun `没保存过时读出 null，而不是 0`() {
        val context = RuntimeEnvironment.getApplication()

        val loaded = ReadingProgressStore.loadProgress(context, "some-key")

        // null 表示"没有这个文件的记录"，不能用 0f 当默认值——0f 是"读到过开头"这个
        // 有意义的进度值，两者语义不同，调用方要能区分"从未打开过"和"上次读到开头"。
        assertNull(loaded)
    }

    @Test
    fun `保存后读出的比例和保存前一致（round trip）`() {
        val context = RuntimeEnvironment.getApplication()

        ReadingProgressStore.saveProgress(context, "file-a", 0.42f)
        val loaded = ReadingProgressStore.loadProgress(context, "file-a")

        assertEquals(0.42f, loaded!!, 0.0001f)
    }

    @Test
    fun `不同文件的进度互不影响`() {
        val context = RuntimeEnvironment.getApplication()

        ReadingProgressStore.saveProgress(context, "file-a", 0.3f)
        ReadingProgressStore.saveProgress(context, "file-b", 0.8f)

        assertEquals(0.3f, ReadingProgressStore.loadProgress(context, "file-a")!!, 0.0001f)
        assertEquals(0.8f, ReadingProgressStore.loadProgress(context, "file-b")!!, 0.0001f)
    }

    @Test
    fun `保存新值会覆盖同一个 key 上一次保存的值`() {
        val context = RuntimeEnvironment.getApplication()

        ReadingProgressStore.saveProgress(context, "file-a", 0.1f)
        ReadingProgressStore.saveProgress(context, "file-a", 0.9f)
        val loaded = ReadingProgressStore.loadProgress(context, "file-a")

        assertEquals(0.9f, loaded!!, 0.0001f)
    }

    @Test
    fun `比例会被收拢到 0 到 1 之间`() {
        val context = RuntimeEnvironment.getApplication()

        ReadingProgressStore.saveProgress(context, "file-a", 1.5f)
        val loaded = ReadingProgressStore.loadProgress(context, "file-a")

        assertEquals(1.0f, loaded!!, 0.0001f)
    }
}
