package app.pdfreader.extract

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * [PdfTextExtractor.extractContent] 接入 [RunningFooterFilter] 之后的端到端测试——
 * 构造一份 3 页文档，每页都有"正文 + 浏览器打印页脚水印（日期/标题/网址/页码）"，
 * 验证抽取结果里正文保留、水印被过滤掉。
 *
 * fixture 用自定义 1000pt 高的页面（不是标准 A4/Letter），每一行文字单独一次
 * `beginText`/`newLineAtOffset`/`endText`，行间距刻意拉开档次（正文行内 15pt、
 * 正文到页脚 200pt、页脚内部 60pt）——这样"正文 5 行合并成一个段落、页脚 4 行各自
 * 独立成段"这个分段结果是稳定可复现的，不依赖 PDFTextStripper 内部实现细节的
 * 精确数值，只依赖"行间距的相对大小关系"。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorFooterNoiseTest {

    // 用 ASCII 文字，不用中文——PDFBox 的标准 Type1 字体（Helvetica）不能编码 CJK
    // 字符，构造 fixture 时会直接抛 IllegalArgumentException；这条测试只关心
    // RunningFooterFilter 接线对不对（水印过滤、正文保留），不需要覆盖 CJK 场景
    // （CJK 相关逻辑已经有 PdfTextExtractorTest 等专门的测试覆盖）。
    private val bodyLines = listOf("This", "is", "normal", "body", "text")
    private val pageHeight = 1000f

    /** yDirAdj≈1000-y_raw；数值本身不重要，重要的是相邻行之间的间距档次。 */
    private val bodyYRaw = listOf(950f, 935f, 920f, 905f, 890f) // 相邻间距 15
    private val footerYRaw = listOf(690f, 630f, 570f, 510f) // 到正文间距 200，内部间距 60

    private fun buildDocument(): File {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)

        val document = PDDocument()
        val font = PDType1Font.HELVETICA
        for (pageNo in 1..3) {
            val page = PDPage(PDRectangle(612f, pageHeight))
            document.addPage(page)
            val stream = PDPageContentStream(document, page)
            fun writeLine(y: Float, text: String) {
                stream.beginText()
                stream.setFont(font, 12f)
                stream.newLineAtOffset(50f, y)
                stream.showText(text)
                stream.endText()
            }
            bodyLines.forEachIndexed { i, text -> writeLine(bodyYRaw[i], text) }
            writeLine(footerYRaw[0], "2026/7/10 23:21")
            writeLine(footerYRaw[1], "Happy Life Handbook (2025)")
            writeLine(footerYRaw[2], "https://baike.azpdl.net/#/entry/abc-123")
            writeLine(footerYRaw[3], "$pageNo/136")
            stream.close()
        }

        val output = File.createTempFile("footer-noise-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()
        return output
    }

    @Test
    fun `每页的日期、网址、页码水印被过滤掉，正文段落完整保留`() {
        val context = RuntimeEnvironment.getApplication()
        val content = PdfTextExtractor.extractContent(context, buildDocument())

        // 每页剩下 2 段：正文 + 标题行——日期/网址/页码这 3 种水印被过滤掉了；标题行
        // 故意不处理（见 RunningFooterFilter 类 KDoc"故意不处理标题行"一节），所以
        // 还会剩下，不是"水印全部消失、一个字不剩"。
        assertEquals(listOf(1, 1, 2, 2, 3, 3), content.paragraphPages)
        assertEquals(
            listOf(
                "This is normal body text",
                "Happy Life Handbook (2025)",
                "This is normal body text",
                "Happy Life Handbook (2025)",
                "This is normal body text",
                "Happy Life Handbook (2025)",
            ),
            content.paragraphs,
        )

        val joined = content.paragraphs.joinToString("\n")
        assertFalse(joined.contains("2026/7/10"))
        assertFalse(joined.contains("baike.azpdl.net"))
        assertFalse(joined.contains("136"))
    }

    @Test
    fun `没有水印特征的正常文档不受影响`() {
        val context = RuntimeEnvironment.getApplication()
        val content = PdfTextExtractor.extractContent(context, buildFixtureWithoutFooter())
        assertEquals(listOf("Plain body text with no header or footer watermark."), content.paragraphs)
    }

    private fun buildFixtureWithoutFooter(): File {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        val stream = PDPageContentStream(document, page)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(50f, 700f)
        stream.showText("Plain body text with no header or footer watermark.")
        stream.endText()
        stream.close()
        val output = File.createTempFile("no-footer-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()
        return output
    }
}
