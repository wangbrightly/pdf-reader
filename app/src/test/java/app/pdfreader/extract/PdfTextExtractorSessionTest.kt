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
 * [PdfTextExtractor.Session] 的单元测试——文字/图片真正按需加载，见该类 KDoc 完整
 * 背景。核心要验证：按页调用 [PdfTextExtractor.Session.loadPage] 逐页拼起来的结果
 * 跟 [PdfTextExtractor.extractContent] 一次性抽取出的结果等价（文字、图片、表格
 * 区域裁剪都一致），以及 [PdfTextExtractor.Session.pageCount]/[PdfTextExtractor
 * .Session.outline] 打开后立刻可用。
 *
 * 2026-08-20：这个文件曾经还测过"即时可用阶段" `paragraphs`/`loadPageMedia` 这套
 * 旧字段（"文字一次性抽完、图片按需加载"那版 `Session`）——那套字段已经在这次改造
 * 里整体删除（见 NOTES.md #21、`/Users/mac/.claude/plans/fizzy-snuggling-cloud.md`），
 * 对应的测试也一并删除，不是遗漏。
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
