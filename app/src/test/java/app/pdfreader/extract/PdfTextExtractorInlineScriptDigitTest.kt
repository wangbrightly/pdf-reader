package app.pdfreader.extract

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 行内上标/下标（同一次 `writeString` 回调内嵌数字）端到端测试——见
 * [PdfTextExtractor] 类里 `absorbInlineScriptDigits` KDoc 完整背景。
 *
 * 2026-09-07 真机诊断（120 页真实学术书）发现 [PdfTextExtractor.absorbSuperscriptSubscriptRuns]
 * （2026-09-06 新增）架构上永远不会触发：那个函数比较**相邻两个 Line**，但真实排版
 * 软件生成脚注上标用的是 PDF 的 Text Rise（`Ts`）操作符，不移动文本行起点，PdfBox
 * 自己的 `writeString` 视觉行分组不会因此拆行——"正文 + 上标 + 后续正文"整个落在
 * **同一次 `writeString` 回调**里，`textPositions` 混着两种字号，根本不会产生"两个
 * 相邻 Line"这个 [absorbSuperscriptSubscriptRuns] 依赖的前提。
 *
 * 用 `setTextRise` 构造 fixture 而不是 `newLineAtOffset`——这是唯一能在合成 PDF 里
 * 复现"同一次 writeString 回调"这个真机现象的方式（一次性诊断
 * `SuperscriptRiseDiagnosticTest`/`SuperscriptGroupingDiagnosticTest` 已确认过，
 * `newLineAtOffset` 哪怕只挪几个 pt 也会被 PdfBox 自己的换行判定拆成独立 Line，跟
 * 真机数据的分组方式不一致；已删除，结论固化在这里）。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorInlineScriptDigitTest {

    private fun extract(build: PDPageContentStream.() -> Unit): List<String> {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage(PDRectangle(300f, 200f))
        document.addPage(page)
        val stream = PDPageContentStream(document, page)
        stream.build()
        stream.close()

        val file = File.createTempFile("inline-script-digit-doc", ".pdf")
        file.deleteOnExit()
        document.save(file)
        document.close()

        return PdfTextExtractor.extractContent(context, file).paragraphs
    }

    @Test
    fun `TextRise构造的行内脚注上标数字转换成Unicode上标字符`() {
        val paragraphs = extract {
            beginText()
            setFont(PDType1Font.HELVETICA, 12f)
            newLineAtOffset(20f, 150f)
            showText("Body")
            setTextRise(4f)
            setFont(PDType1Font.HELVETICA, 7f)
            showText("6")
            setTextRise(0f)
            setFont(PDType1Font.HELVETICA, 12f)
            showText(" continues")
            endText()
        }
        assertEquals(listOf("Body⁶ continues"), paragraphs)
    }

    @Test
    fun `TextRise向下偏移的行内数字转换成Unicode下标字符`() {
        val paragraphs = extract {
            beginText()
            setFont(PDType1Font.HELVETICA, 12f)
            newLineAtOffset(20f, 150f)
            showText("H")
            setTextRise(-3f)
            setFont(PDType1Font.HELVETICA, 7f)
            showText("2")
            setTextRise(0f)
            setFont(PDType1Font.HELVETICA, 12f)
            showText("O")
            endText()
        }
        assertEquals(listOf("H₂O"), paragraphs)
    }

    /**
     * 反例：抬升游程里混了非数字字符（脚注符号"*"常跟编号连在一起）——见
     * `absorbInlineScriptDigits` KDoc"跟 [absorbSuperscriptSubscriptRuns] 同一条
     * 保守原则"——宁可整个游程不转换，也不做部分转换，退回原样数字+符号。
     */
    @Test
    fun `抬升游程混了非数字字符时整个游程不转换`() {
        val paragraphs = extract {
            beginText()
            setFont(PDType1Font.HELVETICA, 12f)
            newLineAtOffset(20f, 150f)
            showText("Note")
            setTextRise(4f)
            setFont(PDType1Font.HELVETICA, 7f)
            showText("*6")
            setTextRise(0f)
            setFont(PDType1Font.HELVETICA, 12f)
            showText(" end")
            endText()
        }
        assertEquals(listOf("Note*6 end"), paragraphs)
    }

    /**
     * 反例：字号一致、没有 Text Rise 的普通一行文字里的数字——回归安全网，确认这次
     * 修复不会把行内任何数字都当上标处理。
     */
    @Test
    fun `没有TextRise偏移的普通数字不会被转换`() {
        val paragraphs = extract {
            beginText()
            setFont(PDType1Font.HELVETICA, 12f)
            newLineAtOffset(20f, 150f)
            showText("There are 42 items")
            endText()
        }
        assertEquals(listOf("There are 42 items"), paragraphs)
    }
}
