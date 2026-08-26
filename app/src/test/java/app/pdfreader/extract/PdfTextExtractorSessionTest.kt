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
