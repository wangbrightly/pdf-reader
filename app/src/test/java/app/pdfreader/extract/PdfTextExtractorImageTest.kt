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
    fun `图片插在第一段之后、第二段之前`() {
        val content = extractFixtureContent()
        // fixture 结构：第一段 → 图片 → 第二段 → 第三段，图片应该插在段落下标 0 之后。
        assertEquals(0, content.images.single().afterParagraphIndex)
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
     * 用 PDFBox 底层 API 直接拼一个"页面资源里有一张正常图片 + 一张损坏图片"的文档：
     * 损坏图片的手法是把 Filter 声明成 `/DCTDecode`（JPEG）但塞入完全不是 JPEG 的字节，
     * `PDImageXObject.getImage()` 解码时会抛 `IOException`，用来验证
     * [PdfTextExtractor] 对单张图片抽取失败的兜底处理。文字用英文是因为标准 14 字体
     * （Helvetica）不支持中文字形，写中文会在 `showText` 时直接抛异常——这里只是要
     * 一段随便什么占位文字来验证"图片抽取失败不连累文字抽取"，不需要是中文。
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

        val validImage = createValidPngImageXObject(document)
        page.resources.add(validImage)

        val corruptImage = createCorruptImageXObject(document)
        page.resources.add(corruptImage)

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
        val out = cosStream.createOutputStream()
        out.write(ByteArray(32) { it.toByte() }) // 完全不是合法 JPEG 数据的随机字节
        out.close()
        cosStream.setItem(
            com.tom_roush.pdfbox.cos.COSName.TYPE,
            com.tom_roush.pdfbox.cos.COSName.XOBJECT,
        )
        cosStream.setItem(
            com.tom_roush.pdfbox.cos.COSName.SUBTYPE,
            com.tom_roush.pdfbox.cos.COSName.IMAGE,
        )
        cosStream.setItem(com.tom_roush.pdfbox.cos.COSName.FILTER, com.tom_roush.pdfbox.cos.COSName.DCT_DECODE)
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
}
