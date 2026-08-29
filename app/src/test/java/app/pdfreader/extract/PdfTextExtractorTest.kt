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
     * 2026-08-28 真机反馈修复（排查"图片和文字分开了"时顺带发现的另一个问题）——
     * 见 [PdfTextExtractor.mergeSameLineRuns] KDoc 完整背景。数据照抄真机日志：
     * 一份两栏排版文档，左栏标题"1/2 分频器"和右栏标题"CMOS PLL 合成器"落在
     * 完全相同的 y 高度（142.48…，只是四舍五入误差），原来的合并逻辑只看 y
     * 相同就直接拼接，会把两栏内容错误粘连成一条"1/2 分频器CMOS PLL 合成器"。
     */
    @Test
    fun `y坐标相同但x坐标离得很远的两栏标题不会被错误合并成一行（真机两栏排版反例）`() {
        val lines = listOf(
            PdfTextExtractor.Line("1/2 分频器", 142.48895f, 7, startX = 63.1604f, endX = 98.82032f, pageWidth = 595.276f),
            PdfTextExtractor.Line(
                "CMOS PLL 合成器", 142.48553f, 7, startX = 301.85703f, endX = 362.71622f, pageWidth = 595.276f,
            ),
        )

        val merged = PdfTextExtractor.mergeSameLineRuns(lines)

        assertEquals("两栏标题应该保持独立，不该被拼成一行", 2, merged.size)
        assertEquals("1/2 分频器", merged[0].text)
        assertEquals("CMOS PLL 合成器", merged[1].text)
    }

    /**
     * 正向对照：同一份真机文档里真实存在的、因为中英文混排字体切换产生的正常
     * 段内片段（"对"|"1.5GHz 带高速锁定"|"PLL 合成器锁定前的情况进行瞬态分析，"，
     * 间隙实测 1.86~3.72pt），确认新加的 x 坐标距离检查不会误伤这种正常场景——
     * 只拦真正的跨栏，不拦同一栏内的正常字体切换。
     */
    @Test
    fun `正常字体切换产生的小间隙片段仍然合并成一行（真机中英文混排正例）`() {
        val lines = listOf(
            PdfTextExtractor.Line("对", 154.83661f, 7, startX = 897.1342f, endX = 904.5742f, pageWidth = 595.276f),
            PdfTextExtractor.Line(
                "1.5GHz 带高速锁定", 154.83661f, 7, startX = 908.29425f, endX = 973.1078f, pageWidth = 595.276f,
            ),
            PdfTextExtractor.Line(
                "PLL 合成器锁定前的情况进行瞬态分析，",
                154.83661f,
                7,
                startX = 976.82855f,
                endX = 1115.7203f,
                pageWidth = 595.276f,
            ),
        )

        val merged = PdfTextExtractor.mergeSameLineRuns(lines)

        assertEquals("正常的段内字体切换片段应该合并成一行", 1, merged.size)
        assertEquals("对1.5GHz 带高速锁定PLL 合成器锁定前的情况进行瞬态分析，", merged[0].text)
    }

    // ---- hasColumnGap：2026-08-28 真机反馈（"图片和文字分开了"，用户拍板不重排、识别到就整页栅格化）----

    /**
     * 真机"RF 电路"页的坐标形状简化版：左栏 6 行（X 落在 50~270 区间），右栏
     * 6 行（X 落在 300~575 区间），中间留出约 30pt 的空白带——两边行数都远超
     * [PdfTextExtractor] 里 `MIN_COLUMN_LINES_PER_SIDE`（真机实测两侧各 17/18
     * 行，这里用 6 行简化但仍然远超门槛），应该判定为两栏。
     */
    @Test
    fun `两栏排版、中间有干净空白带、两边行数都足够时判定为两栏`() {
        val leftLines = (0 until 6).map { i ->
            PdfTextExtractor.Line("左栏第${i}行", 100f + i * 20f, 1, startX = 50f, endX = 270f, pageWidth = 595.276f)
        }
        val rightLines = (0 until 6).map { i ->
            PdfTextExtractor.Line("右栏第${i}行", 100f + i * 20f, 1, startX = 300f, endX = 575f, pageWidth = 595.276f)
        }
        assertTrue(PdfTextExtractor.hasColumnGap(leftLines + rightLines))
    }

    /**
     * 真机"天线设计"页的已修复案例：页面顶部有一句跨越几乎整个内容宽度的
     * 介绍段落（真机实测宽度占内容区 78%），这句话"骑"在两栏分界线上，2026-08-29
     * 之前会让"全页无缺口"、判定不是两栏（曾经记录成已知局限，用户真机使用
     * 时撞上、反馈"明明是两栏文字却被硬拆开重排了"后重新评估，见
     * [PdfTextExtractor.hasColumnGap] KDoc"2026-08-29 修复"一节）——这条介绍
     * 段落宽度远超正常两栏行宽（78% vs 37%~46%），现在会被
     * [PdfTextExtractor.OUTLIER_LINE_WIDTH_RATIO] 这层过滤排除在区间合并之外，
     * 不再堵住真正的分栏缝隙。
     */
    @Test
    fun `有一行跨越两栏分界线时仍然判定为两栏（异常宽行不参与区间合并）`() {
        val bridgingLine = PdfTextExtractor.Line("跨两栏的介绍段落", 80f, 1, startX = 60f, endX = 560f, pageWidth = 595.276f)
        val leftLines = (0 until 6).map { i ->
            PdfTextExtractor.Line("左栏第${i}行", 120f + i * 20f, 1, startX = 50f, endX = 270f, pageWidth = 595.276f)
        }
        val rightLines = (0 until 6).map { i ->
            PdfTextExtractor.Line("右栏第${i}行", 120f + i * 20f, 1, startX = 300f, endX = 575f, pageWidth = 595.276f)
        }
        assertTrue(PdfTextExtractor.hasColumnGap(listOf(bridgingLine) + leftLines + rightLines))
    }

    /**
     * 跨栏介绍段落多到把两栏正文本身也挤到门槛以下时，[PdfTextExtractor
     * .OUTLIER_LINE_WIDTH_RATIO] 这层过滤没法把"漏检"变成"零漏检"——过滤后
     * 剩下的行数不够 [PdfTextExtractor.hasColumnGap] 判定两栏所需的最小行数
     * 时，仍然按"不是两栏"处理，风险模型跟之前一致：漏检不会让页面变得比
     * 现在更差，这条测试确认这种极端情况不会误判或抛异常。
     */
    @Test
    fun `跨栏介绍段落过多、过滤后正文行数不够时仍然不判定为两栏`() {
        val bridgingLines = (0 until 3).map { i ->
            PdfTextExtractor.Line("跨两栏介绍第${i}行", 60f + i * 20f, 1, startX = 60f, endX = 560f, pageWidth = 595.276f)
        }
        val leftLines = (0 until 2).map { i ->
            PdfTextExtractor.Line("左栏第${i}行", 140f + i * 20f, 1, startX = 50f, endX = 270f, pageWidth = 595.276f)
        }
        val rightLines = (0 until 2).map { i ->
            PdfTextExtractor.Line("右栏第${i}行", 140f + i * 20f, 1, startX = 300f, endX = 575f, pageWidth = 595.276f)
        }
        assertFalse(PdfTextExtractor.hasColumnGap(bridgingLines + leftLines + rightLines))
    }

    // ---- hasScatteredLayout：2026-08-29 真机反馈"复杂的分栏页面直接显示为图片，
    // 不需要再分开显示"，排查后发现比 hasColumnGap 覆盖的场景更宽——真机"天线设计"
    // 页实际是"标题并排+目录网格"混合版式，不是 hasColumnGap 假设的"长文章两栏"
    // 形状。第一版按未合并的原始坐标数据数出"5 组同 Y 碰撞"是错的（把同一句话被
    // PDFBox 拆出的正常碎片误当成独立并排内容），加诊断在真实调用路径上（先调
    // mergeSameLineRuns 再统计）重新实测才拿到准确数字，这里的测试数据是合并后
    // 的真实文本+坐标，不是合并前的原始碎片 ----

    /**
     * 真机"天线设计"页合并后的真实数据（35 行原始碎片合并成 26 行后的结果，
     * 这里只保留 4 组碰撞 + 2 条无碰撞的行凑够 [PdfTextExtractor] 内
     * `MIN_COLUMN_LINES_PER_SIDE`×2=10 这个最小行数门槛）：y~586/599/610 是
     * 三组真实的两栏标题并排（"天线之间隐性干扰的可视化"/"考虑所有环境的天线
     * 分析"这类），y~792 是页码"5"和页脚"//电子设计解决方案"巧合对齐在同一行
     * （不是版式意图，但真机数据里确实存在，阈值校准时把它算在内）——四组
     * 加起来精确等于 [PdfTextExtractor] 内 `MIN_OVERLAPPING_Y_GROUPS`=4，应该
     * 命中分散版式判定。
     */
    @Test
    fun `真机天线设计页合并后的4组Y碰撞判定为分散版式`() {
        fun line(y: Float, startX: Float, endX: Float, text: String) =
            PdfTextExtractor.Line(text, y, 6, startX = startX, endX = endX, pageWidth = 595.276f)
        val lines = listOf(
            line(586f, 91f, 180f, "天线之间隐性干扰的可视化"),
            line(586f, 310f, 392f, "考虑所有环境的天线分析"),
            line(599f, 91f, 276f, "EMIT采用独特的多保真度方法"),
            line(599f, 310f, 544f, "先进的天线环境仿真软件HFSS SBR+"),
            line(610f, 91f, 263f, "并迅速识别复杂射频环境中的问题"),
            line(610f, 310f, 544f, "安装的天线的方向图、近场及天线间的耦合"),
            // 页码和页脚的真实 Y 值差 0.166（791.757 vs 791.591）——写成完全相同的
            // 792f 会让 mergeSameLineRuns 误判成"同一行"直接合并掉，反而测不出
            // 这条巧合碰撞；用真机实测的两个不同 Y 值，四舍五入到同一个整数桶
            // （[PdfTextExtractor] 内 hasScatteredLayout 自己的分组逻辑）但不满足
            // mergeSameLineRuns 的"同一行"判定（阈值 0.01），行为才跟真机一致。
            line(791.757f, 20f, 24f, "5"),
            line(791.591f, 37f, 98f, "//电子设计解决方案"),
            line(113f, 79f, 120f, "天线设计"),
            line(135f, 79f, 543f, "不仅可以单独分析天线"),
        )
        assertTrue(PdfTextExtractor.hasScatteredLayout(lines))
    }

    /**
     * 反例：去掉真机数据里那组页码/页脚巧合碰撞（y~792 那两行），只剩 3 组
     * 真实内容碰撞——低于门槛，不该判定为分散版式。用来确认阈值不是形同虚设。
     */
    @Test
    fun `去掉页码页脚巧合碰撞后只剩3组时不判定为分散版式`() {
        fun line(y: Float, startX: Float, endX: Float, text: String) =
            PdfTextExtractor.Line(text, y, 6, startX = startX, endX = endX, pageWidth = 595.276f)
        val lines = listOf(
            line(586f, 91f, 180f, "天线之间隐性干扰的可视化"),
            line(586f, 310f, 392f, "考虑所有环境的天线分析"),
            line(599f, 91f, 276f, "EMIT采用独特的多保真度方法"),
            line(599f, 310f, 544f, "先进的天线环境仿真软件HFSS SBR+"),
            line(610f, 91f, 263f, "并迅速识别复杂射频环境中的问题"),
            line(610f, 310f, 544f, "安装的天线的方向图、近场及天线间的耦合"),
            line(113f, 79f, 120f, "天线设计"),
            line(135f, 79f, 543f, "不仅可以单独分析天线"),
            line(200f, 79f, 300f, "填充行一"),
            line(220f, 79f, 300f, "填充行二"),
        )
        assertFalse(PdfTextExtractor.hasScatteredLayout(lines))
    }

    /**
     * 正向对照：真机干净的两栏正文（左右两栏每一行都对齐到相同的 Y 高度）
     * 天然也会命中这个更通用的信号——两个函数在这种场景上结论一致，不冲突。
     */
    @Test
    fun `干净的两栏正文也会被判定为分散版式（跟hasColumnGap结论一致）`() {
        val leftLines = (0 until 6).map { i ->
            PdfTextExtractor.Line("左栏第${i}行", 100f + i * 20f, 1, startX = 50f, endX = 270f, pageWidth = 595.276f)
        }
        val rightLines = (0 until 6).map { i ->
            PdfTextExtractor.Line("右栏第${i}行", 100f + i * 20f, 1, startX = 300f, endX = 575f, pageWidth = 595.276f)
        }
        assertTrue(PdfTextExtractor.hasScatteredLayout(leftLines + rightLines))
    }

    /**
     * 假阳性防护：只是偶然出现一两处 Y 恰好相同（比如页眉和正文第一行凑巧对齐），
     * 不该被当成"分散版式"——真机单栏正文页实测 0 组，这里给 2 组留安全边际，
     * 门槛（[PdfTextExtractor] 内 `MIN_OVERLAPPING_Y_GROUPS`=5）远高于偶发情况。
     */
    @Test
    fun `只有一两处偶然的Y坐标重合时不判定为分散版式`() {
        val lines = (0 until 12).map { i ->
            PdfTextExtractor.Line("正文第${i}行", 100f + i * 20f, 1, startX = 50f, endX = 400f, pageWidth = 595.276f)
        } + listOf(
            PdfTextExtractor.Line("页眉", 100f, 1, startX = 500f, endX = 520f, pageWidth = 595.276f),
            PdfTextExtractor.Line("页码", 120f, 1, startX = 500f, endX = 510f, pageWidth = 595.276f),
        )
        assertFalse(PdfTextExtractor.hasScatteredLayout(lines))
    }

    /**
     * 真机数据里的假阳性反例：一页散落的图注文字，凑巧留出一个孤立的小缺口
     * （真机实测约 13.6pt，来自一个孤零零的页码），但缺口一边只有 1 行文字，
     * 远低于 [PdfTextExtractor] 里的 `MIN_COLUMN_LINES_PER_SIDE`——两边都要
     * 有足够多行文字才算真的两栏，孤立元素造成的缺口不该被当成栏间距。
     */
    @Test
    fun `缺口一侧只有孤立的一两行文字时不判定为两栏`() {
        val isolatedLine = PdfTextExtractor.Line("孤立页码", 700f, 1, startX = 20f, endX = 24f, pageWidth = 595.276f)
        val restLines = (0 until 8).map { i ->
            PdfTextExtractor.Line("散落图注${i}", 120f + i * 20f, 1, startX = 40f, endX = 550f, pageWidth = 595.276f)
        }
        assertFalse(PdfTextExtractor.hasColumnGap(listOf(isolatedLine) + restLines))
    }

    @Test
    fun `没有明显缺口的单栏正文不判定为两栏`() {
        val lines = (0 until 8).map { i ->
            PdfTextExtractor.Line("正文第${i}行", 100f + i * 20f, 1, startX = 50f, endX = 500f + i, pageWidth = 595.276f)
        }
        assertFalse(PdfTextExtractor.hasColumnGap(lines))
    }

    @Test
    fun `pageWidth为0（旧数据没有坐标信息）时不判定为两栏`() {
        val lines = (0 until 12).map { PdfTextExtractor.Line("行$it", 100f + it * 20f, 1) }
        assertFalse(PdfTextExtractor.hasColumnGap(lines))
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
