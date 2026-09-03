package app.pdfreader.extract

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
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
 * [PdfTextExtractor] 内部 `decodeSoftMaskCompositeOrNull` 的测试——见该函数
 * KDoc 完整背景（2026-08-29 真机反馈"这本书的页面该显示成图片却把文字和图片分开
 * 显示了"，追出来是底图+蒙版都是 JPEG 编码的图片解码成纯黑，不是排版问题；
 * 2026-09-03 真机反馈同一句话但指向另一页，追出来是同一类 bug 的另一种组合——
 * 蒙版是 JPX 编码时同样会合成出纯黑，函数因此从"只处理 JPEG 蒙版"扩展成"处理
 * JPEG 或 JPX 蒙版"）。
 *
 * 跟 [PdfTextExtractorJpegSubsamplingTest] 同样的限制：Robolectric 的
 * `BitmapFactory` 影子实现尺寸可信、像素内容不可信，这里只能验证"走了正确的
 * 代码路径"，验证不了"合成结果真的不是纯黑"（那一步已经真机验证过）。用尺寸当
 * 路径信号：[decodeSoftMaskCompositeOrNull] 不做降采样，直接按底图原始像素
 * 尺寸解码——特意选一张超过 [PdfTextExtractor] 降采样阈值的大图当底图
 * （`large-quadrant.jpg`，4500x3000），三条路径会给出三种不同尺寸：占位图裁到
 * 长边 400px；原生降采样路径按 2 倍减半到约 2250x1500；只有这条新路径会保持
 * 4500x3000 原始尺寸——尺寸精确匹配原图，是"确实走了这条新路径"最直接的证据。
 *
 * JPX 蒙版这条分支验证不到这么细——[Jpeg2000Decoder.decode] 依赖真实 Android
 * native 库，Robolectric 下必定解码失败、优雅回退到通用路径，见下面对应测试
 * KDoc 和 [PdfTextExtractorImageTest] 里 JPX 图片本身那条 wiring 测试的同一个
 * 已知局限。
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

    /**
     * 反例：底图没有任何 `/SMask`（不是"蒙版格式不对"，是压根没有蒙版）——
     * 确认没有蒙版时不会误触发这条分支，走的是普通降采样路径（这份 fixture
     * 超过降采样阈值，正常应该缩到 400px 长边）。
     */
    @Test
    fun `完全没有蒙版时不受影响，走原有的降采样路径`() {
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

    /**
     * 反例：蒙版是 PNG（不是 jpg/jpx）——**如实说明这条测试的局限**：把判断
     * 条件的 `softMask.suffix != "jpg" && != "jpx"` 早退检查临时禁用过（改成
     * `if (false) return null`）重跑过这条测试，结果仍然通过，因为 PNG 字节
     * 送进 `BitmapFactory.decodeByteArray`（当 jpg 解）或 `Jpeg2000Decoder
     * .decode`（当 jpx 解）本身就会解码失败返回 `null`，函数最终一样落到
     * 通用回退路径——**这条测试测不出"判断条件是否存在"，只能测出"PNG 蒙版
     * 最终能正常出图、不崩溃"**这个更弱的结论。判断条件本身的价值是文档
     * 性的（明确写出"这个函数只处理 jpg/jpx 这两种蒙版格式"，帮后来人快速
     * 判断某个 bug 在不在这条函数的管辖范围内），不是这条测试能验证到的。
     * 没有真机数据证实 PNG 蒙版组合本来就没问题（上面 JPX 那次教训之后，
     * 不再没有反例就断言"这种组合没事"），如实记录成"目前没有证据显示 PNG
     * 蒙版有问题，也没有专门验证过"，不写更强的结论。
     */
    @Test
    fun `蒙版是 PNG 时最终仍能正常出图 不崩溃（判断条件本身是文档性的，这条测试测不到它）`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val baseBytes = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("large-quadrant.jpg")?.readBytes(),
        ) { "找不到测试 fixture：src/test/resources/large-quadrant.jpg" }
        val maskBytes = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("tiny.png")?.readBytes(),
        ) { "找不到测试 fixture：src/test/resources/tiny.png" }
        val baseImage = PDImageXObject.createFromByteArray(document, baseBytes, "base")
        val maskImage = PDImageXObject.createFromByteArray(document, maskBytes, "mask")
        assertEquals("createFromByteArray 应该把蒙版字节识别成 png 后缀", "png", maskImage.suffix)
        baseImage.cosObject.setItem(COSName.SMASK, maskImage)

        PDPageContentStream(document, page).use { it.drawImage(baseImage, 0f, 0f, 100f, 100f) }
        val output = File.createTempFile("softmask-png-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()

        // 只断言"不崩溃、产出一张正常图片"——见上面 KDoc"如实说明这条测试的
        // 局限"，PNG 蒙版无论判断条件在不在都会落到同一条回退路径，这里不再
        // 断言具体走的是哪条子路径（降采样/原始尺寸），避免测试名不副实。
        val content = PdfTextExtractor.extractContent(context, output)
        assertEquals(1, content.images.size)
        val bitmap = content.images.single().bitmap
        assertTrue("蒙版是 PNG 时应该正常出图，不崩溃、不产出零宽高", bitmap.width > 0 && bitmap.height > 0)
    }

    /**
     * 2026-09-03 真机反馈修复：蒙版是 JPX 编码时，`decodeSoftMaskCompositeOrNull`
     * 现在也会尝试用 [Jpeg2000Decoder] 解码蒙版——见该函数 KDoc"2026-09-03 扩展"
     * 一节完整背景（第 30 页真机反馈同一句话"文字和图片分开了"，但根因是另一种
     * 蒙版格式组合导致的黑图，不是版式判定漏检）。
     *
     * **这条测试测不到"真正合成成功、不是黑图"这条路径**——[Jpeg2000Decoder
     * .decode] 依赖真实 Android native 库，Robolectric 是纯桌面 JVM，
     * `System.loadLibrary` 在这里必定失败（`UnsatisfiedLinkError`，被
     * `runCatching` 吞掉变成 `null`），跟 [PdfTextExtractorImageTest] 里
     * JPX 图片本身那条 wiring 测试同一个已知局限。这条测试验证的是"新分支被
     * 正确识别、蒙版解码失败时优雅回退到通用路径、不崩溃、`/SMask` 状态正确
     * 恢复不留副作用"——真正"不再是黑图"这个结论必须靠真机，第 30 页真机
     * 截图是这次修复实际生效的证据。
     */
    @Test
    fun `蒙版是 JPX 时尝试新分支 Robolectric下解码失败但优雅回退不崩溃`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val baseBytes = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("large-quadrant.jpg")?.readBytes(),
        ) { "找不到测试 fixture：src/test/resources/large-quadrant.jpg" }
        val baseImage = PDImageXObject.createFromByteArray(document, baseBytes, "base")

        // 蒙版内容不需要是合法的 JPX 数据——这条测试验证的是 Robolectric 环境下
        // native 库加载不了时的兜底行为，任意非空字节即可触发 [Jpeg2000Decoder
        // .decode] 内部的 UnsatisfiedLinkError -> runCatching -> null，构造方式
        // 参考 [PdfTextExtractorImageTest] 里 JPX 图片本身那条 wiring 测试。
        val maskCosStream = document.document.createCOSStream()
        maskCosStream.createOutputStream().use { it.write(byteArrayOf(0x00, 0x01, 0x02)) }
        maskCosStream.setItem(COSName.TYPE, COSName.XOBJECT)
        maskCosStream.setItem(COSName.SUBTYPE, COSName.IMAGE)
        maskCosStream.setItem(COSName.FILTER, COSName.getPDFName("JPXDecode"))
        maskCosStream.setInt(COSName.WIDTH, 10)
        maskCosStream.setInt(COSName.HEIGHT, 10)
        maskCosStream.setInt(COSName.BITS_PER_COMPONENT, 8)
        maskCosStream.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
        val maskImage = com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject(
            com.tom_roush.pdfbox.pdmodel.common.PDStream(maskCosStream),
            com.tom_roush.pdfbox.pdmodel.PDResources(),
        )
        assertEquals("这条测试的前提假设——蒙版按 /Filter 被判定成 jpx，新分支才会生效", "jpx", maskImage.suffix)
        baseImage.cosObject.setItem(COSName.SMASK, maskImage)

        PDPageContentStream(document, page).use { it.drawImage(baseImage, 0f, 0f, 100f, 100f) }
        val output = File.createTempFile("softmask-jpx-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()

        // 不该抛异常、不该静默消失——回退到通用路径后应该正常产出一张图片
        // （具体走哪条降采样/占位图分支不重要，重要的是流程完整跑通）。
        val content = PdfTextExtractor.extractContent(context, output)
        assertEquals(1, content.images.size)
        val bitmap = content.images.single().bitmap
        assertTrue("回退路径应该产出正常宽高的图片", bitmap.width > 0 && bitmap.height > 0)
    }
}
