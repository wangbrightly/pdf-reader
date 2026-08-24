package app.pdfreader.extract

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 见 NOTES.md #14/#37：紧凑列表（目录/大纲，每项该独立一行）被 [PdfTextExtractor
 * .linesToParagraphs] 的段落合并启发式错误粘成一大段。原来的判断只看"相邻行 y
 * 间距是否明显大于本页典型行距"，分不清"一段话自然换行"和"一份紧凑列表、每项
 * 各自成行"——两者的行间距量级往往接近，纯 y 间距这一个信号不够用。
 *
 * `sample-compact-list.pdf` 是本机 Puppeteer（`~/.claude-tools/webshot`，跟
 * [PdfTextExtractorTest] 的 `sample-chinese.pdf` 同一套生成方式，不用 macOS
 * 自带的 textutil/cupsfilter，理由见该测试类顶部注释）打印一段 HTML 生成的：
 * 7 行紧凑列表（每行一个独立的目录条目，行宽差异很大——"第一章 绪论"很短，
 * "第五章 总结与展望"更长，但都远短于页面宽度）+ 2 段正常的自然语言段落（每段
 * 自动换行 3-6 行，除最后一行外，其余行都接近页面宽度）。正确的段落切分结果应该
 * 是 9 段：7 个列表项各自独立成段 + 2 个自然段落各自完整成段。
 */
@RunWith(RobolectricTestRunner::class)
class CompactListParagraphSplitTest {

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
    fun `紧凑列表每项独立成段，不跟相邻列表项合并`() {
        val context = RuntimeEnvironment.getApplication()
        val paragraphs = PdfTextExtractor.extractParagraphs(context, loadFixtureFile("sample-compact-list.pdf"))

        val expectedListItems = listOf(
            "第一章绪论",
            "第二章相关工作",
            "第三章系统设计",
            "第四章实验结果",
            "第五章总结与展望",
            "参考文献",
            "致谢",
        )
        assertEquals(
            "应该有 9 段（7 个列表项 + 2 个自然段落），实际=${paragraphs.size}：$paragraphs",
            9,
            paragraphs.size,
        )
        for ((index, expected) in expectedListItems.withIndex()) {
            assertEquals("第 ${index + 1} 个列表项不应该跟别的行合并", expected, paragraphs[index])
        }
    }

    @Test
    fun `紧凑列表后面的自然段落依然正常合并成一整段，不会被拆碎`() {
        val context = RuntimeEnvironment.getApplication()
        val paragraphs = PdfTextExtractor.extractParagraphs(context, loadFixtureFile("sample-compact-list.pdf"))

        // 第 8 段是第一个自然段落——断言它是连续的一整段（不含列表项的短文本），
        // 不是被自然换行的中间断点误切成好几小段。
        assertEquals(9, paragraphs.size)
        val firstNaturalParagraph = paragraphs[7]
        assertEquals(
            "本文档用于测试紧凑列表和正常段落是否会被段落切分逻辑正确区分开来。这一段是正常的自然语言段落，" +
                "句子会自动换行，行与行之间紧密相连，读起来应该是连续的一整段文字，不应该被切成好几段。" +
                "这段话故意写得比较长，是为了确保它在页面上至少占用三到四行的空间，这样才能真正测试出" +
                "\"段落内部自然换行\"和\"每行都是独立列表项\"这两种情况在视觉上的行间距是否接近，" +
                "从而验证新的分段逻辑能不能正确处理这两种截然不同的排版意图。",
            firstNaturalParagraph,
        )
        val secondNaturalParagraph = paragraphs[8]
        assertEquals(
            "这是第二个正常段落，同样是连续的自然语言，用来确认前一段结束、这一段开始的地方能被正确识别为" +
                "\"另起一段\"，不会被误判成列表的一部分，也不会跟前一段错误地合并在一起。",
            secondNaturalParagraph,
        )
    }
}
