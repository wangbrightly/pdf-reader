package app.pdfreader.extract

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
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
 * CMYK JPEG 从"占位图"升级为"真正解码显示"这条链路的提取层测试。
 *
 * 背景：4 分量 CMYK/YCCK JPEG 在这台设备上走 PdfBox-Android 自己的解码和安卓
 * 原生 `BitmapFactory` 都解出纯黑（两条独立路径真机验证过，见
 * [PdfTextExtractor.decodeCmykJpegOrNull] KDoc），因此自己手写了支持 Adobe 反色
 * CMYK 约定的 [JpegDecoder]。解码器本身的正确性由 [JpegDecoderCrossValidationTest]
 * 对着 Pillow（libjpeg-turbo）参考逐像素验证；**这个文件验证的是"解码器真的被
 * 接进了抽取管线"**——解码结果进了 `extractContent` 的 `images`，占位图只在解码器
 * 明确拒绝（范围外数据）时才出现。
 *
 * fixture 用 PDFBox 底层 API 直接拼（跟 [PdfTextExtractorImageTest] 同一套路）：
 * 把真实 CMYK JPEG 字节原样写进 COSStream，声明 `/Filter /DCTDecode` +
 * `/ColorSpace /DeviceCMYK`——`createInputStream(listOf("DCTDecode","DCT"))` 拿到的
 * 就是原始 JPEG 字节，跟真机文档里图片的取字节路径完全一致。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorCmykJpegTest {

    private fun loadBytes(name: String): ByteArray = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(name)?.readBytes(),
    ) { "找不到测试 fixture：src/test/resources/$name" }

    /** 把一份 JPEG 字节按"真机文档同款字典声明"嵌进单页 PDF 并画出来。 */
    private fun buildDocumentWithCmykJpeg(jpegBytes: ByteArray, width: Int, height: Int): File {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)

        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val cosStream = document.document.createCOSStream()
        cosStream.createOutputStream().use { it.write(jpegBytes) }
        cosStream.setItem(COSName.TYPE, COSName.XOBJECT)
        cosStream.setItem(COSName.SUBTYPE, COSName.IMAGE)
        cosStream.setInt(COSName.WIDTH, width)
        cosStream.setInt(COSName.HEIGHT, height)
        cosStream.setInt(COSName.BITS_PER_COMPONENT, 8)
        cosStream.setItem(COSName.FILTER, COSName.DCT_DECODE)
        cosStream.setItem(COSName.COLORSPACE, COSName.DEVICECMYK)
        val image = PDImageXObject(PDStream(cosStream), PDResources())
        // 前提自检：PDFBox 必须按 /Filter 把这张图判定成 jpg，CMYK 拦截/解码链路才会生效。
        assertEquals("jpg", image.suffix)

        val drawStream = PDPageContentStream(document, page)
        drawStream.drawImage(image, 0f, 0f, 200f, 150f)
        drawStream.close()

        val output = File.createTempFile("cmyk-jpeg-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()
        return output
    }

    private fun pixelRgb(bitmap: android.graphics.Bitmap, x: Int, y: Int): Triple<Int, Int, Int> {
        val argb = bitmap.getPixel(x, y)
        return Triple(
            android.graphics.Color.red(argb),
            android.graphics.Color.green(argb),
            android.graphics.Color.blue(argb),
        )
    }

    /**
     * 核心接线测试：范围内（真机确认过的数据形状）的 CMYK JPEG 必须真正解码出
     * 内容，不再是占位图。用 64x64 四象限纯色图（左上红/右上绿/左下蓝/右下黄），
     * 逐象限断言颜色——占位图是 #EEEEEE 浅灰底加一行提示文字，四个角不可能出现
     * 高饱和的红/绿/蓝/黄；反过来只要解码结果有一处接错（反色约定漏了、CMYK→RGB
     * 公式错了），颜色断言会立刻失败，不是"有图就算过"的粗粒度检查。
     */
    @Test
    fun `范围内的CMYK JPEG真正解码出内容 不再是占位图`() {
        val context = RuntimeEnvironment.getApplication()
        val doc = buildDocumentWithCmykJpeg(loadBytes("cmyk-quadrant-64.jpg"), 64, 64)

        val content = PdfTextExtractor.extractContent(context, doc)

        assertEquals(1, content.images.size)
        val bitmap = content.images.single().bitmap
        assertEquals("解码结果应该是原始像素尺寸，不是占位图的缩放尺寸", 64, bitmap.width)
        assertEquals(64, bitmap.height)

        val (r1, g1, b1) = pixelRgb(bitmap, 16, 16)
        assertTrue("左上应该是红：($r1,$g1,$b1)", r1 > g1 + 40 && r1 > b1 + 40)
        val (r2, g2, b2) = pixelRgb(bitmap, 48, 16)
        assertTrue("右上应该是绿：($r2,$g2,$b2)", g2 > r2 + 40 && g2 > b2 + 40)
        val (r3, g3, b3) = pixelRgb(bitmap, 16, 48)
        assertTrue("左下应该是蓝：($r3,$g3,$b3)", b3 > r3 + 40 && b3 > g3 + 40)
        val (r4, g4, b4) = pixelRgb(bitmap, 48, 48)
        assertTrue("右下应该是黄：($r4,$g4,$b4)", r4 > b4 + 40 && g4 > b4 + 40)
    }

    /**
     * 范围外回归保护：把四象限图的 Adobe APP14 `transform` 字节从 0 改成 2
     * （YCCK 变体），[JpegDecoder] 会明确拒绝（本地造不出可信的 transform=2 参考
     * 数据，见其类 KDoc"范围"一节）——这时必须退回诚实的占位图，而不是漏过拦截、
     * 掉进 `pdImage.image` 那条会产出纯黑块的路径。
     *
     * APP14 载荷结构：`"Adobe"(5字节) + version(2) + flags0(2) + flags1(2) +
     * transform(1)`——transform 在 `"Adobe"` 字面量起始偏移 +11 处。
     */
    @Test
    fun `范围外的CMYK JPEG(如YCCK) 解码器拒绝时退回占位图 不掉进纯黑路径`() {
        val context = RuntimeEnvironment.getApplication()
        val bytes = loadBytes("cmyk-quadrant-64.jpg")
        val adobeIndex = findAdobeTagIndex(bytes)
        bytes[adobeIndex + 11] = 2 // transform: 0（反色 CMYK）→ 2（YCCK）

        val doc = buildDocumentWithCmykJpeg(bytes, 64, 64)
        val content = PdfTextExtractor.extractContent(context, doc)

        assertEquals(1, content.images.size)
        val bitmap = content.images.single().bitmap
        // Robolectric 环境下占位图的 Canvas 绘制不会真的写入像素（影子实现不栅格
        // 化），不能像真机那样靠"浅灰底色"识别占位图——改用占位图的**特征尺寸**
        // 判定：占位图长边固定缩放到 400（小图反而被放大，见
        // createUnsupportedImagePlaceholder），64x64 的原图对应的占位图是 400x400；
        // 如果拦截漏了、掉进常规解码路径，出来的要么是 64x64 要么整张图被跳过
        // （images 为空），两种都过不了这条断言。
        assertEquals("占位图长边应该是固定的 400，实际=${bitmap.width}x${bitmap.height}", 400, maxOf(bitmap.width, bitmap.height))
        assertTrue(
            "占位图应保持原图长宽比（1:1），实际=${bitmap.width}x${bitmap.height}",
            kotlin.math.abs(bitmap.width.toFloat() / bitmap.height - 1f) < 0.05f,
        )
    }

    private fun findAdobeTagIndex(bytes: ByteArray): Int {
        val tag = byteArrayOf('A'.code.toByte(), 'd'.code.toByte(), 'o'.code.toByte(), 'b'.code.toByte(), 'e'.code.toByte())
        outer@ for (i in 0..bytes.size - tag.size) {
            for (j in tag.indices) {
                if (bytes[i + j] != tag[j]) continue@outer
            }
            return i
        }
        throw IllegalStateException("fixture 里没找到 Adobe APP14 标记，测试前提不成立")
    }
}
