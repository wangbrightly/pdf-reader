package app.pdfreader.extract

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
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
 * [PdfTextExtractor.decodeIndexedColorImageOrNull] 的端到端测试——见该函数 KDoc
 * 完整背景（2026-09-03 排查图片重叠拼贴那次真机反馈时顺带发现：PdfBox-Android
 * 把 `/Indexed` 调色板颜色空间误识别成 DeviceRGB，`pdImage.colorSpace.name`
 * 直接报"DeviceRGB"，导致按 3 分量/像素读取只有 1 分量/像素的索引数据，图片
 * 被压扁到约 1/3 高度、颜色也全错）。
 *
 * 构造 fixture 的技巧：PDFBox-Android 没有 `PDIndexed` 这样的便捷 API（这个
 * 版本的公开 API 里根本没有这个类），直接手写 PDF 规范里 Indexed 颜色空间的
 * 标准结构——`/ColorSpace` 是一个四元数组 `[/Indexed, 基色空间, hival, 调色板]`，
 * 调色板用 `COSString`（PDF 规范允许调色板数据是 stream 或 string，真机数据是
 * stream，这里为了测试简单用 string，两种在 [decodeIndexedColorImageOrNull]
 * 里都要处理，见该函数 `when` 分支），跟真机第 30 页第一次发现这个 bug 时
 * 用 `pdfimages -list`/直接读 `COSDictionary` 核对出来的结构完全一致。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorIndexedColorTest {

    /**
     * 4×2 的最小索引色图片，2 色调色板（hival=1）：索引 0=红，索引 1=绿。
     * 像素数据（行主序，每像素 1 字节）：第一行 [0,0,1,1]（左半红、右半绿），
     * 第二行 [1,1,0,0]（左半绿、右半红）——特意设计成"上下颜色相反"，能同时
     * 验证宽度/高度/行顺序/调色板取色四件事，不是随便拼的数字。
     */
    private fun buildIndexedImage(document: PDDocument): PDImageXObject {
        val cosStream = document.document.createCOSStream()
        cosStream.createOutputStream().use {
            it.write(byteArrayOf(0, 0, 1, 1, 1, 1, 0, 0))
        }
        cosStream.setItem(COSName.TYPE, COSName.XOBJECT)
        cosStream.setItem(COSName.SUBTYPE, COSName.IMAGE)
        cosStream.setInt(COSName.WIDTH, 4)
        cosStream.setInt(COSName.HEIGHT, 2)
        cosStream.setInt(COSName.BITS_PER_COMPONENT, 8)

        val colorSpaceArray = COSArray()
        colorSpaceArray.add(COSName.getPDFName("Indexed"))
        colorSpaceArray.add(COSName.DEVICERGB)
        colorSpaceArray.add(COSInteger.get(1))
        val lookup = COSString(byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0)) // 红, 绿
        colorSpaceArray.add(lookup)
        cosStream.setItem(COSName.COLORSPACE, colorSpaceArray)

        return PDImageXObject(PDStream(cosStream), PDResources())
    }

    @Test
    fun `索引色图片解码出正确的宽高和调色板颜色 不再被压扁成错误尺寸`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val indexedImage = buildIndexedImage(document)
        PDPageContentStream(document, page).use { it.drawImage(indexedImage, 0f, 0f, 40f, 20f) }

        val output = File.createTempFile("indexed-color-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()

        val content = PdfTextExtractor.extractContent(context, output)
        assertEquals(1, content.images.size)
        val bitmap = content.images.single().bitmap
        assertEquals("宽度应该是原图宽度，不该被压缩", 4, bitmap.width)
        assertEquals("高度应该是原图高度 2，不该被压扁成 2/3=0（旧 bug 是压到约 1/3）", 2, bitmap.height)

        val red = android.graphics.Color.rgb(255, 0, 0)
        val green = android.graphics.Color.rgb(0, 255, 0)
        assertEquals("第一行左半应该是索引 0 对应的红色", red, bitmap.getPixel(0, 0))
        assertEquals("第一行右半应该是索引 1 对应的绿色", green, bitmap.getPixel(3, 0))
        assertEquals("第二行左半应该是索引 1 对应的绿色（跟第一行颜色相反）", green, bitmap.getPixel(0, 1))
        assertEquals("第二行右半应该是索引 0 对应的红色", red, bitmap.getPixel(3, 1))
    }

    /**
     * 反例：越界索引（脏数据，调色板只有 2 色但像素写了索引 5）不该抛异常或
     * 显示垃圾颜色，应该夹紧到调色板范围内最后一个有效索引。
     */
    @Test
    fun `越界索引不抛异常 夹紧到调色板范围内`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val cosStream = document.document.createCOSStream()
        cosStream.createOutputStream().use { it.write(byteArrayOf(5, 5, 5, 5, 5, 5, 5, 5)) }
        cosStream.setItem(COSName.TYPE, COSName.XOBJECT)
        cosStream.setItem(COSName.SUBTYPE, COSName.IMAGE)
        cosStream.setInt(COSName.WIDTH, 4)
        cosStream.setInt(COSName.HEIGHT, 2)
        cosStream.setInt(COSName.BITS_PER_COMPONENT, 8)
        val colorSpaceArray = COSArray()
        colorSpaceArray.add(COSName.getPDFName("Indexed"))
        colorSpaceArray.add(COSName.DEVICERGB)
        colorSpaceArray.add(COSInteger.get(1))
        colorSpaceArray.add(COSString(byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0)))
        cosStream.setItem(COSName.COLORSPACE, colorSpaceArray)
        val indexedImage = PDImageXObject(PDStream(cosStream), PDResources())

        PDPageContentStream(document, page).use { it.drawImage(indexedImage, 0f, 0f, 40f, 20f) }
        val output = File.createTempFile("indexed-color-oob-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()

        val content = PdfTextExtractor.extractContent(context, output)
        assertEquals(1, content.images.size)
        val bitmap = content.images.single().bitmap
        assertTrue("不该崩溃，应该产出正常宽高的图片", bitmap.width > 0 && bitmap.height > 0)
        val green = android.graphics.Color.rgb(0, 255, 0)
        assertEquals("越界索引 5 应该夹紧到 hival=1（调色板最后一个有效颜色，绿色）", green, bitmap.getPixel(0, 0))
    }

    /**
     * 反例：普通 DeviceRGB 图片（不是 Indexed）不该被这条新分支误伤——确认判断
     * 条件精确匹配 `/ColorSpace` 数组第一项是 `/Indexed`，不会对其它颜色空间
     * 产生任何影响。
     */
    @Test
    fun `非索引色的普通图片不受影响`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val baseBytes = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("tiny.png")?.readBytes(),
        ) { "找不到测试 fixture：src/test/resources/tiny.png" }
        val normalImage = PDImageXObject.createFromByteArray(document, baseBytes, "normal")
        PDPageContentStream(document, page).use { it.drawImage(normalImage, 0f, 0f, 40f, 30f) }

        val output = File.createTempFile("non-indexed-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()

        val content = PdfTextExtractor.extractContent(context, output)
        assertEquals(1, content.images.size)
        val bitmap = content.images.single().bitmap
        assertTrue("普通图片应该正常出图，不受索引色分支影响", bitmap.width > 0 && bitmap.height > 0)
    }
}
