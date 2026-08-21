package app.pdfreader.extract

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * [PdfTextExtractor.extractContent] 图片抽取部分的单元测试——SELECTION.md 第 4 节
 * 兜底方案"图片降级为独立浮动展示"这个增量。
 *
 * fixture `sample-with-image.pdf` 的生成方式和 [PdfTextExtractorTest] 顶部注释里
 * 记录的 sample-chinese.pdf 同一套路子：本机 Puppeteer（`~/.claude-tools/webshot`）
 * 把一段内嵌 base64 PNG（120×80，程序生成的测试图案，不是任何真实图片/截图）的 HTML
 * 用 Chromium 打印成 PDF。内容结构是"第一段文字 → 一张图片 → 第二段文字 → 第三段
 * 文字"，用 `pdfimages -list`/`pdftotext -raw` 交叉核对过，确认 PDF 里正好嵌了 1 个
 * image XObject（120×80），文字按三段正常排布。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorImageTest {

    private fun loadFixtureFile(name: String): File {
        val resourceStream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(name)
        ) { "找不到测试 fixture：src/test/resources/$name" }

        val tempFile = File.createTempFile(name, ".pdf")
        tempFile.deleteOnExit()
        resourceStream.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        return tempFile
    }

    private fun extractFixtureContent(): PdfContent {
        val context = RuntimeEnvironment.getApplication()
        return PdfTextExtractor.extractContent(context, loadFixtureFile("sample-with-image.pdf"))
    }

    @Test
    fun `抽取出的图片数量与 fixture 里嵌入的图片数量一致`() {
        val content = extractFixtureContent()
        assertEquals(1, content.images.size)
    }

    @Test
    fun `抽取出的图片能正常转成有效尺寸的 Bitmap`() {
        val content = extractFixtureContent()
        val bitmap = content.images.single().bitmap
        assertTrue("Bitmap 宽度应该大于 0", bitmap.width > 0)
        assertTrue("Bitmap 高度应该大于 0", bitmap.height > 0)
    }

    @Test
    fun `同页图片统一插在该页最后一个段落之后（按页归类，不追求页面内精确位置）`() {
        val content = extractFixtureContent()
        // fixture 是单页 PDF，三段文字和图片都在第 1 页——按页归类的降级方案下，
        // 图片不追求插在"第一段之后、第二段之前"这种页面内精确位置，而是统一插在
        // 这一页文字全部结束之后，也就是最后一个段落（下标 2）之后。这正是 SELECTION.md
        // 第 4 节兜底方案里"把每一页的图片都统一插在这一页对应的文字段落全部结束之后"
        // 这条更简单的降级路径，见 PdfTextExtractor 类注释"图片抽取"一节。
        assertEquals(2, content.images.single().afterParagraphIndex)
    }

    @Test
    fun `抽取图片的同时文字段落数量和内容不受影响`() {
        val content = extractFixtureContent()
        assertEquals(3, content.paragraphs.size)
        assertTrue(content.paragraphs[0].contains("本文档用于测试图片抽取功能"))
        assertTrue(content.paragraphs[1].contains("这张小图片应该出现在第一段之后"))
        assertTrue(content.paragraphs[2].contains("这是文档的最后一段文字"))
    }

    @Test
    fun `没有图片的纯文字 PDF 抽取结果里图片列表为空（回归检查）`() {
        val context = RuntimeEnvironment.getApplication()
        val content = PdfTextExtractor.extractContent(context, loadFixtureFile("sample-chinese.pdf"))
        assertTrue(content.images.isEmpty())
        // 纯文字文档的段落抽取结果要和改动前的 extractParagraphs 完全一致，
        // 这是"没有图片的 PDF 保持现有行为不受影响"这条回归检查点的核心断言。
        assertEquals(
            PdfTextExtractor.extractParagraphs(context, loadFixtureFile("sample-chinese.pdf")),
            content.paragraphs,
        )
    }

    @Test
    fun `单张图片抽取失败不会导致整份文档抽取失败，也不影响其它图片`() {
        val context = RuntimeEnvironment.getApplication()
        val brokenFile = buildDocumentWithOneValidAndOneCorruptImage()

        // 关键断言：即使页面资源里有一个解码会抛异常的损坏图片对象，extractContent
        // 本身不能抛出去——必须整体返回，跳过坏的那张，留下好的那张。
        val content = PdfTextExtractor.extractContent(context, brokenFile)

        assertEquals(1, content.images.size)
        assertTrue(content.images.single().bitmap.width > 0)
        assertEquals(listOf("normal paragraph"), content.paragraphs)
    }

    /**
     * 用 PDFBox 底层 API 直接拼一个"页面资源里有一张正常图片 + 一张损坏图片"的文档。
     *
     * 损坏图片的手法：读了 PdfBox-Android 上游源码（`SampledImageReader.getRGBImage`，
     * github.com/TomRoush/PdfBox-Android/blob/master/library/.../SampledImageReader.java
     * 第 181 行）确认——图片对象的底层 COSStream 一个字节都不写，
     * `PDImageXObject.isEmpty()`（`getStream().getCOSObject().getLength() == 0`）
     * 会命中，`getImage()` 一开始就抛 `IOException("Image stream is empty")`。
     * 选这条路径而不是"塞一段乱码字节冒充 JPEG"，是因为 Robolectric 对
     * `android.graphics.Bitmap`/`BitmapFactory` 的影子实现（Shadow）不会真的按
     * 图片格式解码校验，塞乱码字节在 Robolectric 环境下往往不会像真机那样抛异常
     * ——踩过这个坑（第一版用乱码 JPEG 字节实测在这个测试环境下反而"解码成功"）。
     * `isEmpty()` 这条检查是 PDFBox 自己的纯 Java 逻辑，不经过任何 Android
     * Bitmap API，不受 Robolectric 影子实现影响，是确定性更强的失败触发方式。
     * 文字用英文是因为标准 14 字体（Helvetica）不支持中文字形，写中文会在
     * `showText` 时直接抛异常——这里只是要一段随便什么占位文字来验证"图片抽取
     * 失败不连累文字抽取"，不需要是中文。
     */
    private fun buildDocumentWithOneValidAndOneCorruptImage(): File {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)

        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val contentStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page)
        val font = com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA
        contentStream.beginText()
        contentStream.setFont(font, 12f)
        contentStream.newLineAtOffset(50f, 700f)
        contentStream.showText("normal paragraph")
        contentStream.endText()
        contentStream.close()

        // 2026-08-18 图片朝向修复后，抽取改成跟着 content stream 的 `Do` 操作符走
        // （见 PdfTextExtractor 类注释"内嵌图片朝向修正"一节），不再只看
        // `PDResources` 里挂了哪些名字——一张图片只是"挂在资源字典里、从没被
        // `Do` 画出来"在真实 PDF 里等价于页面上根本看不见这张图，所以这里改成
        // 真的用 `contentStream.drawImage` 画一次，才能如实模拟"页面上有一张
        // 正常图片、一张损坏图片都被画出来"这个场景，而不是曾经那种"只挂资源、
        // 不画"的不真实 fixture。
        val validImage = createValidPngImageXObject(document)
        val corruptImage = createCorruptImageXObject(document)
        val drawStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(
            document,
            page,
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND,
            true,
        )
        drawStream.drawImage(validImage, 50f, 500f, 40f, 30f)
        drawStream.drawImage(corruptImage, 50f, 400f, 40f, 30f)
        drawStream.close()

        val output = File.createTempFile("broken-image-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()
        return output
    }

    private fun createValidPngImageXObject(document: PDDocument): PDImageXObject {
        val pngBytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("tiny.png")?.readBytes()) {
            "找不到测试用的 tiny.png"
        }
        return PDImageXObject.createFromByteArray(document, pngBytes, "tiny")
    }

    private fun createCorruptImageXObject(document: PDDocument): PDImageXObject {
        val cosStream = document.document.createCOSStream()
        // 故意一个字节都不写：COSStream 的 /Length 会是 0，PDImageXObject.isEmpty()
        // 命中，getImage() 一开始就抛 IOException——见上方方法 KDoc 为什么选这条路。
        cosStream.createOutputStream().close()
        cosStream.setItem(
            com.tom_roush.pdfbox.cos.COSName.TYPE,
            com.tom_roush.pdfbox.cos.COSName.XOBJECT,
        )
        cosStream.setItem(
            com.tom_roush.pdfbox.cos.COSName.SUBTYPE,
            com.tom_roush.pdfbox.cos.COSName.IMAGE,
        )
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.WIDTH, 10)
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.HEIGHT, 10)
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.BITS_PER_COMPONENT, 8)
        cosStream.setItem(
            com.tom_roush.pdfbox.cos.COSName.COLORSPACE,
            com.tom_roush.pdfbox.cos.COSName.DEVICERGB,
        )
        val pdStream = PDStream(cosStream)
        return PDImageXObject(pdStream, com.tom_roush.pdfbox.pdmodel.PDResources())
    }

    /**
     * 2026-08-21：用户反馈"之前花屏选择不加载的思路是错的，需要按需加载"——
     * `bitsPerComponent` 不是 1/8（PdfBox-Android 没有专门解码路径的位深，见
     * NOTES.md #19）不该直接跳过不显示，改成自己按位深正确解包（[PdfTextExtractor
     * .decodeRawImageByBitDepth]，通过这条 [drawImage] 流程间接验证，那个函数本身
     * 是 `private`）。这条测试手工拼一张 2×1 像素、4 位每分量的 RGB 原始图像
     * （不走任何压缩 Filter，直接摆位打包好的字节），验证解码出的两个像素颜色
     * 分量正确——不是"能不能显示"这种粗粒度断言，是逐分量数值核对，因为这个
     * bug 当初就是"位深换算错了导致行/高度全乱"，只验证"图片非空"不够。
     *
     * 手工打包（大端、逐分量 4 位、每行按字节对齐）：
     * 像素0 (R=15,G=0,B=8) → 二进制 1111 0000 1000 → 字节0=0xF0，字节1 高 4 位=0x8
     * 像素1 (R=0,G=15,B=0) → 二进制 0000 1111 0000 → 字节1 低 4 位=0x0，字节2=0xF0
     * 拼起来正好 3 字节：[0xF0, 0x80, 0xF0]（2 像素×3 分量×4 位=24 位=3 字节，
     * 这一行天然字节对齐，不用另外补位）。
     * 4 位满量程是 15，缩放到 0-255：15→255，0→0，8→136（8*255/15 取整）。
     */
    @Test
    fun `4位每分量的RGB图片能正确解码出颜色（不再是直接跳过不显示）`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)

        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val cosStream = document.document.createCOSStream()
        cosStream.createOutputStream().use { it.write(byteArrayOf(0xF0.toByte(), 0x80.toByte(), 0xF0.toByte())) }
        cosStream.setItem(com.tom_roush.pdfbox.cos.COSName.TYPE, com.tom_roush.pdfbox.cos.COSName.XOBJECT)
        cosStream.setItem(com.tom_roush.pdfbox.cos.COSName.SUBTYPE, com.tom_roush.pdfbox.cos.COSName.IMAGE)
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.WIDTH, 2)
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.HEIGHT, 1)
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.BITS_PER_COMPONENT, 4)
        cosStream.setItem(com.tom_roush.pdfbox.cos.COSName.COLORSPACE, com.tom_roush.pdfbox.cos.COSName.DEVICERGB)
        val fourBitImage = PDImageXObject(PDStream(cosStream), com.tom_roush.pdfbox.pdmodel.PDResources())

        val drawStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page)
        drawStream.drawImage(fourBitImage, 0f, 0f, 40f, 20f)
        drawStream.close()

        val output = File.createTempFile("four-bit-image-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()

        val content = PdfTextExtractor.extractContent(context, output)

        assertEquals(1, content.images.size)
        val bitmap = content.images.single().bitmap
        assertEquals(2, bitmap.width)
        assertEquals(1, bitmap.height)

        val pixel0 = bitmap.getPixel(0, 0)
        assertEquals(255, android.graphics.Color.red(pixel0))
        assertEquals(0, android.graphics.Color.green(pixel0))
        assertEquals(136, android.graphics.Color.blue(pixel0))

        val pixel1 = bitmap.getPixel(1, 0)
        assertEquals(0, android.graphics.Color.red(pixel1))
        assertEquals(255, android.graphics.Color.green(pixel1))
        assertEquals(0, android.graphics.Color.blue(pixel1))
    }

    /**
     * 2026-08-21：上一版"按位深自己解码"上线后用户反馈"不正常，没有效果"——加诊断
     * 日志装机复现才发现真正的坑：`pdImage.colorSpace.numberOfComponents` 对这份
     * 真机文档的图片报的是 3（RGB），但按 3 分量算出来的"每行字节数×高度"是
     * 2,125,475，实际读到的原始样本字节数只有 708,972——换算下来正好是 1 分量
     * （灰度）的量。库报的分量数是错的，跟本类其它地方已经踩过的"某些颜色空间
     * 检测不准、静默当成 DeviceRGB"是同一类问题（见 CMYK JPEG 那次的教训）。
     *
     * 这条测试专门复现这个"库报的分量数是错的"场景：`COSStream` 的 `/ColorSpace`
     * 字典项故意写成 `DeviceRGB`（3 分量），但底层原始样本数据实际只有 1 分量
     * （灰度）那么多字节——验证 `decodeRawImageByBitDepth` 会用实际字节数反推出
     * 正确的分量数（1，不是字典里写的 3），解码出正确的灰度颜色，而不是照抄
     * 字典里的错误分量数、要么解出乱码要么（原来的写法）直接判定"数据长度不够"
     * 而放弃整张图。
     *
     * 手工打包（1 分量、4 位、宽 2 高 1，天然字节对齐）：
     * 像素0 灰度=15（纯白）像素1 灰度=0（纯黑）→ 二进制 1111 0000 → 单字节 0xF0。
     */
    @Test
    fun `颜色空间报的分量数是错的时候，用实际字节数反推出正确分量数`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)

        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val cosStream = document.document.createCOSStream()
        // 只写了 1 字节——按字典里写的 DeviceRGB（3 分量）来算，2 像素 4 位深应该要
        // 3 字节；这里只给 1 分量（灰度）该有的量，模拟"字典里的颜色空间是错的"。
        cosStream.createOutputStream().use { it.write(byteArrayOf(0xF0.toByte())) }
        cosStream.setItem(com.tom_roush.pdfbox.cos.COSName.TYPE, com.tom_roush.pdfbox.cos.COSName.XOBJECT)
        cosStream.setItem(com.tom_roush.pdfbox.cos.COSName.SUBTYPE, com.tom_roush.pdfbox.cos.COSName.IMAGE)
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.WIDTH, 2)
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.HEIGHT, 1)
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.BITS_PER_COMPONENT, 4)
        cosStream.setItem(com.tom_roush.pdfbox.cos.COSName.COLORSPACE, com.tom_roush.pdfbox.cos.COSName.DEVICERGB)
        val mislabeledImage = PDImageXObject(PDStream(cosStream), com.tom_roush.pdfbox.pdmodel.PDResources())

        val drawStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page)
        drawStream.drawImage(mislabeledImage, 0f, 0f, 40f, 20f)
        drawStream.close()

        val output = File.createTempFile("mislabeled-colorspace-image-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()

        val content = PdfTextExtractor.extractContent(context, output)

        assertEquals(1, content.images.size)
        val bitmap = content.images.single().bitmap
        assertEquals(2, bitmap.width)
        assertEquals(1, bitmap.height)

        val pixel0 = bitmap.getPixel(0, 0)
        assertEquals(255, android.graphics.Color.red(pixel0))
        assertEquals(255, android.graphics.Color.green(pixel0))
        assertEquals(255, android.graphics.Color.blue(pixel0))

        val pixel1 = bitmap.getPixel(1, 0)
        assertEquals(0, android.graphics.Color.red(pixel1))
        assertEquals(0, android.graphics.Color.green(pixel1))
        assertEquals(0, android.graphics.Color.blue(pixel1))
    }

    /**
     * 2026-08-21：用户反馈"图片加载不全"→"有的图片干脆不出现"——真机诊断日志确认
     * 是 JBIG2 格式（扫描书常用的黑白压缩格式），PdfBox-Android 这个 fork 完全没
     * 实现 JBIG2，`pdImage.image` 对这种图片必定失败，原来的兜底逻辑因此把每一张
     * 都静默跳过。试过接 Apache 官方的 jbig2-imageio 库，装机验证过走不通（真机
     * 抛 `NoClassDefFoundError: javax/imageio/ImageReader`，Android 运行时压根没有
     * 这个类，见 [PdfTextExtractor.createUnsupportedImagePlaceholder] KDoc 完整
     * 背景），改成显示一块保留原图长宽比的占位图，不再是静默消失。
     *
     * `/Filter /JBIG2Decode` 是 PDFBox 判定 `pdImage.suffix == "jb2"` 的依据——这条
     * 测试不需要合法的 JBIG2 编码字节（占位图路径根本不读原始图片数据，只用
     * 宽高），随便塞几个字节能让 `COSStream` 非空即可。
     */
    @Test
    fun `JBIG2图片显示占位图，不再静默消失`() {
        val context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)

        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val cosStream = document.document.createCOSStream()
        cosStream.createOutputStream().use { it.write(byteArrayOf(0x00)) }
        cosStream.setItem(com.tom_roush.pdfbox.cos.COSName.TYPE, com.tom_roush.pdfbox.cos.COSName.XOBJECT)
        cosStream.setItem(com.tom_roush.pdfbox.cos.COSName.SUBTYPE, com.tom_roush.pdfbox.cos.COSName.IMAGE)
        cosStream.setItem(com.tom_roush.pdfbox.cos.COSName.FILTER, com.tom_roush.pdfbox.cos.COSName.getPDFName("JBIG2Decode"))
        // 2000x3000：故意用一个远超屏幕分辨率的尺寸，验证占位图会按长宽比缩小，
        // 不会真的分配一张几千像素见方、内存爆炸的 Bitmap 只为了画几个字。
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.WIDTH, 2000)
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.HEIGHT, 3000)
        cosStream.setInt(com.tom_roush.pdfbox.cos.COSName.BITS_PER_COMPONENT, 1)
        cosStream.setItem(com.tom_roush.pdfbox.cos.COSName.COLORSPACE, com.tom_roush.pdfbox.cos.COSName.DEVICEGRAY)
        val jbig2Image = PDImageXObject(PDStream(cosStream), com.tom_roush.pdfbox.pdmodel.PDResources())
        assertEquals("这条测试的前提假设——PDFBox 按 /Filter 把这张图判定成 jb2，占位图逻辑才会生效", "jb2", jbig2Image.suffix)

        val drawStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page)
        drawStream.drawImage(jbig2Image, 0f, 0f, 200f, 300f)
        drawStream.close()

        val output = File.createTempFile("jbig2-image-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()

        val content = PdfTextExtractor.extractContent(context, output)

        // 占位图算作一张图片正常插入版面（不是"抽取失败/跳过"），只是内容是占位
        // 提示，不是真解出来的画面。
        assertEquals(1, content.images.size)
        val bitmap = content.images.single().bitmap
        assertTrue("占位图应该有正常的正宽高", bitmap.width > 0 && bitmap.height > 0)
        // 原图 2000x3000（长宽比 2:3），占位图应该保持同样的长宽比，且长边远小于
        // 原图（不该真的分配几千像素见方的 Bitmap）。
        assertTrue("占位图长边应该远小于原图，不该真的按原始尺寸分配内存", maxOf(bitmap.width, bitmap.height) < 1000)
        val aspectRatioDiff = kotlin.math.abs(bitmap.width.toFloat() / bitmap.height - 2000f / 3000f)
        assertTrue("占位图应该保留原图的长宽比（误差在 0.05 以内），实际长宽比=${bitmap.width.toFloat() / bitmap.height}", aspectRatioDiff < 0.05f)
    }
}
