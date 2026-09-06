package app.pdfreader.extract

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 代码块识别（[PdfTextExtractor.isMonospaceTextPosition]/[PdfTextExtractor.Line
 * .isMonospace]）的端到端测试——见 [PdfTextExtractor.linesToParagraphs] KDoc
 * "代码块识别"一节完整背景（2026-09-06 新增，借鉴 mj_pdf 用等宽字体占比判断
 * 代码块的思路）。
 *
 * `PDType1Font.COURIER` 是 PDF 标准 14 内置字体之一、真正的等宽字体，不需要像
 * [PdfTextExtractorDecorativeFontTest] 那样改字体名做手脚——它的字体描述符
 * `isFixedPitch()` 应该本来就是真的。这条测试同时验证了
 * [PdfTextExtractor.isMonospaceTextPosition] 读到的是真实字体数据，不是凭
 * 字体名字符串猜的。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorCodeBlockTest {

    @Test
    fun `Courier等宽字体的整行被判定为isMonospace`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage(PDRectangle(300f, 200f))
        document.addPage(page)
        val stream = PDPageContentStream(document, page)
        stream.beginText()
        stream.setFont(PDType1Font.COURIER, 12f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("val x = 1")
        stream.endText()
        stream.close()

        val file = File.createTempFile("courier-doc", ".pdf")
        file.deleteOnExit()
        document.save(file)
        document.close()

        val reopened = PDDocument.load(file)
        val stripper = PdfTextExtractor.LineCollectingStripper()
        stripper.getText(reopened)
        reopened.close()

        assertTrue("Courier 字体整行应该判定为等宽", stripper.lines.single().isMonospace)
    }

    @Test
    fun `Helvetica比例字体的整行不判定为isMonospace`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage(PDRectangle(300f, 200f))
        document.addPage(page)
        val stream = PDPageContentStream(document, page)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("This is normal text")
        stream.endText()
        stream.close()

        val file = File.createTempFile("helvetica-doc", ".pdf")
        file.deleteOnExit()
        document.save(file)
        document.close()

        val reopened = PDDocument.load(file)
        val stripper = PdfTextExtractor.LineCollectingStripper()
        stripper.getText(reopened)
        reopened.close()

        assertFalse("比例字体不该被判定为等宽", stripper.lines.single().isMonospace)
    }

    /**
     * [PdfTextExtractor.linesToParagraphs] 聚合层的纯逻辑测试——段内多数
     * （过半）行判定为等宽才算整段是代码块，用行数统计不用字符数统计，
     * 见该函数 KDoc"代码块识别"一节。这里直接构造 [PdfTextExtractor.Line]，
     * 不需要真实字体，跟其它 `linesToParagraphs`/`mergeSameLineRuns` 测试
     * 同一个风格。
     */
    @Test
    fun `段内多数行是等宽字体时整段判定为代码块`() {
        val lines = listOf(
            PdfTextExtractor.Line("val a = 1", 100f, 1, fontSize = 12f, isMonospace = true),
            PdfTextExtractor.Line("val b = 2", 114f, 1, fontSize = 12f, isMonospace = true),
            PdfTextExtractor.Line("return a + b", 128f, 1, fontSize = 12f, isMonospace = false),
        )

        val paragraphs = PdfTextExtractor.linesToParagraphs(lines)

        assertTrue("3 行里 2 行等宽，过半，应该判定为代码块", paragraphs.single().isCode)
    }

    @Test
    fun `段内少数行是等宽字体时整段不判定为代码块`() {
        val lines = listOf(
            PdfTextExtractor.Line("正文第一行", 100f, 1, fontSize = 12f, isMonospace = false),
            PdfTextExtractor.Line("正文第二行", 114f, 1, fontSize = 12f, isMonospace = false),
            // 偶尔一个变量名用了等宽字体排版，不该让整段被当代码块。
            PdfTextExtractor.Line("variableName", 128f, 1, fontSize = 12f, isMonospace = true),
        )

        val paragraphs = PdfTextExtractor.linesToParagraphs(lines)

        assertFalse("3 行里只有 1 行等宽，不过半，不该判定为代码块", paragraphs.single().isCode)
    }

    /**
     * [PdfTextExtractor.classifyHeadings] 对代码块段落恒定返回 0，即使字号
     * 凑巧偏大——见该函数 KDoc 和 [PdfTextExtractor.Paragraph.isCode] 的
     * "不变量"说明：代码块段落不该被当成标题加粗放大，两者是互斥的视觉处理。
     */
    @Test
    fun `代码块段落即使字号偏大也不判定为标题`() {
        val paragraphs = listOf(
            PdfTextExtractor.Paragraph("大字号代码块", page = 1, topY = 0f, fontSize = 24f, isBold = false, isCode = true),
            PdfTextExtractor.Paragraph("正文一", page = 1, topY = 30f, fontSize = 12f, isBold = false),
            PdfTextExtractor.Paragraph("正文二", page = 1, topY = 60f, fontSize = 12f, isBold = false),
        )

        val result = PdfTextExtractor.classifyHeadings(paragraphs)

        assertEquals(listOf(0, 0, 0), result)
    }
}
