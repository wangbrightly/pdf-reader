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
 * PdfTextExtractor 的单元测试。
 *
 * 依赖 Android Context（PDFBoxResourceLoader.init(context)），纯 JVM 测试拿不到真实
 * Context，所以用 Robolectric 在 JVM 上模拟一个（@RunWith(RobolectricTestRunner)）。
 *
 * 测试用的 sample-chinese.pdf 是一个可提交进仓库的确定性 fixture：用 macOS 自带的
 * textutil + cupsfilter（本机 textutil 版本没有直接的 -convert pdf 选项，改走
 * "txt → rtf → 系统打印管线生成 PDF" 的等价路径）从下面 [KNOWN_PARAGRAPHS] 对应的
 * 已知中文文本生成，已用 pdftotext -raw 交叉核实过：字符完整、顺序正确、
 * 不含 U+FFFD 替换字符（详见任务报告）。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorTest {

    /** 写入 fixture-source.txt 的原始文本，按空行切成的三段——断言的“期望值”依据。 */
    private val knownParagraphs = listOf(
        "本文档用于测试中文文字提取功能是否正常工作。PDF阅读器项目需要验证从PDF文件中抽取的中文文字不会出现乱码或空白字符。",
        "测试内容包括常见汉字、标点符号（例如逗号、句号、问号？还有感叹号！）以及阿拉伯数字12345和百分号100%。这段文字大约有一百五十字左右，涵盖了日常说明性文字的基本特征，比如时间、地点、人物、事件等要素。",
        "例如：2026年8月17日，工程师在北京完成了这份测试文档的编写工作，用来验证PdfBox-Android库对中文字体的解析能力是否可靠。",
    )

    private fun extractFixtureParagraphs(): List<String> {
        val context = RuntimeEnvironment.getApplication()
        val resourceStream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("sample-chinese.pdf")
        ) { "找不到测试 fixture：src/test/resources/sample-chinese.pdf" }

        val tempFile = File.createTempFile("sample-chinese", ".pdf")
        tempFile.deleteOnExit()
        resourceStream.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }

        return PdfTextExtractor.extractParagraphs(context, tempFile)
    }

    @Test
    fun `抽取出的段落数量与已知段落数一致`() {
        val paragraphs = extractFixtureParagraphs()
        assertEquals(knownParagraphs.size, paragraphs.size)
    }

    @Test
    fun `抽取出的文字包含已知中文片段`() {
        val paragraphs = extractFixtureParagraphs()
        val fullText = paragraphs.joinToString("")

        // 挑几个跨越标点、数字、常见汉字的片段做包含性断言。
        assertTrue(fullText.contains("本文档用于测试中文文字提取功能"))
        assertTrue(fullText.contains("标点符号（例如逗号、句号、问号？还有感叹号！）"))
        assertTrue(fullText.contains("阿拉伯数字12345和百分号100%"))
        assertTrue(fullText.contains("2026年8月17日"))
        assertTrue(fullText.contains("PdfBox-Android库对中文字体的解析能力是否可靠"))
    }

    @Test
    fun `抽取出的段落与已知段落逐段完全一致`() {
        val paragraphs = extractFixtureParagraphs()
        assertEquals(knownParagraphs, paragraphs)
    }

    @Test
    fun `抽取结果不包含乱码替换字符`() {
        val paragraphs = extractFixtureParagraphs()
        val fullText = paragraphs.joinToString("")

        assertFalse("提取结果里出现了 U+FFFD 替换字符，说明命中了 CJK ToUnicode CMap 缺失的已知问题", fullText.contains('�'))
    }
}
