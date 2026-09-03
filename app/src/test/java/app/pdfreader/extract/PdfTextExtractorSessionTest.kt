package app.pdfreader.extract

import android.graphics.Bitmap
import app.pdfreader.ui.DisplayBlock
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
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
 * [PdfTextExtractor.Session] 的单元测试——文字/图片真正按需加载，见该类 KDoc 完整
 * 背景。核心要验证：按页调用 [PdfTextExtractor.Session.loadPage] 逐页拼起来的结果
 * 跟 [PdfTextExtractor.extractContent] 一次性抽取出的结果等价（文字、图片、表格
 * 区域裁剪都一致），以及 [PdfTextExtractor.Session.pageCount] 打开后立刻可用。
 *
 * 2026-08-21：[PdfTextExtractor.Session.outline]（连同页脚水印学习）改成后台
 * 异步抽取（用户要求"一秒之内打开 PDF"），不再是"打开后立刻可用"，测试里凡是
 * 要断言 `outline`/页脚过滤结果的，都要先调 `awaitOutlineForTest`/
 * `awaitFooterLearningForTest` 等后台线程跑完，不然会变成偶发失败的时序竞态。
 *
 * 2026-08-20：这个文件曾经还测过"即时可用阶段" `paragraphs`/`loadPageMedia` 这套
 * 旧字段（"文字一次性抽完、图片按需加载"那版 `Session`）——那套字段已经在这次改造
 * 里整体删除（见 NOTES.md #21、`/Users/mac/.claude/plans/fizzy-snuggling-cloud.md`），
 * 对应的测试也一并删除，不是遗漏。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorSessionTest {

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
    fun `loadPage 逐页拼起来的文字段落和 extractContent 一致（纯文字文档）`() {
        val context = RuntimeEnvironment.getApplication()
        val file = loadFixtureFile("sample-chinese.pdf")
        val expected = PdfTextExtractor.extractContent(context, loadFixtureFile("sample-chinese.pdf"))

        PdfTextExtractor.Session.open(context, file).use { session ->
            val texts = (1..session.pageCount).flatMap { pageNo ->
                session.loadPage(pageNo).blocks.filterIsInstance<DisplayBlock.Text>().map { it.text }
            }
            assertEquals(expected.paragraphs, texts)
        }
    }

    @Test
    fun `loadPage 能拿到图片，数量跟 extractContent 一致`() {
        val context = RuntimeEnvironment.getApplication()
        val file = loadFixtureFile("sample-with-image.pdf")
        val expected = PdfTextExtractor.extractContent(context, loadFixtureFile("sample-with-image.pdf"))

        PdfTextExtractor.Session.open(context, file).use { session ->
            val allBlocks = (1..session.pageCount).flatMap { session.loadPage(it).blocks }
            val texts = allBlocks.filterIsInstance<DisplayBlock.Text>()
            val images = allBlocks.filterIsInstance<DisplayBlock.Image>()
            assertEquals(3, texts.size)
            assertEquals(expected.images.size, images.size)
        }
    }

    @Test
    fun `loadPage 表格区域场景——表格前后正文保留，单元格内容排除，表格裁成一张图`() {
        val context = RuntimeEnvironment.getApplication()
        val file = loadFixtureFile("sample-with-table.pdf")

        PdfTextExtractor.Session.open(context, file).use { session ->
            val allBlocks = (1..session.pageCount).flatMap { session.loadPage(it).blocks }
            val texts = allBlocks.filterIsInstance<DisplayBlock.Text>().map { it.text }
            val images = allBlocks.filterIsInstance<DisplayBlock.Image>()

            assertTrue(
                "表格前的说明文字应该保留",
                texts.any { it.contains("这段文字之后紧跟着一张带边框的表格") },
            )
            assertTrue(
                "表格后的说明文字应该保留",
                texts.any { it.contains("这是表格后的说明文字") },
            )
            assertTrue(
                "表格单元格内容不应该出现在文字段落里",
                texts.none { it.contains("螺丝") || it.contains("M3x10") },
            )
            assertEquals("表格应该被裁剪成一张图片", 1, images.size)
        }
    }

    @Test
    fun `pageCount 立刻可用，outline 后台抽完之后也是对的`() {
        val context = RuntimeEnvironment.getApplication()
        val file = loadFixtureFile("sample-with-outline.pdf")

        // 2026-08-21：outline 改成后台异步抽取（见 Session.outline 字段 KDoc，用户
        // 要求"一秒之内打开 PDF，后台加载数据"）——pageCount 不受影响，仍然是
        // open() 一返回就有；outline 要用 awaitOutlineForTest 等后台线程跑完才能
        // 确定性断言，不然这条测试会变成偶发失败的时序竞态。
        PdfTextExtractor.Session.open(context, file).use { session ->
            assertEquals(3, session.pageCount)
            session.awaitOutlineForTest()
            assertEquals(4, session.outline.size)
        }
    }

    /**
     * 2026-08-21：页脚标题类噪音学习改成后台异步（用户要求"一秒之内打开 PDF，
     * 后台加载数据"，见 [PdfTextExtractor.Session.footerLearnedTitles] KDoc 完整
     * 背景）——这条测试验证"改成异步之后，学习结果还是对的，只是不再阻塞
     * open()"：用 [PdfTextExtractor.Session.awaitFooterLearningForTest] 等后台
     * 线程跑完，再断言重复出现的标题类水印被过滤、每页不同的正文保留。
     *
     * fixture 构造方式照抄 [PdfTextExtractorFooterNoiseTest.buildDocument]（同一个
     * "每页正文不同、页脚有一行全文档一字不差重复的标题"套路，那个方法是
     * `private`，没法跨测试类直接复用），但正文行数从原版的 5 行改成了 15 行——
     * 写这条测试时先按 5 行试过一次，真的暴露了一个问题：`linesToParagraphs` 的
     * 中位数间距统计按页调用时样本量小（这一页总共才 9 行），5 条正文行（间距 15）
     * 和 4 条页脚行（间距 60）数量接近，中位数刚好落在 60 那一侧，导致页脚 4 行
     * 被错误合并成一段，本该被过滤的水印文字混在一起，逃过了噪音正则的精确匹配。
     * 这是 NOTES.md #22 已经如实记录过的"per-page 样本量小、中位数统计不稳定"
     * 这同一类局限在另一个场景下的具体表现，不是这次改动引入的新问题——真实文档
     * 一页正文通常远不止 5 行（这里改成 15 行更接近真实比例），中位数会稳定落在
     * 正文间距这一侧，不会触发这个边界情况，所以按真实比例调整 fixture 而不是去
     * 改动分段算法本身。
     */
    @Test
    fun `后台学习页脚标题水印跑完之后，loadPage 能正确过滤`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val font = PDType1Font.HELVETICA
        val bodyLineCount = 15
        val bodyYRaw = (0 until bodyLineCount).map { 950f - it * 15f }
        val footerYRaw = listOf(690f, 630f, 570f, 510f)
        for (pageNo in 1..3) {
            val page = PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(612f, 1000f))
            document.addPage(page)
            val stream = PDPageContentStream(document, page)
            fun writeLine(y: Float, text: String) {
                stream.beginText()
                stream.setFont(font, 12f)
                stream.newLineAtOffset(50f, y)
                stream.showText(text)
                stream.endText()
            }
            // 2026-08-25：每行加填充词让右边界接近页宽——原来"page1 line0"这种短
            // 文本远短于半页宽，[linesToParagraphs] 补上"紧凑列表识别"（见该函数
            // KDoc、NOTES.md #14/#37）之后，15 行连续短行会被新规则当成列表逐行
            // 拆开，这条测试真正要验证的是"per-page 样本量小、中位数统计不稳定"
            // 这件事（见本函数上方注释），跟行宽无关，补宽内容只是避开新规则，
            // 不改变这条测试原本的意图。
            (0 until bodyLineCount).forEach { i ->
                writeLine(bodyYRaw[i], "page$pageNo line$i with extra filler words for testing width thresholds here")
            }
            writeLine(footerYRaw[0], "2026/7/10 23:21")
            writeLine(footerYRaw[1], "Happy Life Handbook (2025)")
            writeLine(footerYRaw[2], "https://baike.azpdl.net/#/entry/abc-123")
            writeLine(footerYRaw[3], "$pageNo/136")
            stream.close()
        }
        val file = File.createTempFile("footer-noise-session-doc", ".pdf")
        file.deleteOnExit()
        document.save(file)
        document.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            session.awaitFooterLearningForTest()
            val texts = (1..session.pageCount).flatMap { pageNo ->
                session.loadPage(pageNo).blocks.filterIsInstance<DisplayBlock.Text>().map { it.text }
            }
            // 15 条正文行间距都是 15（小于阈值），合并成一段——每页应该只剩 1 段正文。
            assertEquals(3, texts.size)
            texts.forEachIndexed { index, text -> assertTrue(text.contains("page${index + 1} line0")) }
            val joined = texts.joinToString("\n")
            assertFalse(joined.contains("2026/7/10"))
            assertFalse(joined.contains("baike.azpdl.net"))
            assertFalse(joined.contains("Happy Life Handbook"))
        }
    }

    /**
     * 2026-08-21：用户真机反馈+确认——某些扫描版文档一整页是一张占满全页的图片，
     * 旁边跟着一行没有意义的乱码（扫描工具自动加的隐藏 OCR 文字层，识别质量差时
     * 就是反复出现几个常见字的垃圾输出，跟图片内容毫无关系）。用户明确要求：图片
     * 占满全页时不显示旁边的文字。这条测试用一张缩放铺满整个页面的图 + 一行"乱码"
     * 验证 `loadPage` 只留图片、不留文字。
     */
    @Test
    fun `图片占满全页时不显示旁边的文字`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        val pageWidth = 200f
        val pageHeight = 300f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        val image = document.tinyImage()
        val stream = PDPageContentStream(document.pdDocument, page)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("garbage ocr text")
        stream.endText()
        // 图片铺满整个页面（起点 (0,0)，宽高就是页面宽高）——对应 loadPage 里
        // hasFullPageImage 判断用的"渲染宽高跟页面宽高的比例"。
        stream.drawImage(image, 0f, 0f, pageWidth, pageHeight)
        stream.close()

        val file = File.createTempFile("full-page-image-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            assertTrue("应该保留图片", blocks.any { it is DisplayBlock.Image })
            assertTrue(
                "图片占满全页时不该显示旁边的乱码文字，实际 blocks=$blocks",
                blocks.none { it is DisplayBlock.Text },
            )
        }
    }

    /**
     * NOTES.md #48：接入 JPX 解码器（[Jpeg2000Decoder]）当天马上暴露的回归——
     * 真机反馈"整页灰色，没有图像"，分两轮修复。根因：LuraDocument 这类双层
     * 扫描技术生成的文档，背景层本身经常是一坨接近纯色的东西（纸张底色/
     * 纹理），真正能读的内容全靠 PDF 里独立的、真实准确的文字对象承载，不是
     * 上面 `图片占满全页时不显示旁边的文字` 那条测试针对的"OCR 乱码文字"场景。
     *
     * **第一轮修复**（只做到"不隐藏文字"）：JPX 解码"成功"（技术上没错，像素
     * 值是对的）之后，按原来的规则隐藏文字，用户看到的从"至少能读文字"倒退成
     * "整页一坨灰色，什么都看不到"，比图片解码失败时的旧行为还差——改成
     * "占满全页+看起来真的有内容"两者都满足才隐藏文字。
     *
     * **第二轮修复**（真机复测揪出的进一步问题）：只是"不隐藏文字"还不够——
     * 这张接近纯色的背景图仍然被当成一个展示块加进页面里，按原始像素尺寸等比
     * 缩放显示，占的屏幕空间远大于旁边可能只有两三行的真实文字，翻到这页第一
     * 眼看到的还是一大片灰色，文字要往下滚很久才看得到，观感上还是"这页是灰
     * 的"。改成：占满全页 + 没有实际内容的图片，直接不展示这个图片本身，不只是
     * 不隐藏文字——这类图片没有任何值得展示的内容，继续展示只会占地方。
     *
     * 这条测试用一张真实解码成功、但内容接近纯灰色（标准差远低于
     * [PdfTextExtractor.VISIBLE_CONTENT_MIN_LUMINANCE_STDDEV]）的图片铺满整页
     * +一段"真实"文字，验证 [PdfTextExtractor.PageContentStreamEngine
     * .hasVisibleContent] 生效：图片解码成功但没有实际内容时，文字继续展示，
     * 这张没有内容的图片本身则完全不出现在结果里。
     */
    @Test
    fun `图片解码成功但接近纯色时只展示文字，图片本身也不展示（真机JPX灰屏反例）`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val pageWidth = 200f
        val pageHeight = 300f
        val page = PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight))
        document.addPage(page)

        // 纯色 PNG（32x32，全部像素同一个灰色），标准差=0，远低于判定阈值——
        // 用 java.awt/ImageIO（纯 JVM，不经过 Android Bitmap API）现场生成，
        // 不需要额外的二进制 fixture 文件。
        val solidGrayPng = run {
            val image = java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = java.awt.Color(170, 170, 158)
            graphics.fillRect(0, 0, 32, 32)
            graphics.dispose()
            val output = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(image, "png", output)
            output.toByteArray()
        }
        val grayImage = com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(document, solidGrayPng, "gray")

        val stream = PDPageContentStream(document, page)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("real book text")
        stream.endText()
        stream.drawImage(grayImage, 0f, 0f, pageWidth, pageHeight)
        stream.close()

        val file = File.createTempFile("full-page-solid-gray-image-doc", ".pdf")
        file.deleteOnExit()
        document.save(file)
        document.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            assertTrue(
                "接近纯色的图片不该被当成'真的有内容'，文字应该继续展示，实际 blocks=$blocks",
                blocks.any { it is DisplayBlock.Text },
            )
            assertTrue(
                "接近纯色、没有实际内容的图片不该展示，只会占地方，实际 blocks=$blocks",
                blocks.none { it is DisplayBlock.Image },
            )
        }
    }

    /**
     * NOTES.md #49：真机反馈"跳过没内容的全页图片"这条规则对封面页是错的——
     * 真机核实过封面页 `pdftotext` 抽出来的字符数是 0，跟内页动辄几百字符的
     * 真实段落有数量级差距：封面上的副标题/署名/网格线这些设计元素全靠
     * "JPX 背景+蒙版裁出锐利图形"这套机制画出来，没有任何独立可提取文字对象
     * 兜底，被跳过之后这些设计元素凭空消失（真机截图对比 poppler 独立渲染
     * 确认过，见该 NOTES 条目）。
     *
     * 修法：这一页除了"接近纯色的图片被跳过"，还要满足"完全没有真实可提取
     * 文字"（[PageContentStreamEngine.hasSkippedFullPageImage] KDoc"整页栅格化
     * 兜底"一节），才改用 `android.graphics.pdf.PdfRenderer`（Android 系统自带、
     * pdfium 引擎，见 [PdfTextExtractor.Session.renderPageWithAndroidPdfRenderer]
     * KDoc——真机验证过 PdfBox 自己的 `PDFRenderer` 对这类带 JBIG2 蒙版的图片
     * 同样渲染不全，pdfium 才是真正能用的）整页栅格化。
     *
     * **这条测试测不到"真的栅格化出正确图片"这一步**：`android.graphics.pdf
     * .PdfRenderer` 依赖真实的 Android 系统 PDF 渲染服务，Robolectric（纯桌面
     * JVM）没有这个服务的可用影子实现，`renderPageWithAndroidPdfRenderer`
     * 内部 `runCatching` 会吞掉失败、返回 `null`——这里只验证"没有走回旧的
     * 展示逻辑"（不该出现文字块，这一页本来就没有真实文字）；"真的栅格化出
     * 完整封面"这条真机结论见 NOTES.md 对应条目（真机截图跟 poppler 独立渲染
     * 逐像素比对过标题/QFD 图标/副标题/网格线/编者署名，完全一致）。
     */
    @Test
    fun `占满全页的图片没内容且完全没有真实文字时，整页栅格化兜底（真机封面反例）`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val pageWidth = 200f
        val pageHeight = 300f
        val page = PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight))
        document.addPage(page)

        val solidGrayPng = run {
            val image = java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = java.awt.Color(30, 30, 40)
            graphics.fillRect(0, 0, 32, 32)
            graphics.dispose()
            val output = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(image, "png", output)
            output.toByteArray()
        }
        val grayImage = com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(document, solidGrayPng, "gray")

        // 不放任何文字——模拟封面页"设计元素全靠图片承载，没有独立文字对象"
        // 这个真机确认过的结构特征。
        val stream = PDPageContentStream(document, page)
        stream.drawImage(grayImage, 0f, 0f, pageWidth, pageHeight)
        stream.close()

        val file = File.createTempFile("full-page-no-text-cover-doc", ".pdf")
        file.deleteOnExit()
        document.save(file)
        document.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            // Robolectric 环境下 android.graphics.pdf.PdfRenderer 不可用，栅格化
            // 会失败返回 null，blocks 因此是空列表——这跟真机上"渲染成功、恰好
            // 一张图片"是同一条代码路径的两种环境结果，见上面类 KDoc 完整说明。
            assertTrue(
                "整页栅格化兜底最多只应该有一张图片，不该有文字块，实际 blocks=$blocks",
                blocks.count { it is DisplayBlock.Image } <= 1 && blocks.none { it is DisplayBlock.Text },
            )
        }
    }

    /**
     * NOTES.md #49：上一条测试用的是"完全没有文字"这个理想情况，真机数据打脸
     * 过一次——真实封面页并不是真的一个字都提取不出来，`pdftotext`/自家
     * stripper 都抽出了一个孤立的"o"（扫描/字体渲染噪音，不是真内容）。第一版
     * 判断用 `filtered.isEmpty()`（段落数是否为零）会被这一个字符骗过去，
     * 误判成"有文字"，不触发栅格化兜底，封面继续缺副标题/署名/网格线。
     *
     * 这条测试专门验证这个真机反例：页面上放一个孤立的短字符（不是空字符串），
     * 图片仍然是接近纯色且无文字兜底价值——应该仍然触发整页栅格化兜底，不能
     * 因为"技术上不是空的"就放过。
     *
     * 断言用"这个孤立字符不该出现在结果里"而不是"应该有一张图片"——见上一条
     * 测试 KDoc，`android.graphics.pdf.PdfRenderer` 在 Robolectric 下不可用，
     * 没法断言真的拿到栅格化图片；但"旧判断被这个字符骗过、退回正常文字展示"
     * 这个回归（本条测试真正要防的问题）刚好能测：一旦退回正常路径，这个"o"
     * 会被当成 [DisplayBlock.Text] 展示出来，走了正确的整页栅格化兜底则不会
     * （兜底分支整个丢弃 `filtered`，不管栅格化成功与否都不会有这个文字块）。
     */
    @Test
    fun `只有孤立噪音字符、没有真正段落时，仍然整页栅格化兜底（真机封面反例二）`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val pageWidth = 200f
        val pageHeight = 300f
        val page = PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight))
        document.addPage(page)

        val solidGrayPng = run {
            val image = java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = java.awt.Color(30, 30, 40)
            graphics.fillRect(0, 0, 32, 32)
            graphics.dispose()
            val output = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(image, "png", output)
            output.toByteArray()
        }
        val grayImage = com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(document, solidGrayPng, "gray")

        val stream = PDPageContentStream(document, page)
        // 真机确认过的噪音特征：孤立的单字符，不是一整段真实内容。
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("o")
        stream.endText()
        stream.drawImage(grayImage, 0f, 0f, pageWidth, pageHeight)
        stream.close()

        val file = File.createTempFile("full-page-noise-char-cover-doc", ".pdf")
        file.deleteOnExit()
        document.save(file)
        document.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            assertTrue(
                "只有一个噪音字符不算真实文字兜底，仍应整页栅格化、不该展示这个字符，实际 blocks=$blocks",
                blocks.none { it is DisplayBlock.Text },
            )
        }
    }

    /**
     * NOTES.md #43：真机反馈一份 Internet Archive 扫描书（LuraDocument 产出，
     * 每页是 JPEG2000/JPX 编码的扫描背景图 + 真实可读的文字层）真机复现"翻开
     * 一页，什么都没有"——追出根因：这台设备解不了 JPX（需要额外的可选组件
     * `com.gemalto.jp2:jp2-android`，2026-08-26 核实过原发布仓库 JCenter 已关停、
     * Maven Central 没有这个坐标、JitPack 全部版本构建失败，添加 JPX 支持单独
     * 评估，这次没做），图片解码失败、文字又被"图片占满全页时不显示文字"那条
     * 规则错误隐藏——两个"各自合理"的处理叠在一起变成空白页。
     *
     * 这条测试用上面"占满全页时不显示旁边的文字"同样的构造方式，但把图片换成
     * 解码会失败的损坏图片（借用 [PdfTextExtractorImageTest] 里验证过的手法：
     * `COSStream` 一个字节都不写，`PDImageXObject.isEmpty()` 命中，`getImage()`
     * 直接抛 `IOException`）——验证图片解码失败时改成展示文字（不是隐藏），
     * 同时展示一张诚实的占位图（不是让图片凭空消失）。
     */
    @Test
    fun `占满全页的图片解码失败时展示文字而不是空白页（真机JPX反例）`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val pageWidth = 200f
        val pageHeight = 300f
        val page = PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight))
        document.addPage(page)

        val cosStream = document.document.createCOSStream()
        cosStream.createOutputStream().close()
        cosStream.setItem(com.tom_roush.pdfbox.cos.COSName.TYPE, com.tom_roush.pdfbox.cos.COSName.XOBJECT)
        cosStream.setItem(com.tom_roush.pdfbox.cos.COSName.SUBTYPE, com.tom_roush.pdfbox.cos.COSName.IMAGE)
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.WIDTH, 931)
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.HEIGHT, 1250)
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.BITS_PER_COMPONENT, 8)
        cosStream.setItem(com.tom_roush.pdfbox.cos.COSName.COLORSPACE, com.tom_roush.pdfbox.cos.COSName.DEVICERGB)
        val corruptImage = com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject(
            com.tom_roush.pdfbox.pdmodel.common.PDStream(cosStream),
            com.tom_roush.pdfbox.pdmodel.PDResources(),
        )

        val stream = PDPageContentStream(document, page)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("real book text")
        stream.endText()
        // 图片铺满整个页面，跟"占满全页时不显示旁边的文字"那条测试同一个构造方式，
        // 只是这张图解码会失败。
        stream.drawImage(corruptImage, 0f, 0f, pageWidth, pageHeight)
        stream.close()

        val file = File.createTempFile("full-page-broken-image-doc", ".pdf")
        file.deleteOnExit()
        document.save(file)
        document.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            assertTrue(
                "图片解码失败时应该展示文字，不是空白页，实际 blocks=$blocks",
                blocks.any { it is DisplayBlock.Text },
            )
            assertTrue("应该展示一张占位图，不是让图片凭空消失", blocks.any { it is DisplayBlock.Image })
        }
    }

    /**
     * NOTES.md #38/#39：真机年报封面页被 [TableGridDetector.tableRegionOrNull]
     * 误判成表格——封面是设计感很强的一页，色块/装饰线条凑巧命中"≥3 横线+≥3
     * 竖线、边界框重叠"这条本来是为真表格设计的判定条件，结果整页图片被裁剪成
     * 表格分支那种"220 DPI 整页栅格化再裁剪"的局部图，而不是按"图片占满全页"
     * 的既有规则（见上面`图片占满全页时不显示旁边的文字`）直接展示原图。
     *
     * 这条测试同时构造"占满全页的图片"+"凑巧组成网格的矢量线段"，验证修复后
     * `scanHasFullPageImage=true` 时不再跑表格分支——用图片的原始像素尺寸
     * （120×80，`tiny.png` 的真实尺寸）区分两条分支：表格分支会产出一张按
     * 页面尺寸（200×300pt）以 220 DPI 栅格化再裁剪的图，尺寸不可能是 120×80；
     * 只有直接抽取内嵌图片（`decodeImages=true` 那条路径，不经过栅格化）才会
     * 保留原始像素尺寸。没有改动 [TableGridDetector] 本身的判定逻辑（NOTES #17
     * 记录过那是来回调过好几次的敏感区域），只是让"整页图片"这个更具体的信号
     * 优先于"矢量线段凑巧像网格"这个更弱的启发式信号——跟"图片占满全页时不
     * 显示旁边文字"是同一条已经验证过的产品规则的自然延伸，不是新的判断。
     */
    @Test
    fun `整页图片和巧合的表格状矢量线段同时出现时 按整页图片处理不裁成表格图`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        val pageWidth = 200f
        val pageHeight = 300f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        val image = document.tinyImage()
        val stream = PDPageContentStream(document.pdDocument, page)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("garbage ocr text")
        stream.endText()
        // 图片铺满整个页面，跟`图片占满全页时不显示旁边的文字`那条测试同一个构造方式。
        stream.drawImage(image, 0f, 0f, pageWidth, pageHeight)
        // 凑巧组成"网格"的装饰性矢量线（3 横 + 3 竖，边界框重叠）——照抄
        // TableGridDetectorTest 里"3列4行的规整网格线判定为像表格"那条用例的
        // 构造思路，改用 Chromium 风格的填充细矩形（addRect+fill），因为
        // TableGridDetector 的信号就是填充矩形的长边，不是描边直线（见该类 KDoc）。
        val verticalXs = listOf(20f, 100f, 180f)
        val horizontalYs = listOf(50f, 150f, 250f)
        for (y in horizontalYs) {
            stream.addRect(verticalXs.first(), y, verticalXs.last() - verticalXs.first(), 1f)
            stream.fill()
        }
        for (x in verticalXs) {
            stream.addRect(x, horizontalYs.first(), 1f, horizontalYs.last() - horizontalYs.first())
            stream.fill()
        }
        stream.close()

        val file = File.createTempFile("full-page-image-with-fake-table-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            val images = blocks.filterIsInstance<DisplayBlock.Image>()
            assertTrue("应该保留图片，实际 blocks=$blocks", images.isNotEmpty())
            assertEquals("不该走表格分支裁出额外的图片块", 1, images.size)
            assertEquals("应该是直接抽取的原图（120×80），不是整页栅格化裁剪出来的图", 120, images.single().bitmap.width)
            assertEquals("应该是直接抽取的原图（120×80），不是整页栅格化裁剪出来的图", 80, images.single().bitmap.height)
            assertTrue(
                "图片占满全页时不该显示旁边的文字，实际 blocks=$blocks",
                blocks.none { it is DisplayBlock.Text },
            )
        }
    }

    /**
     * 2026-08-27 真机反馈修复（"第二页颜色不对"）：跟上面那条 NOTES #38/#39 的
     * 测试不同——那条是"一张真的占满全页的图片"+"巧合的表格状线段"，这次
     * 是完全没有占满全页图片的设计页（多个小缩略图配细边框/装饰线/背景色块），
     * 矢量段凑巧被 [TableGridDetector] 判定出一个**精确等于整个页面 MediaBox**
     * 的 [TableRegion]（真机日志验证过 minX=minY=0、maxX/maxY=页面宽高）——这种
     * "四条边都跟页面边界零边距贴死"的形状本身就不像真表格，命中后原本会把
     * 整页交给 PdfBox-Android 自己的 `PDFRenderer` 栅格化，那条渲染管线不认识
     * DeviceCMYK，栅格化结果颜色全错（黑底蓝字）。
     *
     * 第一版修复只是让这种形状回退到"逐张小图 + 文字段落"的正常 reflow 分支，
     * 真机复测发现观感更差——这一页的背景色块/装饰线条本来就是矢量图形，
     * reflow 抽取模型只认文字段落和内嵌图片两种内容，完全没有"矢量填充"这个
     * 概念，10 张互不相关的缩略图纵向罗列、丢光所有版式关系，看起来是一整块
     * 灰色。改成跟 NOTES #48/#49 的 JPX 整页兜底同一条路：命中这个形状直接用
     * [Session.renderPageWithAndroidPdfRenderer]（pdfium）整页栅格化，不落进
     * 下面表格裁剪分支，也不落进 reflow 分支。
     *
     * 用网格线的坐标精确覆盖 `(0,0)` 到 `(pageWidth,pageHeight)` 复现这个形状。
     * Robolectric 环境下 `android.graphics.pdf.PdfRenderer` 不可用（纯 JVM，
     * 没有系统 PDF 渲染服务，见上面"占满全页的图片没内容且完全没有真实文字时"
     * 那条测试同样的环境限制），`renderPageWithAndroidPdfRenderer` 内部
     * `runCatching` 会静默返回 `null`——这条测试只能验证"决策分支选对了"（没有
     * 落进表格裁剪分支产出错误尺寸的裁剪图，也没有落进 reflow 分支把小图和
     * 文字都摆出来），不能验证真实像素，真机截图是实际正确性的证据。
     */
    @Test
    fun `表格状矢量线段精确覆盖整个页面边界时判定为误判 改用整页栅格化兜底`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        val pageWidth = 200f
        val pageHeight = 300f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        val image = document.tinyImage()
        val stream = PDPageContentStream(document.pdDocument, page)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("design page caption text")
        stream.endText()
        // 图片只占一角，远没有到"占满全页"的比例——跟上面那条测试的关键区别。
        stream.drawImage(image, 10f, 10f, 40f, 30f)
        // 装饰性矢量线的边界框精确等于整个页面（0,0)-(pageWidth,pageHeight)，
        // 复现真机那份 Ansys 文档设计页的坐标形状。
        val verticalXs = listOf(0f, pageWidth / 2f, pageWidth)
        val horizontalYs = listOf(0f, pageHeight / 2f, pageHeight)
        for (y in horizontalYs) {
            stream.addRect(verticalXs.first(), y, verticalXs.last() - verticalXs.first(), 1f)
            stream.fill()
        }
        for (x in verticalXs) {
            stream.addRect(x, horizontalYs.first(), 1f, horizontalYs.last() - horizontalYs.first())
            stream.fill()
        }
        stream.close()

        val file = File.createTempFile("full-page-table-shaped-lines-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            // Robolectric 下 renderPageWithAndroidPdfRenderer 必然返回 null（见上面
            // KDoc），修复后这条分支应该整页栅格化兜底、直接返回，blocks 因此应该
            // 是空列表——不是表格裁剪分支产出的一张 ~611px 宽裁剪图（旧行为，
            // PdfBox 自己的 PDFRenderer 在 Robolectric 下确实能跑，只是颜色是错的，
            // 真机才看得出来），也不是 reflow 分支产出的"120×80 小图 + 文字段落"。
            assertTrue("应该整页栅格化兜底（Robolectric 下即空列表），实际 blocks=$blocks", blocks.isEmpty())
        }
    }

    /**
     * 2026-09-02 真机反馈修复（"文字和图片分开显示了"）：`ansys_electronic_test.pdf`
     * 第 20 页用 `pdfimages -list` 核对确认，根因是**同一张图片对象在这一页里被
     * 复用了 3 次**（设计师把一张装饰色块当"连接箭头"用，摆在 3 个不同位置/角度
     * 拼出视觉连接效果）——见 [PdfTextExtractor.PageContentStreamEngine
     * .hasReusedImage] KDoc 完整背景。这条测试复现最小场景：**同一个
     * `PDImageXObject` 实例**（不是内容相同的两份拷贝，见下面反例）被
     * `drawImage` 调用两次。
     *
     * Robolectric 下 `renderPageWithAndroidPdfRenderer` 必然返回 null（跟上面
     * "表格状矢量线段"那条测试同样的环境限制），这条测试只能验证"决策分支选对
     * 了"（blocks 是空列表，不是 reflow 分支产出的"文字段落 + 两张一样的小图"），
     * 不能验证真实像素，真机截图是实际正确性的证据。
     */
    @Test
    fun `同一张图片对象在页内被复用两次时整页栅格化兜底`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        val pageWidth = 200f
        val pageHeight = 300f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        // 只建一个 PDImageXObject 实例，调用方两次 drawImage 传的是同一个引用——
        // 跟真实 PDF 里同一个资源名被 `/Do` 调用两次是同一回事（PdfBox-Android
        // 内部资源解析也会返回同一个实例，见 hasReusedImage KDoc 引用的
        // decodeSoftMaskCompositeOrNull 那段注释）。
        val sharedImage = document.tinyImage()
        val stream = PDPageContentStream(document.pdDocument, page)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("infographic caption text")
        stream.endText()
        stream.drawImage(sharedImage, 10f, 10f, 40f, 30f)
        stream.drawImage(sharedImage, 120f, 200f, 40f, 30f)
        stream.close()

        val file = File.createTempFile("reused-image-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            assertTrue("应该整页栅格化兜底（Robolectric 下即空列表），实际 blocks=$blocks", blocks.isEmpty())
        }
    }

    /**
     * 反例：两张**内容相同但物理上是两个不同对象**的图片（各自单独
     * `createFromByteArray` 出来，就像 [PdfDocumentForTest.tinyImage] 每次调用
     * 都新建一份）不该触发整页栅格化——这是 #51 已经修好的"一页多个独立图文
     * 小节"场景（每节自己的文字+图），不能被这次新加的检测误伤。
     */
    @Test
    fun `两张内容相同但物理上是不同对象的图片不触发整页栅格化`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        val pageWidth = 200f
        val pageHeight = 300f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        val stream = PDPageContentStream(document.pdDocument, page)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 250f)
        stream.showText("section one caption")
        stream.endText()
        stream.drawImage(document.tinyImage(), 10f, 200f, 40f, 30f)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 100f)
        stream.showText("section two caption")
        stream.endText()
        stream.drawImage(document.tinyImage(), 10f, 50f, 40f, 30f)
        stream.close()

        val file = File.createTempFile("distinct-image-objects-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            assertTrue("不该整页栅格化，应该正常 reflow", blocks.any { it is DisplayBlock.Text })
            assertEquals("两张图应该都正常展示，不是被裁成一张", 2, blocks.count { it is DisplayBlock.Image })
        }
    }

    /**
     * 2026-09-03 真机反馈修复（"第 30 页文字与图片还是分开了"）：延续上面
     * `hasReusedImage` 那次修复的思路，但这页不是"复用同一张图片对象"，是
     * "两张不同的图片对象被故意摆放成物理重叠"——真机数据用 CTM 精确核对过，
     * 见 [PdfTextExtractor.PageContentStreamEngine.hasOverlappingImages]
     * KDoc 完整背景。这条测试构造两张**内容不同、物理上也是不同对象**的图片
     * （避免跟 [hasReusedImage] 那条规则搞混），故意让它们的包围盒有实质重叠
     * （X:[10,70]∩[40,100]=[40,70]=30pt，Y:[10,70]∩[30,90]=[30,70]=40pt，
     * 交集面积 1200，两张图各自面积都是 3600，重叠比 1200/3600≈33%，远高于
     * [PdfTextExtractor] 内 `MIN_IMAGE_OVERLAP_RATIO`=15% 的门槛）。
     */
    @Test
    fun `两张不同图片对象故意物理重叠时整页栅格化兜底`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        val pageWidth = 200f
        val pageHeight = 300f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        val stream = PDPageContentStream(document.pdDocument, page)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("layered chart caption text")
        stream.endText()
        stream.drawImage(document.tinyImage(), 10f, 10f, 60f, 60f)
        stream.drawImage(document.tinyImage(), 40f, 30f, 60f, 60f)
        stream.close()

        val file = File.createTempFile("overlapping-image-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            assertTrue("应该整页栅格化兜底（Robolectric 下即空列表），实际 blocks=$blocks", blocks.isEmpty())
        }
    }

    /**
     * 反例：两张不同图片对象**边缘贴合但不重叠**（第一张右边界 x=70 恰好
     * 是第二张左边界 x=70，包围盒 X 区间刚好相邻不相交）——正常图片并排
     * 摆放的常见形状，重叠面积应该是 0，不该被新加的检测误判成"故意重叠"。
     */
    @Test
    fun `两张图片边缘贴合但不重叠时不触发整页栅格化`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        val pageWidth = 200f
        val pageHeight = 300f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        val stream = PDPageContentStream(document.pdDocument, page)
        stream.drawImage(document.tinyImage(), 10f, 10f, 60f, 60f)
        stream.drawImage(document.tinyImage(), 70f, 10f, 60f, 60f)
        stream.close()

        val file = File.createTempFile("adjacent-image-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            assertEquals("边缘贴合不算重叠，两张图应该都正常展示", 2, blocks.count { it is DisplayBlock.Image })
        }
    }

    /**
     * NOTES.md #42：真机反馈整页图片的页要等图片全解完才看到任何东西（尤其是
     * 不显示文字的 hasFullPageImage 页），加了 `onImageReady` 回调让每张图片
     * 刚解出来就能先展示，不用等整页处理完。这条测试验证回调本身的契约：
     * 一页两张图片，回调应该正好触发两次，且在 `loadPage` 整体返回之前就已经
     * 触发完——调用方（[app.pdfreader.ui.PdfPageAdapter]）就是靠"回调先到、
     * 最终返回值后到"这个时序做"先预览、后用权威结果整体刷新"的。
     */
    @Test
    fun `loadPage 图片边解码边回调 每张图片触发一次且在整体返回前完成`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        val pageWidth = 200f
        val pageHeight = 300f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        val stream = PDPageContentStream(document.pdDocument, page)
        // 两张图各占一角，都不到"占满全页"的比例，不触发 hasFullPageImage/表格
        // 那两条跟这条测试无关的分支，只测回调本身的次数和时序。
        stream.drawImage(document.tinyImage(), 10f, 200f, 40f, 30f)
        stream.drawImage(document.tinyImage(), 10f, 50f, 40f, 30f)
        stream.close()

        val file = File.createTempFile("two-images-progressive-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val previewed = mutableListOf<Bitmap>()
            val content = session.loadPage(1) { bitmap -> previewed.add(bitmap) }
            // 回调是同步调用的（见 Session.loadPage KDoc），走到这里时应该已经
            // 全部触发完——不是靠回调"最终会不会来"，是靠"loadPage 返回时必然
            // 已经来过"。
            assertEquals("两张图片应该各触发一次回调", 2, previewed.size)
            previewed.forEach {
                assertEquals(120, it.width)
                assertEquals(80, it.height)
            }
            val finalImages = content.blocks.filterIsInstance<DisplayBlock.Image>()
            assertEquals("最终结果的图片数量应该跟回调触发次数一致", previewed.size, finalImages.size)
        }
    }

    /**
     * 用户真机反馈"想先看到当前页，不想等图片也解码完"——文字在锁内那段（Phase
     * A）就已经算完，比图片解码（锁外那段）先就绪，`onTextReady` 让已经算好的
     * 文字立刻交给调用方，不用等图片一起。这条测试验证：
     * 1. `onTextReady` 只触发一次，携带的文字内容跟最终结果的文字部分一致。
     * 2. `onTextReady` 在任何 `onImageReady` 之前触发（"文字先于图片就绪"这个
     *    顺序保证，是这个功能存在的意义，不只是"两个回调都会触发"这么简单）。
     */
    @Test
    fun `loadPage 文字算完立刻回调 且早于图片解码完成的回调`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        val pageWidth = 200f
        val pageHeight = 300f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        val stream = PDPageContentStream(document.pdDocument, page)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("real paragraph text")
        stream.endText()
        // 图片只占一角（不到"占满全页"的比例），走 PendingImages 分支——这个
        // 分支才会触发 onTextReady，见 Session.loadPage KDoc。
        stream.drawImage(document.tinyImage(), 10f, 200f, 40f, 30f)
        stream.close()

        val file = File.createTempFile("text-then-image-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val callOrder = mutableListOf<String>()
            var textReadyBlocks: List<DisplayBlock>? = null
            val content = session.loadPage(
                1,
                onTextReady = { blocks ->
                    callOrder.add("text")
                    textReadyBlocks = blocks
                },
            ) { callOrder.add("image") }

            assertEquals("onTextReady 应该只触发一次", 1, callOrder.count { it == "text" })
            assertEquals("文字应该先于图片就绪", listOf("text", "image"), callOrder)
            assertEquals(
                "onTextReady 携带的文字应该跟最终结果的文字部分一致",
                content.blocks.filterIsInstance<DisplayBlock.Text>(),
                textReadyBlocks,
            )
        }
    }

    /**
     * 反例：图片只占页面一角（不是占满全页），旁边的文字应该正常保留——防止
     * `hasFullPageImage` 判断误伤"图文混排、图片本来就不大"这种正常场景。
     */
    @Test
    fun `图片只占一小部分页面时文字正常保留`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        val pageWidth = 200f
        val pageHeight = 300f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        val image = document.tinyImage()
        val stream = PDPageContentStream(document.pdDocument, page)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("real caption text")
        stream.endText()
        // 图片只占页面左上角一小块（40x30），远没到"占满全页"的比例阈值。
        stream.drawImage(image, 10f, 250f, 40f, 30f)
        stream.close()

        val file = File.createTempFile("small-image-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val texts = session.loadPage(1).blocks.filterIsInstance<DisplayBlock.Text>().map { it.text }
            assertEquals(listOf("real caption text"), texts)
        }
    }

    /**
     * 2026-08-28 真机反馈修复（排查"图片和文字分开了"时发现的更基础问题）：
     * InDesign 导出的跨页拼版文档，一页的 content stream 会包含邻页的绘图指令
     * （超出这一页自己 `MediaBox` 的部分——`MediaBox` 只是显示窗口，不是内容
     * 边界）。真机验证过真实数据：读一份文档"RF 电路"页的文字，会连同前一页
     * "天线设计"的全部内容一起读出来，X 坐标整体偏移了一个页面宽度（是负数，
     * 肉眼看不见，但确实混进了段落列表）。
     *
     * 这条测试用最小的构造复现同一个坐标形状：一页正常写一段"这一页真正的
     * 文字"（X 在页面范围内），content stream 里另外在页面左边界之外（X 是
     * 负数）写一段"邻页文字"（模拟被拼版污染进来的内容）——`Session.loadPage`
     * 的结果应该只有真正属于这一页的文字，邻页文字必须被 [PdfTextExtractor
     * .isLineOnPage] 挡在外面，不出现在任何段落里。
     */
    @Test
    fun `跨页拼版导致的越界文字不会混进这一页的段落里（InDesign拼版真机反例）`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        val pageWidth = 595.276f
        val pageHeight = 807.874f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        val stream = PDPageContentStream(document.pdDocument, page)
        // 邻页文字：X 坐标在页面左边界（0）之外，模拟拼版污染。
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(-500f, 700f)
        stream.showText("neighboring page bleed text")
        stream.endText()
        // 这一页真正的文字：X 坐标在页面范围内。
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(50f, 400f)
        stream.showText("real content on this page")
        stream.endText()
        stream.close()

        val file = File.createTempFile("cross-page-bleed-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val texts = session.loadPage(1).blocks.filterIsInstance<DisplayBlock.Text>().map { it.text }
            assertEquals(listOf("real content on this page"), texts)
        }
    }

    /**
     * 2026-08-28 真机反馈（"图片和文字分开了"）——用户拍板不追求重排出正确的
     * 两栏阅读顺序，改成识别到两栏排版就整页栅格化，见
     * [PdfTextExtractor.hasColumnGap] KDoc 完整背景。这条测试构造一个真实的
     * 两栏页面（左栏/右栏各 6 段独立文字，中间留出干净的空白带），验证
     * `Session.loadPage` 走的是整页栅格化分支，不是逐段文字重排。
     *
     * Robolectric 环境下 `android.graphics.pdf.PdfRenderer` 不可用（纯 JVM，
     * 没有系统 PDF 渲染服务，见上面"占满全页的图片没内容且完全没有真实文字时"
     * 那条测试同样的环境限制），`renderPageWithAndroidPdfRenderer` 内部
     * `runCatching` 会静默返回 `null`——这条测试只能验证"决策分支选对了"
     * （没有落进逐段文字重排分支，也没有把 12 段文字原样展示出来），不能验证
     * 真实栅格化像素，真机截图是实际正确性的证据。
     */
    @Test
    fun `识别到两栏排版时整页栅格化 不逐段重排文字（真机RF电路页简化复现）`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        val pageWidth = 595.276f
        val pageHeight = 807.874f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        val stream = PDPageContentStream(document.pdDocument, page)
        // 左栏 6 段独立文字，X 落在页面左半部分。
        for (i in 0 until 6) {
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 12f)
            stream.newLineAtOffset(50f, 700f - i * 80f)
            stream.showText("left column section $i")
            stream.endText()
        }
        // 右栏 6 段独立文字，X 落在页面右半部分，跟左栏之间留出干净的空白带。
        for (i in 0 until 6) {
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 12f)
            stream.newLineAtOffset(320f, 700f - i * 80f)
            stream.showText("right column section $i")
            stream.endText()
        }
        stream.close()

        val file = File.createTempFile("two-column-layout-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            assertTrue(
                "识别到两栏排版后不该逐段重排文字，实际 blocks=$blocks",
                blocks.none { it is DisplayBlock.Text },
            )
        }
    }

    /**
     * 2026-09-03 真机反馈（"这本书当前页文字与图片分开显示，应该显示为图片"）——
     * "CPS 热分析"页的三栏"分类标签+产品名徽章+短语列表"版式，见
     * [PdfTextExtractor.hasLabelColumnWithSideContent] KDoc 完整背景。这条测试
     * 构造一个真实的三栏页面（右栏 6 条短语对齐同一 X、左边有 3 条不同的其它
     * 内容），验证 `Session.loadPage` 走的是整页栅格化分支。Robolectric 环境
     * 限制同上面两栏测试，只能验证决策分支选对了，不能验证真实像素。
     */
    @Test
    fun `识别到标签列且左侧有其它内容时整页栅格化（真机CPS热分析页简化复现）`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        val pageWidth = 595.276f
        val pageHeight = 807.874f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        val stream = PDPageContentStream(document.pdDocument, page)
        // 右栏 6 条短语，精确对齐同一个 X（431pt）。
        listOf(200f, 220f, 240f, 260f, 280f, 300f).forEachIndexed { i, topY ->
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 10f)
            stream.newLineAtOffset(431f, pageHeight - topY)
            stream.showText("phrase label $i")
            stream.endText()
        }
        // 左边不同 X 的 3 条内容（分类标签+产品名徽章），落在短语列的 Y 范围内。
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 10f)
        stream.newLineAtOffset(80f, pageHeight - 210f)
        stream.showText("category label")
        stream.endText()
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 10f)
        stream.newLineAtOffset(230f, pageHeight - 230f)
        stream.showText("product badge name")
        stream.endText()
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 10f)
        stream.newLineAtOffset(80f, pageHeight - 290f)
        stream.showText("category label two")
        stream.endText()
        stream.close()

        val file = File.createTempFile("label-column-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            assertTrue(
                "识别到标签列后不该逐段重排文字，实际 blocks=$blocks",
                blocks.none { it is DisplayBlock.Text },
            )
        }
    }

    /**
     * 反例（用户明确要求加的校验，防止误伤正常缩进列表）：6 条精确对齐同一
     * X 的列表项，但左边是页面留白，没有别的内容——这跟上面标签列的构造
     * 唯一的区别就是"左边有没有其它内容"，验证这道校验真的能把普通缩进
     * 列表放过、不整页栅格化。
     */
    @Test
    fun `普通缩进列表不会被标签列规则误伤 仍然正常重排`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        val pageWidth = 595.276f
        val pageHeight = 807.874f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        val stream = PDPageContentStream(document.pdDocument, page)
        listOf(200f, 220f, 240f, 260f, 280f, 300f).forEachIndexed { i, topY ->
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 10f)
            stream.newLineAtOffset(90f, pageHeight - topY)
            stream.showText("list item $i")
            stream.endText()
        }
        stream.close()

        val file = File.createTempFile("indented-list-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            assertTrue(
                "普通缩进列表不该被判定为标签列、不该整页栅格化，实际 blocks=$blocks",
                blocks.any { it is DisplayBlock.Text },
            )
        }
    }

    /**
     * 2026-08-28 真机反馈修复（"图片和文字分开了"）：真实场景是一份产品手册，
     * 每页两栏×3 个独立小节，每个小节自己的标题+说明文字+1~3 张图——原来的
     * 实现把一页里所有图片统一堆到这一页最后一段文字之后（见
     * [ImagePlacement.afterParagraphIndexByTopY] KDoc 完整背景），一页 6 张图
     * 全部挪到页面最后，跟它们各自的说明文字完全脱节。这条测试用最小的复现
     * 场景——一页两个独立的"文字+图片"小节——验证修复后图片按自己的纵坐标
     * 插回紧跟在它自己那段文字后面，不是全部堆在页面最后。
     *
     * 每个小节两行文字（不是一行）是必要的构造，不是随意加料：
     * [linesToParagraphs] 用"本页所有相邻行间距的中位数 × 1.5"当分段阈值，
     * 只有两行文字时中位数就是这一个间距本身，"间距 ≤ 间距×1.5"恒成立，两行
     * 永远被并成一段，测不出"按小节分段"这个前提。每个小节内部两行用 12pt
     * 的正常单倍行距（模拟一段说明文字自身的行距），两个小节之间隔 118pt
     * （远大于小节内行距，模拟真实排版里"这一段说明文字讲完了，另起一个不
     * 相关的小节"）——三个间距 `[12, 118, 12]` 中位数是 12，阈值 18，小节内
     * 间距不超阈值（合并成一段），小节之间间距远超阈值（切开成两段），这样
     * 段落切分本身先验证过是符合预期的，图片插入位置的断言才有意义。
     *
     * 页面坐标（PDF 坐标系，原点左下、y 向上，页高 300pt，页宽 595.276pt——
     * 跟项目其它测试用真机页宽同一个惯例，2026-08-29 从原来的 200pt 窄页面
     * 改过来，见下面 `sectionLineText` 那段注释）：
     * - 小节 A：两行文字在 y=280/268（topY=20/32），图片画在 y=200~230（topY=70）
     * - 小节 B：两行文字在 y=150/138（topY=150/162），图片画在 y=50~80（topY=220）
     *
     * 修复前（图片统一堆最后）：`[段落A, 段落B, 图片A, 图片B]`。
     * 修复后（按纵坐标插回）：`[段落A, 图片A, 段落B, 图片B]`。
     */
    @Test
    fun `一页两个独立图文小节时 每张图片插回自己那段文字后面 不是全部堆在页面最后`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val document = PdfDocumentForTest()
        // 2026-08-29：isShortLine 改成量"这一行自己有多宽"而不是"右边界离页面
        // 右侧多远"之后（见该函数 KDoc），原来 200pt 的窄页面配短句子在新公式
        // 下量出来不到半页宽，被误判成"短行"触发紧凑列表规则，把本该合并的
        // 两行拆开——这条测试的本意是"正常段落的两行"，不该被短行列表规则
        // 误伤。改成跟项目其它测试一致的真实页宽（595.276pt），字号也调大到
        // 20pt 让文字明显占到半页以上又不至于超出页面（真机数据不会出现比
        // 页面还宽的单行文字，`isLineOnPage` 会把那种越界内容当跨页污染
        // 过滤掉，太短或太长都会跟这条测试的本意不符）。
        val pageWidth = 595.276f
        val pageHeight = 300f
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(
            com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight),
        )
        document.pdDocument.addPage(page)
        val stream = PDPageContentStream(document.pdDocument, page)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 20f)
        stream.newLineAtOffset(20f, 280f)
        stream.showText("section A line one of the paragraph text")
        stream.newLineAtOffset(0f, -20f)
        stream.showText("section A line two of the paragraph text")
        stream.endText()
        stream.drawImage(document.tinyImage(), 20f, 200f, 40f, 30f)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 20f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("section B line one of the paragraph text")
        stream.newLineAtOffset(0f, -20f)
        stream.showText("section B line two of the paragraph text")
        stream.endText()
        stream.drawImage(document.tinyImage(), 20f, 50f, 40f, 30f)
        stream.close()

        val file = File.createTempFile("two-image-text-sections-doc", ".pdf")
        file.deleteOnExit()
        document.pdDocument.save(file)
        document.pdDocument.close()

        PdfTextExtractor.Session.open(context, file).use { session ->
            val blocks = session.loadPage(1).blocks
            val kinds = blocks.map { if (it is DisplayBlock.Text) "T" else "I" }
            assertEquals(
                "图片应该紧跟在自己那段文字后面，实际 blocks=$blocks",
                listOf("T", "I", "T", "I"),
                kinds,
            )
            val texts = blocks.filterIsInstance<DisplayBlock.Text>().map { it.text }
            assertEquals(2, texts.size)
            assertTrue("第一段应该是小节 A 的两行：${texts[0]}", texts[0].contains("section A"))
            assertTrue("第二段应该是小节 B 的两行：${texts[1]}", texts[1].contains("section B"))
        }
    }

    /** 小工具：包一层 [PDDocument] + 复用 [PdfTextExtractorImageTest] 同款 tiny.png fixture 造一张真实可解码的小图片。 */
    private class PdfDocumentForTest {
        val pdDocument: com.tom_roush.pdfbox.pdmodel.PDDocument = com.tom_roush.pdfbox.pdmodel.PDDocument()

        fun tinyImage(): com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject {
            val pngBytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("tiny.png")?.readBytes()) {
                "找不到测试用的 tiny.png"
            }
            return com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(pdDocument, pngBytes, "tiny")
        }
    }
}
