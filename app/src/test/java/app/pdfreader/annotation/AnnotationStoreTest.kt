package app.pdfreader.annotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * [AnnotationStore] 的单元测试——批注按文档分文件存 JSON。
 *
 * 不沿用 [app.pdfreader.progress.ReadingProgressStore] 那套 SharedPreferences：
 * 那是扁平键值对，一份文档一个 Int 刚好合适；批注是"一份文档一串带结构的记录"，
 * 塞进 SharedPreferences 只能自己拼字符串，不如直接落 JSON 文件。文件名用的还是
 * 同一个文件标识（内容 SHA-256，见 [app.pdfreader.progress.ReadingProgressKey]），
 * 换句话说同一份书改了文件名/挪了位置，批注照样认得出来。
 *
 * 需要真实 Context 拿 filesDir，用 Robolectric（同 ReadingProgressStoreTest 的理由）。
 */
@RunWith(RobolectricTestRunner::class)
class AnnotationStoreTest {

    private fun annotation(id: String, page: Int, quotedText: String) = TextAnnotation(
        id = id,
        kind = AnnotationKind.HIGHLIGHT,
        anchor = TextAnchor(
            page = page,
            paragraphIndex = 0,
            startOffset = 0,
            endOffset = quotedText.length,
            quotedText = quotedText,
        ),
        createdAt = 1_726_000_000_000L,
    )

    @Test
    fun `没存过的文档读出空列表，不是 null`() {
        val context = RuntimeEnvironment.getApplication()

        // 空列表让调用方可以直接遍历，不用每处都判空——"这份文档还没有批注"和
        // "有批注但一条都没剩"对使用者来说是同一件事，不需要区分。
        assertEquals(emptyList<TextAnnotation>(), AnnotationStore.load(context, "没存过的-key"))
    }

    @Test
    fun `存进去再读出来，内容完全一致`() {
        val context = RuntimeEnvironment.getApplication()
        val saved = listOf(
            annotation("a1", page = 3, quotedText = "凭手感判断这批纸能不能过机"),
            annotation("a2", page = 47, quotedText = "读数更准"),
        )

        AnnotationStore.save(context, "书-key", saved)

        assertEquals(saved, AnnotationStore.load(context, "书-key"))
    }

    @Test
    fun `不同文档互不干扰`() {
        val context = RuntimeEnvironment.getApplication()
        AnnotationStore.save(context, "甲书", listOf(annotation("a1", 1, "甲书的话")))
        AnnotationStore.save(context, "乙书", listOf(annotation("b1", 1, "乙书的话")))

        assertEquals("甲书的话", AnnotationStore.load(context, "甲书").single().anchor.quotedText)
        assertEquals("乙书的话", AnnotationStore.load(context, "乙书").single().anchor.quotedText)
    }

    @Test
    fun `存空列表等于清空，不留下半截旧数据`() {
        val context = RuntimeEnvironment.getApplication()
        AnnotationStore.save(context, "书-key", listOf(annotation("a1", 1, "要被删掉的话")))

        AnnotationStore.save(context, "书-key", emptyList())

        assertEquals(emptyList<TextAnnotation>(), AnnotationStore.load(context, "书-key"))
    }

    @Test
    fun `文件内容损坏时读出空列表，不抛异常`() {
        val context = RuntimeEnvironment.getApplication()
        AnnotationStore.save(context, "坏文件", listOf(annotation("a1", 1, "原本好好的")))
        // 模拟写到一半断电/被别的程序改坏：批注是"锦上添花"的数据，损坏时宁可当作
        // 没有，也不能让用户连书都打不开——阅读本身不依赖批注。
        AnnotationStore.fileFor(context, "坏文件").writeText("{ 这不是合法 JSON")

        assertEquals(emptyList<TextAnnotation>(), AnnotationStore.load(context, "坏文件"))
    }

    @Test
    fun `批注文件存在 App 私有目录下的独立子目录里`() {
        val context = RuntimeEnvironment.getApplication()

        val file: File = AnnotationStore.fileFor(context, "书-key")

        // 单独一个子目录，方便以后整体导出/备份/清理，不跟别的私有文件混在一起。
        assertEquals("annotations", file.parentFile?.name)
        assertTrue(file.absolutePath.startsWith(context.filesDir.absolutePath))
    }
}
