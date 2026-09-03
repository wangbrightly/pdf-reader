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

    // ---- 2026-09-03 真机反馈修复"这本书里出现了很多单行的'o'"（HFSS.pdf，跟同一次
    // 会话里"l"的 Wingdings 图标字体问题不是同一回事，这次是 Courier New 字体，
    // 作者真的手打了字母 o 当项目符号）——见 PdfTextExtractor.isBulletMarker /
    // linesToParagraphs KDoc"2026-09-03 补丁"一节完整背景。以下坐标全部取自真机
    // HFSS.pdf 第 120 页的真实提取数据（612pt 宽 letter 页面），不是构造的数字 ----

    private fun line(y: Float, startX: Float, endX: Float, text: String, fontSize: Float = 14f) =
        PdfTextExtractor.Line(text, y, 120, fontSize = fontSize, startX = startX, endX = endX, pageWidth = 612f)

    /**
     * 真机复现：第一个 `o` 项目符号（y=509.82，宽度只有 4.5pt）后面跟着一句会
     * 换行成两行的长内容——这一条在改动前就能正常合并（`isShortLine` 对长内容
     * 那一行本来就是 false，不满足"连续两行都短"），改动后不该变化，是回归
     * 保护，不是新增能力。
     */
    @Test
    fun `项目符号后面接会换行的长内容时正常合并（改动前后行为一致）`() {
        val lines = listOf(
            line(509.82f, 106.9f, 111.4f, "o", fontSize = 10f),
            line(512.83f, 119.3f, 532.0f, "Draw a segmented version of the ogive geometry using an equation based curve as its"),
            line(527.08f, 119.3f, 144.9f, "basis"),
        )
        val paragraphs = PdfTextExtractor.linesToParagraphs(lines)
        assertEquals("三行应该合并成一段", 1, paragraphs.size)
        assertEquals(
            "o Draw a segmented version of the ogive geometry using an equation based curve as its basis",
            paragraphs[0].text,
        )
    }

    /**
     * 真机反馈的真正 bug：`o` 项目符号后面接的内容如果本身能塞进一行（不需要
     * 换行），"符号"和"内容"就都满足 [PdfTextExtractor] 内 `isShortLine`，被
     * 旧逻辑当成"连续两个短列表项"切开，读起来变成孤零零的"o"单独一行。
     * 用真机第二个符号原样的坐标复现（y=541.32/544.33）。
     */
    @Test
    fun `项目符号后面接单行短内容时不再被错误切开`() {
        val lines = listOf(
            line(509.82f, 106.9f, 111.4f, "o", fontSize = 10f),
            line(512.83f, 119.3f, 532.0f, "Draw a segmented version of the ogive geometry using an equation based curve as its"),
            line(527.08f, 119.3f, 144.9f, "basis"),
            line(541.32f, 106.9f, 111.4f, "o", fontSize = 10f),
            line(544.33f, 119.3f, 251.3f, "Add an incident plane wave"),
        )
        val paragraphs = PdfTextExtractor.linesToParagraphs(lines)
        assertEquals(
            "应该是两段（两个列表项各自完整），实际=${paragraphs.size}：${paragraphs.map { it.text }}",
            2,
            paragraphs.size,
        )
        assertEquals("o Add an incident plane wave", paragraphs[1].text)
    }

    /**
     * 真机完整 6 项列表复现（HFSS.pdf 第 120 页第二个符号开始的全部 6 项，
     * 每一项内容都能塞进一行）——确认修复后每一项都正确带着自己的符号独立
     * 成段，列表项之间该有的边界（符号跟"上一项"之间）依然正常切开，不是
     * "干脆全部合并成一大段"这种矫枉过正的副作用。
     */
    @Test
    fun `连续多个项目符号+单行内容 每项独立成段且都带着自己的符号`() {
        val lines = listOf(
            line(541.32f, 106.9f, 111.4f, "o", fontSize = 10f),
            line(544.33f, 119.3f, 251.3f, "Add an incident plane wave"),
            line(558.57f, 106.9f, 111.4f, "o", fontSize = 10f),
            line(561.58f, 119.3f, 217.1f, "Assign an IE Region"),
            line(575.82f, 106.9f, 111.4f, "o", fontSize = 10f),
            line(578.83f, 119.3f, 298.4f, "Specify solution setting for the design"),
            line(593.07f, 106.9f, 111.4f, "o", fontSize = 10f),
            line(596.08f, 119.3f, 240.9f, "Run the HFSS simulation"),
            line(610.32f, 106.9f, 111.4f, "o", fontSize = 10f),
            line(613.33f, 119.3f, 305.7f, "Create a current overlay and animate it"),
            line(627.57f, 106.9f, 111.4f, "o", fontSize = 10f),
            line(630.58f, 119.3f, 262.3f, "Create a monostatic RCS plot"),
        )
        val paragraphs = PdfTextExtractor.linesToParagraphs(lines)
        val texts = paragraphs.map { it.text }
        assertEquals(
            listOf(
                "o Add an incident plane wave",
                "o Assign an IE Region",
                "o Specify solution setting for the design",
                "o Run the HFSS simulation",
                "o Create a current overlay and animate it",
                "o Create a monostatic RCS plot",
            ),
            texts,
        )
    }

    /**
     * 反例：确认这次修复没有让真正的紧凑列表（普通短语，不是项目符号）退化——
     * 复用类顶部注释描述的 `sample-compact-list.pdf` 那组真机校准数据规律
     * （列表项 widthRatio 0.10~0.25），用两个"正常长度的短列表项"（不是单字符
     * 符号）构造，确认它们依然会被 [PdfTextExtractor.isBulletMarker] 排除在外
     * （宽度远超 [PdfTextExtractor] 内 `BULLET_MARKER_MAX_WIDTH_PT`=15pt），
     * 该切开的边界还是会正常切开。
     */
    @Test
    fun `两个真正的短列表项（不是符号）之间依然正常切开`() {
        val lines = listOf(
            line(100f, 60f, 160f, "第一章绪论"), // widthRatio (160-60)/612=0.163，真机同一量级
            line(120f, 60f, 200f, "第二章相关工作"), // widthRatio (200-60)/612=0.229
        )
        val paragraphs = PdfTextExtractor.linesToParagraphs(lines)
        assertEquals("两个短列表项应该保持独立成段，不该被新的符号例外误伤", 2, paragraphs.size)
    }
}
