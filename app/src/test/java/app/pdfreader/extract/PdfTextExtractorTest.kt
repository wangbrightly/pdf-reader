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
 * 测试用的 sample-chinese.pdf 是一个可提交进仓库的确定性 fixture，生成方式几经周折：
 *
 * 1. 最初用 macOS 自带的 textutil/cupsfilter 生成——**踩了坑**：这条系统打印管线给
 *    常见汉字（文/工/日/人/见/比/月……）逐字单独起一个文字对象，PdfBox 的行合并启发式
 *    应付不了这种排版，断行断得很怪；用 pdftotext -raw 交叉核对也一样乱，说明这是
 *    fixture 本身排版异常，不是抽取层的问题。
 * 2. 改用 Chromium（本机已装的 Puppeteer，`~/.claude-tools/webshot`）把一段 HTML
 *    打印成 PDF——这是"网页另存为 PDF"这条真实世界最常见的 PDF 来源之一，文字对象
 *    按正常语句连续排布，pdftotext -raw 交叉核实过排版正常。
 *
 * 用真实中文 PDF（用户桌面上的一份文档，未提交进仓库，只做过一次性人工核实）实测
 * 还发现了另一个独立问题并已在 [PdfTextExtractor] 里修了：PdfBox 对某些字体的
 * CID→Unicode 映射有歧义时会挑中"部首"码位而不是"汉字本字"码位（比如把"十"提取成
 * 康熙部首"⼗"），详见 [PdfTextExtractor] 类注释"已知问题"一节。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorTest {

    /**
     * 写入 fixture-source.txt 的原始文本，按空行切成的三段——断言的"期望值"依据。
     *
     * 2026-08-18 补：中文和数字/字母之间加了 [normalizeCjkSpacing]，这里的期望值已经
     * 按新规则更新（比如"工作。PDF阅读器"变成"工作。 PDF 阅读器"）——原始 fixture 文本
     * 本身没变，变的是抽取出来以后的规范化结果，详见 [PdfTextExtractor] 类注释。
     */
    private val knownParagraphs = listOf(
        "本文档用于测试中文文字提取功能是否正常工作。 PDF 阅读器项目需要验证从 PDF 文件中抽取的中文文字不会出现乱码或空白字符。",
        "测试内容包括常见汉字、标点符号（例如逗号、句号、问号？还有感叹号！）以及阿拉伯数字 12345 和百分号 100%。这段文字大约有一百五十字左右，涵盖了日常说明性文字的基本特征，比如时间、地点、人物、事件等要素。",
        "例如： 2026 年 8 月 17 日，工程师在北京完成了这份测试文档的编写工作，用来验证 PdfBox-Android 库对中文字体的解析能力是否可靠。",
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

        // 挑几个跨越标点、数字、常见汉字的片段做包含性断言（间距按 normalizeCjkSpacing
        // 规则更新过，见 knownParagraphs 上方注释）。
        assertTrue(fullText.contains("本文档用于测试中文文字提取功能"))
        assertTrue(fullText.contains("标点符号（例如逗号、句号、问号？还有感叹号！）"))
        assertTrue(fullText.contains("阿拉伯数字 12345 和百分号 100%"))
        assertTrue(fullText.contains("2026 年 8 月 17 日"))
        assertTrue(fullText.contains("PdfBox-Android 库对中文字体的解析能力是否可靠"))
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

    /**
     * 2026-08-20 真机 bug 回归测试——见 [PdfTextExtractor.mergeSameLineRuns] KDoc
     * 完整背景：一页里如果有很多"同一条视觉行被 PDFBox 拆成多个零 gap 片段"（中英
     * 文混排常见），[PdfTextExtractor.linesToParagraphs] 算出的中位数 gap 会被这些
     * 零 gap 拖成 0，导致真实的段内换行全部被误判成"另起一段"。
     *
     * 构造的数据形状照抄真机日志：39 行里有一大堆同 y 值的零 gap 片段（模拟中英文
     * 混排导致的字体切换），中间夹着几处真实的段内换行（gap≈31-43）——不合并同行
     * 片段的话，中位数会落在 0，这几处真实换行会被错误地切成独立段落。
     */
    @Test
    fun `同一视觉行被拆成多个零gap片段时不会把真实段内换行误判成分段`() {
        val lines = listOf(
            PdfTextExtractor.Line("我来翻译一下，20", 452.83f, 1),
            PdfTextExtractor.Line("年前贝索斯的亚马逊，年用", 452.83f, 1),
            PdfTextExtractor.Line("150", 452.83f, 1),
            PdfTextExtractor.Line("万，一个微信小程", 452.83f, 1),
            PdfTextExtractor.Line("序可能都比它的用户数多。", 484.03f, 1),
            PdfTextExtractor.Line("10", 484.03f, 1),
            PdfTextExtractor.Line("亿人民币，搁在今天，就是", 484.03f, 1),
            // 这里是真实的段内换行（gap≈31.2），不该被切开：
            PdfTextExtractor.Line("但是那一年，贝索斯写道：", 589.66f, 1),
            PdfTextExtractor.Line("为，一切都将围绕长期价值", 620.86f, 1),
            PdfTextExtractor.Line("战略愿景不一样，战略耐心", 652.06f, 1),
        )

        val paragraphs = PdfTextExtractor.linesToParagraphs(lines)

        assertTrue(
            "真实的段内换行不该被拆开——期望\"贝索斯写道：\"和\"为，一切都……\"在同一段里",
            paragraphs.any { it.text.contains("贝索斯写道：") && it.text.contains("为，一切都将围绕长期价值") },
        )
    }

    /**
     * [PdfTextExtractor.classifyHeadings] 的单元测试——用户明确选择的策略："字号
     * 明显偏大 或 字体本身加粗，两个信号满足一个就算标题"（见该函数 KDoc）。
     */
    @Test
    fun `字号明显大于本页中位数时判定为标题`() {
        val paragraphs = listOf(
            PdfTextExtractor.Paragraph("标题", page = 1, topY = 0f, fontSize = 24f, isBold = false),
            PdfTextExtractor.Paragraph("正文一", page = 1, topY = 30f, fontSize = 12f, isBold = false),
            PdfTextExtractor.Paragraph("正文二", page = 1, topY = 60f, fontSize = 12f, isBold = false),
        )

        val result = PdfTextExtractor.classifyHeadings(paragraphs)

        assertEquals(listOf(true, false, false), result)
    }

    @Test
    fun `字号跟正文差不多但标了加粗时也判定为标题`() {
        val paragraphs = listOf(
            PdfTextExtractor.Paragraph("加粗小标题", page = 1, topY = 0f, fontSize = 12f, isBold = true),
            PdfTextExtractor.Paragraph("正文一", page = 1, topY = 30f, fontSize = 12f, isBold = false),
            PdfTextExtractor.Paragraph("正文二", page = 1, topY = 60f, fontSize = 12f, isBold = false),
        )

        val result = PdfTextExtractor.classifyHeadings(paragraphs)

        assertEquals(listOf(true, false, false), result)
    }

    @Test
    fun `字号只是略大不到阈值倍数时不判定为标题`() {
        val paragraphs = listOf(
            // 12 * 1.15 = 13.8，13 没超过，不该判定为标题。
            PdfTextExtractor.Paragraph("略大一点", page = 1, topY = 0f, fontSize = 13f, isBold = false),
            PdfTextExtractor.Paragraph("正文一", page = 1, topY = 30f, fontSize = 12f, isBold = false),
            PdfTextExtractor.Paragraph("正文二", page = 1, topY = 60f, fontSize = 12f, isBold = false),
        )

        val result = PdfTextExtractor.classifyHeadings(paragraphs)

        assertEquals(listOf(false, false, false), result)
    }

    @Test
    fun `只有一个段落时没有对比基准，只能靠加粗信号`() {
        val bold = listOf(PdfTextExtractor.Paragraph("独占一页的加粗文字", page = 1, topY = 0f, fontSize = 12f, isBold = true))
        val notBold = listOf(PdfTextExtractor.Paragraph("独占一页的普通文字", page = 1, topY = 0f, fontSize = 12f, isBold = false))

        assertEquals(listOf(true), PdfTextExtractor.classifyHeadings(bold))
        assertEquals(listOf(false), PdfTextExtractor.classifyHeadings(notBold))
    }
}
