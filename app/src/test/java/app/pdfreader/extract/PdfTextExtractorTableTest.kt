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
 * [PdfTextExtractor.extractContent] "疑似表格区域裁剪为图片、同页其余正文照常重排"
 * 这个增量的单元测试。
 *
 * fixture `sample-with-table.pdf` 的生成方式和 [PdfTextExtractorImageTest] 顶部注释里
 * 记录的同一套路子：本机 Puppeteer（`~/.claude-tools/webshot`）把一段 HTML 打印成
 * PDF。两页：
 *
 * - 第 1 页：一段说明文字 → 一个 3 列 4 行、带边框的 `<table>` → 一段说明文字。
 *   反编译过 content stream 确认表格边框被 Chromium 画成了细长的填充矩形网格
 *   （不是描边直线），共 4 条横向边界线 + 4 条纵向边界线——见 [TableGridDetector]
 *   类注释。这份 fixture 正好是"一页里表格前后都有正文"这个场景的现成测试素材，
 *   2026-08-19 表格检测从"整页降级"改成"区域裁剪"之后，这份 fixture 不用重新
 *   生成，只是断言的预期结果变了（表格前后的正文不再随整页一起消失）。
 * - 第 2 页：一段较长的纯说明文字，完全没有表格结构，用来验证"没有表格的页面
 *   继续走文字抽取+重排"这条回归检查点，以及"同一份文档里表格页和非表格页可以
 *   共存、互不影响"。
 *
 * 用 `pdftotext -raw`/`pdfinfo` 交叉核对过第 1 页的完整文字："这是表格前的说明
 * 文字……这段文字之后紧跟着一张带边框的表格。" → 表格单元格（项目/规格/数量、
 * 螺丝/螺母/垫片……）→ "这是表格后的说明文字……"——表格后那段文字里提到"这一整页
 * ……会被整页渲染成图片"是 fixture 早年生成时写的说明性文字，描述的是改动前的
 * 行为，现在已经不准确了（这份 fixture 生成脚本没有纳入仓库，不重新生成去改这句
 * 装饰性文字，不影响下面测试断言的正确性）。
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
    fun `表格区域被裁剪渲染成图片，表格前后的说明文字仍然正常显示为段落`() {
        val content = extractFixtureContent()

        assertTrue(
            "表格前的说明文字应该正常出现在文字段落里——不再随整页一起降级",
            content.paragraphs.any { it.contains("这段文字之后紧跟着一张带边框的表格") },
        )
        assertTrue(
            "表格后的说明文字也应该正常出现在文字段落里",
            content.paragraphs.any { it.contains("这是表格后的说明文字") },
        )
        assertFalse(
            "表格单元格内容（比如“螺丝”“M3x10”）不应该出现在文字段落里——已经被裁进表格图片",
            content.paragraphs.any { it.contains("螺丝") || it.contains("M3x10") },
        )
        assertTrue("表格区域应该被裁剪渲染成至少一张图片", content.images.isNotEmpty())
    }

    @Test
    fun `表格图片插在表格前后两段说明文字之间，不是插在整页末尾`() {
        val content = extractFixtureContent()
        val beforeIndex = content.paragraphs.indexOfFirst { it.contains("这段文字之后紧跟着一张带边框的表格") }
        val afterIndex = content.paragraphs.indexOfFirst { it.contains("这是表格后的说明文字") }
        require(beforeIndex >= 0 && afterIndex >= 0) { "前置断言失败：前后说明文字应该都在 paragraphs 里" }

        val tableImage = content.images.first()
        assertTrue(
            "表格图片应该插在表格前的说明文字之后，实际 afterParagraphIndex=${tableImage.afterParagraphIndex}",
            tableImage.afterParagraphIndex >= beforeIndex,
        )
        assertTrue(
            "表格图片应该插在表格后的说明文字之前，实际 afterParagraphIndex=${tableImage.afterParagraphIndex}",
            tableImage.afterParagraphIndex < afterIndex,
        )
    }

    @Test
    fun `表格裁剪出的图片高度明显小于整页，证明确实被裁剪了而不是又整页渲染`() {
        val content = extractFixtureContent()
        val bitmap = content.images.first().bitmap
        // A4 页面（595x842pt）在 TABLE_PAGE_RENDER_DPI（150）下整页渲染大约是
        // 1240x1754px。这份 fixture 的表格宽度接近整个正文内容区宽度（实测裁剪后
        // 宽度约 1000px，跟整页宽度差别不大，不是一个有区分度的信号），但只有
        // 3 列 4 行、高度只占页面一小部分——高度这个维度才是"确实被裁剪了、不是
        // 整页渲染"的可靠信号，用一个宽松但有区分度的上限，不锁死具体像素数值。
        assertTrue("裁剪后的高度应该明显小于整页(~1754px)，实际是 ${bitmap.height}", bitmap.height < 900)
        // 同时也不能裁没了——至少要有个几十像素，证明确实裁出了有内容的一块区域。
        assertTrue("裁剪后的高度不应该小到像裁剪出错，实际是 ${bitmap.height}", bitmap.height > 50)
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
