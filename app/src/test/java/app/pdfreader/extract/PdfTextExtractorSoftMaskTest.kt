package app.pdfreader.extract

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * [PdfTextExtractor] 内部 `decodeJpegSoftMaskCompositeOrNull` 的测试——见该函数
 * KDoc 完整背景（2026-08-29 真机反馈"这本书的页面该显示成图片却把文字和图片分开
 * 显示了"，追出来是底图+蒙版都是 JPEG 编码的图片解码成纯黑，不是排版问题）。
 *
 * 跟 [PdfTextExtractorJpegSubsamplingTest] 同样的限制：Robolectric 的
 * `BitmapFactory` 影子实现尺寸可信、像素内容不可信，这里只能验证"走了正确的
 * 代码路径"，验证不了"合成结果真的不是纯黑"（那一步已经真机验证过）。用尺寸当
 * 路径信号：[decodeJpegSoftMaskCompositeOrNull] 不做降采样，直接按底图原始像素
 * 尺寸解码——特意选一张超过 [PdfTextExtractor] 降采样阈值的大图当底图
 * （`large-quadrant.jpg`，4500x3000），三条路径会给出三种不同尺寸：占位图裁到
 * 长边 400px；原生降采样路径按 2 倍减半到约 2250x1500；只有这条新路径会保持
 * 4500x3000 原始尺寸——尺寸精确匹配原图，是"确实走了这条新路径"最直接的证据。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorSoftMaskTest {

    @Test
    fun `底图和蒙版都是 JPEG 时走手动合成路径，按原始尺寸解码而不是回退到占位图或降采样`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val baseBytes = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("large-quadrant.jpg")?.readBytes(),
        ) { "找不到测试 fixture：src/test/resources/large-quadrant.jpg" }
        val maskBytes = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("small-quadrant.jpg")?.readBytes(),
        ) { "找不到测试 fixture：src/test/resources/small-quadrant.jpg" }

        val baseImage = PDImageXObject.createFromByteArray(document, baseBytes, "base")
        val maskImage = PDImageXObject.createFromByteArray(document, maskBytes, "mask")
        assertEquals("createFromByteArray 应该把底图字节识别成 jpg 后缀", "jpg", baseImage.suffix)
        assertEquals("createFromByteArray 应该把蒙版字节识别成 jpg 后缀", "jpg", maskImage.suffix)
        // PDFBox-Android 这个版本的公开 API 没有 setSoftMask，跟本类其它地方
        // （见 PageContentStreamEngine.drawImage JPX 分支）一样直接操作底层
        // COSDictionary——PDF 规范里 /SMask 就是这样挂在图片 XObject 字典上的。
        baseImage.cosObject.setItem(COSName.SMASK, maskImage)

        PDPageContentStream(document, page).use { it.drawImage(baseImage, 0f, 0f, 100f, 100f) }
        val output = File.createTempFile("softmask-jpeg-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()

        val content = PdfTextExtractor.extractContent(context, output)
        assertEquals(1, content.images.size)
        val bitmap = content.images.single().bitmap
        assertEquals("解码结果宽度应该是底图原始尺寸（没有走降采样/占位图）", 4500, bitmap.width)
        assertEquals("解码结果高度应该是底图原始尺寸（没有走降采样/占位图）", 3000, bitmap.height)
    }

    @Test
    fun `蒙版不是 JPEG（比如 PNG）时不受影响，走原有的 pdImage-image 合成路径`() {
        // 真机数据证实蒙版是 PNG/JPX 时 pdImage.image 合成本来就正常——这条测试
        // 确认新加的分支判断条件够窄，不会误伤这两种本来就没问题的组合。
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val baseBytes = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("small-quadrant.jpg")?.readBytes(),
        ) { "找不到测试 fixture：src/test/resources/small-quadrant.jpg" }
        val baseImage = PDImageXObject.createFromByteArray(document, baseBytes, "base")
        assertEquals("jpg", baseImage.suffix)

        PDPageContentStream(document, page).use { it.drawImage(baseImage, 0f, 0f, 40f, 30f) }
        val output = File.createTempFile("no-softmask-jpeg-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()

        val content = PdfTextExtractor.extractContent(context, output)
        assertEquals(1, content.images.size)
        assertEquals(400, content.images.single().bitmap.width)
    }
}
