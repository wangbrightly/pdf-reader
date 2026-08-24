package app.pdfreader.extract

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * [PdfTextExtractor]（内部的 `decodeJpegWithNativeSubsampling`，通过 [PdfTextExtractor
 * .extractContent] 端到端触发）针对大尺寸 JPEG 图片走安卓原生降采样解码这条路径的测试
 * ——见该函数 KDoc"第三次尝试"一节的完整背景（前两次靠 PdfBox-Android 自己的解码
 * 路径都失败了）。
 *
 * fixture `large-quadrant.jpg`（4500×3000，四象限红/绿/蓝/黄测试图，quality=85 的真实
 * JPEG，Python Pillow 生成）特意选了超过 [PdfTextExtractor] 内 `MAX_IMAGE_DIMENSION_PX`
 * （2026-08-22 从 2000px 调到 3000px，见该常量 KDoc"分辨率优化"一节——这份 fixture
 * 也跟着从原来的 3000×2000 重新生成成 4500×3000，保持"长边超过阈值 1.5 倍、
 * 触发一次减半降采样"这个测试意图不变）的尺寸，触发 `subsamplingFactor` 算出
 * 2 倍降采样。
 *
 * ## 只验证解码尺寸，不验证像素颜色——Robolectric 的 `BitmapFactory` 影子实现不可靠
 *
 * 最初这条测试还想顺带验证"降采样之后四个象限颜色还对不对"（左上红、右上绿……），
 * 程序化验证发现：Robolectric 环境下 `BitmapFactory.decodeByteArray` 解码这份真实
 * JPEG 字节，返回的 `Bitmap` 尺寸是对的（按 2 倍降采样了），但四个角
 * 采样出来的颜色全部是纯红——不是真的解码出了图片内容，是这个环境下的影子实现在
 * 尺寸计算上是真的（用了真实的 JPEG 头信息），像素内容却是假的。这是 Robolectric
 * 对 Canvas/`BitmapFactory` 这类底层图形解码的影子实现精度限制，跟
 * [PdfTextExtractorImageOrientationTest] 类注释里记录的"`ImageIO` 编的 PNG 经
 * `getImage()` 颜色错乱"是同一类问题（影子实现在像素级解码/绘制这层不可信），但
 * 触发条件不同，是独立发现的一个新坑。
 *
 * 所以这条测试只断言"解码结果的尺寸确实变小了"（这一步的影子实现是可信的，验证过
 * 手动交叉核对：`subsamplingFactor` 算出来的倍数和最终 `Bitmap` 尺寸吻合），不断言
 * 颜色对不对——颜色/内容是否解码正确这件事已经在真机上装机验证过（见对应 commit
 * 记录），不在这里重复验证一个 Robolectric 验证不了的事情。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorJpegSubsamplingTest {

    private fun loadJpegBytes(): ByteArray = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("large-quadrant.jpg")?.readBytes(),
    ) { "找不到测试 fixture：src/test/resources/large-quadrant.jpg" }

    private fun buildDocumentWithLargeJpeg(): File {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)

        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val imageXObject = PDImageXObject.createFromByteArray(document, loadJpegBytes(), "large")
        assertEquals("createFromByteArray 应该把这份 JPEG 字节识别成 jpg 后缀", "jpg", imageXObject.suffix)

        PDPageContentStream(document, page).use { stream ->
            stream.drawImage(imageXObject, 0f, 0f, 100f, 100f)
        }

        val output = File.createTempFile("large-jpeg-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()
        return output
    }

    @Test
    fun `超过阈值的大 JPEG 解码结果按 2 倍降采样，尺寸明显小于原图`() {
        val context = RuntimeEnvironment.getApplication()
        val content = PdfTextExtractor.extractContent(context, buildDocumentWithLargeJpeg())

        assertEquals(1, content.images.size)
        val bitmap = content.images.single().bitmap
        // 原图 4500x3000，subsamplingFactor 算出来是 2 倍——解码结果应该在 2250x1500
        // 附近（BitmapFactory 的 inSampleSize 不保证精确到像素，允许小误差）。
        assertTrue("解码后宽度应该明显小于原图 4500px，实际是 ${bitmap.width}", bitmap.width in 1900..2500)
        assertTrue("解码后高度应该明显小于原图 3000px，实际是 ${bitmap.height}", bitmap.height in 1300..1700)
    }

    @Test
    fun `不需要降采样的小 JPEG 走安全回退路径，尺寸不变`() {
        // 小图（长边 < 阈值）走 decodeJpegWithNativeSubsampling 时 subsampling<=1，
        // 函数返回 null，调用方回退到 pdImage.image——用一张明显小于阈值的真实 JPEG
        // （400×300，同一个 Python Pillow 脚本生成的小尺寸版本）验证这条回退路径
        // 接得上、图片还在，不需要验证具体解码倍数（本来就没有降采样）。
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        val smallJpegBytes = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("small-quadrant.jpg")?.readBytes(),
        ) { "找不到测试 fixture：src/test/resources/small-quadrant.jpg" }
        val imageXObject = PDImageXObject.createFromByteArray(document, smallJpegBytes, "small")
        PDPageContentStream(document, page).use { it.drawImage(imageXObject, 0f, 0f, 40f, 30f) }
        val output = File.createTempFile("small-jpeg-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()

        val content = PdfTextExtractor.extractContent(context, output)
        assertEquals(1, content.images.size)
        assertTrue(content.images.single().bitmap.width > 0)
    }

    @Test
    fun `CMYK JPEG 不走原生降采样路径，也不回退到 pdImage-image，由自己的解码器真正解出`() {
        // 2026-08-20 最初的修法：安卓 BitmapFactory 解码 CMYK（4 通道）JPEG 有已知
        // 老毛病，把 4 通道数据当 3 通道 RGB 硬读，视觉上是对角线彩色噪点花屏——见
        // decodeJpegWithNativeSubsampling KDoc"已修"一节完整背景，当时的结论是
        // "挡住快速路径、回退到 pdImage.image 就能保住颜色正确"。
        //
        // 2026-08-22 推翻这条结论（真机反馈修复）：用户反馈另一本书"图片显示颜色
        // 不对"→"背景变为黑色"，装机诊断发现回退到的 pdImage.image 对**这批**
        // CMYK JPEG 解码结果网格采样纯黑占比接近 100%——不是花屏，是纯黑。进一步
        // 验证：绕开 PdfBox-Android、直接用安卓原生 BitmapFactory 解码同一份原始
        // JPEG 字节（不带降采样参数）结果同样纯黑。两条独立解码路径（PdfBox 纯
        // Java 实现 + 安卓自带 Skia/libjpeg-turbo 原生实现）给出一致的失败结果，
        // 证明"pdImage.image 能正确处理 CMYK"这个假设是错的，至少对这类 CMYK/
        // YCCK 编码变体不成立。当时改成跟 JBIG2 一样显示诚实的占位图。
        //
        // 2026-08-23 再进一步：手写的 [JpegDecoder] 接进取代了占位图——范围内
        // （真机确认过的数据形状：4 分量、Adobe transform=0、1x1 采样、baseline）
        // 的 CMYK JPEG 现在真正解码出原始像素。这条测试的断言因此从"占位图小尺寸"
        // 翻转成"解码结果是原图尺寸"：既验证没走原生降采样路径（那条路径对
        // 4 分量直接返回），也没回退到 `pdImage.image`（那条路径在 Robolectric
        // 里解不出这种数据），只有 [JpegDecoder] 这条自己的路径能产出原尺寸结果。
        //
        // 注意：这里不能用 `imageXObject.colorSpace.numberOfComponents` 验证 fixture
        // 确实是 CMYK——反编译确认过 `PDImageXObject.createFromByteArray` 内部固定
        // 用 `PDDeviceRGB.INSTANCE`，不管字节实际是什么颜色空间都报 3。这里改成
        // 直接断言 fixture 文件本身的字节，跟 JpegComponentCountTest 用的是同一份
        // fixture、同一个判断依据。
        //
        // fixture `cmyk-book-noinv.jpg`（1725×955，真机从真实教科书取回的 4 通道
        // CMYK，结构在 [JpegDecoder] 范围内，也在 [JpegDecoder.MAX_CMYK_JPEG_PIXELS]
        // 尺寸上限之内——2026-08-24 之前这里用的是 `cmyk-quadrant.jpg`
        // （3000×2000），真机装机撞出 OOM 加了尺寸上限之后这份 fixture 超出了
        // 新上限，换成这份同样是真机数据、但在范围内的 fixture，见
        // [JpegDecoderCrossValidationTest] 里对应的尺寸上限说明。
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        val cmykJpegBytes = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("cmyk-book-noinv.jpg")?.readBytes(),
        ) { "找不到测试 fixture：src/test/resources/cmyk-book-noinv.jpg" }
        assertEquals("这份 fixture 应该是 4 通道 CMYK，不是的话这条测试没测到目标场景", 4, JpegComponentCount.of(cmykJpegBytes))
        val imageXObject = PDImageXObject.createFromByteArray(document, cmykJpegBytes, "cmyk")
        assertEquals("createFromByteArray 应该把这份 JPEG 字节识别成 jpg 后缀", "jpg", imageXObject.suffix)
        PDPageContentStream(document, page).use { it.drawImage(imageXObject, 0f, 0f, 100f, 100f) }
        val output = File.createTempFile("cmyk-jpeg-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()

        val content = PdfTextExtractor.extractContent(context, output)
        assertEquals(1, content.images.size)
        val bitmap = content.images.single().bitmap
        // [JpegDecoder] 按 SOF 声明的原始尺寸输出（1725x955）——如果漏拦截掉进
        // 占位图路径，长边会是 400；掉进常规解码路径，这个环境下根本解不出 4 分量
        // 数据。原尺寸是"走了自己的解码器"最强的间接证据。
        assertEquals("解码结果宽度应该是原图尺寸", 1725, bitmap.width)
        assertEquals("解码结果高度应该是原图尺寸", 955, bitmap.height)
    }
}
