package app.pdfreader.extract

import app.pdfreader.ui.DisplayBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * [PdfTextExtractor.Session] 的单元测试——"按需加载"增量，见该类 KDoc 完整背景。
 * 核心要验证两件事：即时可用阶段（[PdfTextExtractor.Session.paragraphs] 等字段）
 * 跟 [PdfTextExtractor.extractContent] 抽取出的文字结果一致（只是不含图片），以及
 * 按需调用 [PdfTextExtractor.Session.loadPageMedia] 之后能拿到跟 [extractContent]
 * 里同一批图片等价的内容。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorSessionTest {

    private fun loadFixtureFile(name: String): File {
        val resourceStream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(name),
        ) { "找不到测试 fixture：src/test/resources/$name" }
        val tempFile = File.createTempFile(name, ".pdf")
        tempFile.deleteOnExit()
        resourceStream.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        return tempFile
    }

    @Test
    fun `纯文字文档打开 Session 立刻拿到跟 extractContent 一致的文字，没有待加载媒体`() {
        val context = RuntimeEnvironment.getApplication()
        val file = loadFixtureFile("sample-chinese.pdf")
        val expected = PdfTextExtractor.extractContent(context, loadFixtureFile("sample-chinese.pdf"))

        PdfTextExtractor.Session.open(context, file).use { session ->
            assertEquals(expected.paragraphs, session.paragraphs)
            assertEquals(expected.paragraphPages, session.paragraphPages)
            assertTrue(
                "纯文字文档不该有任何待加载媒体位置",
                session.pendingMediaPageByAfterIndex.isEmpty(),
            )
        }
    }

    @Test
    fun `带图片的文档，即时可用阶段文字已就绪，图片位置标记为待加载`() {
        val context = RuntimeEnvironment.getApplication()
        val file = loadFixtureFile("sample-with-image.pdf")

        PdfTextExtractor.Session.open(context, file).use { session ->
            assertEquals(3, session.paragraphs.size)
            assertTrue(
                "带图片的页应该被标记为待加载",
                session.pendingMediaPageByAfterIndex.isNotEmpty(),
            )
        }
    }

    @Test
    fun `loadPageMedia 按需调用后能拿到跟 extractContent 数量一致的图片`() {
        val context = RuntimeEnvironment.getApplication()
        val expected = PdfTextExtractor.extractContent(context, loadFixtureFile("sample-with-image.pdf"))

        PdfTextExtractor.Session.open(context, loadFixtureFile("sample-with-image.pdf")).use { session ->
            val loadedCount = session.pendingMediaPageByAfterIndex.values.flatten()
                .sumOf { pageNo -> session.loadPageMedia(pageNo).size }
            assertEquals(expected.images.size, loadedCount)
        }
    }

    @Test
    fun `表格区域场景——表格前后的正文在即时可用阶段就有，表格单元格内容被排除`() {
        val context = RuntimeEnvironment.getApplication()
        val file = loadFixtureFile("sample-with-table.pdf")

        PdfTextExtractor.Session.open(context, file).use { session ->
            assertTrue(
                "表格前的说明文字应该在即时可用阶段就有",
                session.paragraphs.any { it.contains("这段文字之后紧跟着一张带边框的表格") },
            )
            assertTrue(
                "表格后的说明文字也应该在即时可用阶段就有",
                session.paragraphs.any { it.contains("这是表格后的说明文字") },
            )
            assertTrue(
                "表格单元格内容不应该出现在即时可用阶段的文字里",
                session.paragraphs.none { it.contains("螺丝") || it.contains("M3x10") },
            )
            assertEquals(1, session.pendingMediaPageByAfterIndex.values.flatten().size)
        }
    }

    @Test
    fun `表格区域场景——loadPageMedia 加载出裁剪后的表格图片`() {
        val context = RuntimeEnvironment.getApplication()
        val file = loadFixtureFile("sample-with-table.pdf")

        PdfTextExtractor.Session.open(context, file).use { session ->
            val pageNo = session.pendingMediaPageByAfterIndex.values.flatten().single()
            val media = session.loadPageMedia(pageNo)
            assertEquals(1, media.size)
            // 跟 PdfTextExtractorTableTest 里"高度明显小于整页"那条测试同一个判断依据。
            assertTrue("裁剪后的高度应该明显小于整页", media.single().height < 900)
        }
    }

    // ---- Session.loadPage：文字/图片真正按需加载，2026-08-20 增量（按页加载，
    // 不再要求打开时就抽完全部页面），见 Session 类 KDoc"文字/图片真正按需加载"
    // 一节。这几条测试跟上面几条测的是同一批 fixture、同一批预期内容，只是改成
    // 逐页调 loadPage 拼起来验证，不依赖 paragraphs/pendingMediaPageByAfterIndex
    // 这套旧字段 ----

    @Test
    fun `loadPage 逐页拼起来的文字段落和 extractContent 一致（纯文字文档）`() {
        val context = RuntimeEnvironment.getApplication()
        val file = loadFixtureFile("sample-chinese.pdf")
        val expected = PdfTextExtractor.extractContent(context, loadFixtureFile("sample-chinese.pdf"))

        PdfTextExtractor.Session.open(context, file).use { session ->
            val texts = (1..session.pageCount).flatMap { pageNo ->
                session.loadPage(pageNo).blocks.filterIsInstance<DisplayBlock.Text>().map { it.text }
            }
            assertEquals(expected.paragraphs, texts)
        }
    }

    @Test
    fun `loadPage 能拿到图片，数量跟 extractContent 一致`() {
        val context = RuntimeEnvironment.getApplication()
        val file = loadFixtureFile("sample-with-image.pdf")
        val expected = PdfTextExtractor.extractContent(context, loadFixtureFile("sample-with-image.pdf"))

        PdfTextExtractor.Session.open(context, file).use { session ->
            val allBlocks = (1..session.pageCount).flatMap { session.loadPage(it).blocks }
            val texts = allBlocks.filterIsInstance<DisplayBlock.Text>()
            val images = allBlocks.filterIsInstance<DisplayBlock.Image>()
            assertEquals(3, texts.size)
            assertEquals(expected.images.size, images.size)
        }
    }

    @Test
    fun `loadPage 表格区域场景——表格前后正文保留，单元格内容排除，表格裁成一张图`() {
        val context = RuntimeEnvironment.getApplication()
        val file = loadFixtureFile("sample-with-table.pdf")

        PdfTextExtractor.Session.open(context, file).use { session ->
            val allBlocks = (1..session.pageCount).flatMap { session.loadPage(it).blocks }
            val texts = allBlocks.filterIsInstance<DisplayBlock.Text>().map { it.text }
            val images = allBlocks.filterIsInstance<DisplayBlock.Image>()

            assertTrue(
                "表格前的说明文字应该保留",
                texts.any { it.contains("这段文字之后紧跟着一张带边框的表格") },
            )
            assertTrue(
                "表格后的说明文字应该保留",
                texts.any { it.contains("这是表格后的说明文字") },
            )
            assertTrue(
                "表格单元格内容不应该出现在文字段落里",
                texts.none { it.contains("螺丝") || it.contains("M3x10") },
            )
            assertEquals("表格应该被裁剪成一张图片", 1, images.size)
        }
    }

    @Test
    fun `pageCount 和 outline 立刻可用`() {
        val context = RuntimeEnvironment.getApplication()
        val file = loadFixtureFile("sample-with-outline.pdf")

        PdfTextExtractor.Session.open(context, file).use { session ->
            assertEquals(3, session.pageCount)
            assertEquals(4, session.outline.size)
        }
    }
}
