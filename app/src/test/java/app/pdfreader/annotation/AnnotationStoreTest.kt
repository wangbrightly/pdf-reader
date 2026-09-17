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
    fun `笔记的正文内容存得进也读得出`() {
        val context = RuntimeEnvironment.getApplication()
        val note = TextAnnotation(
            id = "n1",
            kind = AnnotationKind.NOTE,
            anchor = TextAnchor(47, 0, 0, 4, "读数更准"),
            createdAt = 1_726_000_000_000L,
            note = "跟第 112 页的自动检测那段对照着看",
        )

        AnnotationStore.save(context, "书-key", listOf(note))

        assertEquals(note, AnnotationStore.load(context, "书-key").single())
    }

    @Test
    fun `高亮和下划线没有笔记正文，读出来是 null`() {
        val context = RuntimeEnvironment.getApplication()
        val underline = TextAnnotation(
            id = "u1",
            kind = AnnotationKind.UNDERLINE,
            anchor = TextAnchor(47, 0, 0, 4, "读数更准"),
            createdAt = 1_726_000_000_000L,
        )

        AnnotationStore.save(context, "书-key", listOf(underline))

        assertEquals(null, AnnotationStore.load(context, "书-key").single().note)
    }

    @Test
    fun `读得动增量 2 存的老数据（那时候还没有 note 字段）`() {
        val context = RuntimeEnvironment.getApplication()
        // 增量 2 上线时存下来的真实格式，升级后不能读不出来——批注是用户自己攒的
        // 数据，格式往后加字段时老数据必须照常打得开。
        AnnotationStore.fileFor(context, "老数据").writeText(
            """[{"id":"f88509cd","kind":"HIGHLIGHT","createdAt":1789611814526,"page":9,""" +
                """"paragraphIndex":0,"startOffset":20,"endOffset":22,"quotedText":"运转"}]""",
        )

        val loaded = AnnotationStore.load(context, "老数据").single()

        assertEquals(AnnotationKind.HIGHLIGHT, loaded.kind)
        assertEquals("运转", loaded.anchor.quotedText)
        assertEquals(null, loaded.note)
    }

    @Test
    fun `认不出的批注种类整条跳过，不连累同一份文件里其它批注`() {
        val context = RuntimeEnvironment.getApplication()
        // 场景：以后版本加了新种类，用户又用旧版本打开。跳过好过崩溃，也好过
        // 把它当成别的种类显示成错的样子。
        AnnotationStore.fileFor(context, "含未来种类").writeText(
            """[{"id":"x1","kind":"未来才有的种类","createdAt":1,"page":1,"paragraphIndex":0,""" +
                """"startOffset":0,"endOffset":2,"quotedText":"某段"},""" +
                """{"id":"h1","kind":"HIGHLIGHT","createdAt":2,"page":1,"paragraphIndex":0,""" +
                """"startOffset":0,"endOffset":2,"quotedText":"某段"}]""",
        )

        val loaded = AnnotationStore.load(context, "含未来种类")

        assertEquals(1, loaded.size)
        assertEquals("h1", loaded.single().id)
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
