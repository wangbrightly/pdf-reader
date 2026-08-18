package app.pdfreader.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * [PdfTextExtractor.extractContent] "疑似表格整页降级为图片"这个增量的单元测试。
 *
 * fixture `sample-with-table.pdf` 的生成方式和 [PdfTextExtractorImageTest] 顶部注释里
 * 记录的同一套路子：本机 Puppeteer（`~/.claude-tools/webshot`）把一段 HTML 打印成
 * PDF。两页：
 *
 * - 第 1 页：一段说明文字 → 一个 3 列 4 行、带边框的 `<table>` → 一段说明文字。
 *   反编译过 content stream 确认表格边框被 Chromium 画成了细长的填充矩形网格
 *   （不是描边直线），共 4 条横向边界线 + 4 条纵向边界线——见 [TableGridDetector]
 *   类注释。
 * - 第 2 页：一段较长的纯说明文字，完全没有表格结构，用来验证"没有表格的页面
 *   继续走文字抽取+重排"这条回归检查点，以及"同一份文档里表格页和非表格页可以
 *   共存、互不影响"。
 *
 * 用 `pdftotext -raw`/`pdfinfo` 交叉核对过：2 页，第 1 页文字包含表格前后的两段
 * 说明文字和表格单元格内容，第 2 页是独立的一段长文字。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorTableTest {

    private fun loadFixtureFile(name: String): File {
        val resourceStream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(name)
        ) { "找不到测试 fixture：src/test/resources/$name" }

        val tempFile = File.createTempFile(name, ".pdf")
        tempFile.deleteOnExit()
        resourceStream.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        return tempFile
    }

    private fun extractFixtureContent(): PdfContent {
        val context = RuntimeEnvironment.getApplication()
        return PdfTextExtractor.extractContent(context, loadFixtureFile("sample-with-table.pdf"))
    }

    @Test
    fun `含表格的第一页整页渲染成一张图片，不出现在文字段落里`() {
        val content = extractFixtureContent()

        assertFalse(
            "第 1 页表格前后的说明文字不应该出现在文字段落里（整页降级为图片）",
            content.paragraphs.any { it.contains("这段文字之后紧跟着一张带边框的表格") },
        )
        assertFalse(
            "表格单元格内容（比如“螺丝”“M3x10”）不应该出现在文字段落里",
            content.paragraphs.any { it.contains("螺丝") || it.contains("M3x10") },
        )
        assertTrue("表格页应该被整页渲染成至少一张图片", content.images.isNotEmpty())
    }

    @Test
    fun `表格整页渲染出的图片尺寸接近整页大小（而不是一张小图标）`() {
        val content = extractFixtureContent()
        val bitmap = content.images.first().bitmap
        // A4 页面在常见渲染 DPI 下宽度至少几百像素——用一个宽松的下限，
        // 只是为了区分"整页渲染"和"抽取小图标"这两种量级，不锁死具体 DPI 数值。
        assertTrue("整页渲染的图片宽度应该有几百像素量级，实际是 ${bitmap.width}", bitmap.width > 300)
        assertTrue("整页渲染的图片高度应该有几百像素量级，实际是 ${bitmap.height}", bitmap.height > 300)
    }

    @Test
    fun `没有表格的第二页文字继续正常抽取，不受第一页表格降级影响（回归检查点）`() {
        val content = extractFixtureContent()

        assertTrue(
            "第 2 页的正文应该正常出现在文字段落里",
            content.paragraphs.any { it.contains("这是第二页的正文") },
        )
        assertTrue(
            content.paragraphs.any { it.contains("用于验证没有表格的页面行为不受影响这条回归检查点") },
        )
    }

    @Test
    fun `纯文字 PDF（无表格）抽取结果与改动前完全一致（回归检查点）`() {
        val context = RuntimeEnvironment.getApplication()
        val content = PdfTextExtractor.extractContent(context, loadFixtureFile("sample-chinese.pdf"))
        assertTrue("没有表格的 PDF 不应该产生任何整页渲染的图片", content.images.isEmpty())
        assertEquals(
            PdfTextExtractor.extractParagraphs(context, loadFixtureFile("sample-chinese.pdf")),
            content.paragraphs,
        )
    }

    @Test
    fun `已有的图片浮动展示 fixture（无表格）行为不受影响（回归检查点）`() {
        val context = RuntimeEnvironment.getApplication()
        val content = PdfTextExtractor.extractContent(context, loadFixtureFile("sample-with-image.pdf"))
        assertEquals(1, content.images.size)
        assertEquals(3, content.paragraphs.size)
    }
}
