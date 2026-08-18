package app.pdfreader.extract

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * [PdfTextExtractor.extractContent] 大纲（目录）抽取部分的单元测试。
 *
 * API 依据：`PDDocument.getDocumentCatalog().getDocumentOutline()` 返回可能为 `null`
 * 的 `PDDocumentOutline`；递归用 `PDOutlineNode.children()`（`Iterable<PDOutlineItem>`，
 * 内部就是按 `getFirstChild()`/`getNextSibling()` 遍历）；每个 `PDOutlineItem.getTitle()`
 * 可能为 `null`；`PDOutlineItem.findDestinationPage(PDDocument)` 解析目标页——已读
 * PdfBox-Android 上游源码确认它的行为：destination 为 `null`（且没有可用的 GoTo action）
 * 时直接返回 `null`；命名目标（`PDNamedDestination`）在文档编目里查不到时也返回
 * `null`；只有遇到"未知的 destination 类型"这一种情况会抛 `IOException`。这两类"解析
 * 不出目标页"的情况都不应该让整份文档的抽取失败——见下方"目标页解析不出来的目录项
 * 被跳过"用例。
 *
 * fixture `sample-with-outline.pdf` 不是用 Chromium/Puppeteer 生成的——**先用 PyMuPDF
 * (fitz) 实测验证过**：Puppeteer 的 `page.pdf()`（Chromium 打印）不会把 HTML 里的
 * `<h1>`/`<h2>` 标题转成 PDF 大纲/书签，生成出来的 PDF 用 `fitz.open(...).get_toc()`
 * 结果是空列表——这条路子生成不出带大纲的 fixture。改用 PDFBox 本身在 JVM/Robolectric
 * 侧直接构造：3 页正文，程序化搭一份两级大纲——
 * 第一章（页 1，深度 0） / 1.1 小节（页 1，深度 1） / 1.2 小节（页 2，深度 1） /
 * 第二章（页 3，深度 0）——构造完之后同样用 PyMuPDF 的 `get_toc()` 交叉核对过层级、
 * 标题、页码都正确，才把生成出的二进制存进 `src/test/resources/`（生成用的一次性
 * 小工具本身没有提交进仓库，和其它 fixture 只记录生成方式、不提交生成脚本是同一个
 * 惯例）。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorOutlineTest {

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

    private fun extractFixtureContent(fixtureName: String): PdfContent {
        val context = RuntimeEnvironment.getApplication()
        return PdfTextExtractor.extractContent(context, loadFixtureFile(fixtureName))
    }

    @Test
    fun `带大纲的 PDF 抽取出层级、标题、页码都正确的目录`() {
        val content = extractFixtureContent("sample-with-outline.pdf")

        assertEquals(
            listOf(
                OutlineEntry(title = "第一章", pageNumber = 1, depth = 0),
                OutlineEntry(title = "1.1 小节", pageNumber = 1, depth = 1),
                OutlineEntry(title = "1.2 小节", pageNumber = 2, depth = 1),
                OutlineEntry(title = "第二章", pageNumber = 3, depth = 0),
            ),
            content.outline,
        )
    }

    @Test
    fun `每个段落所在的页码一并暴露出来，供目录跳转换算展示块下标`() {
        val content = extractFixtureContent("sample-with-outline.pdf")
        // fixture 3 页，每页恰好一段文字，段落顺序应和页码顺序一致。
        assertEquals(3, content.paragraphPages.size)
        assertEquals(listOf(1, 2, 3), content.paragraphPages)
    }

    @Test
    fun `没有大纲的 PDF 抽取结果里目录列表为空（不崩溃，如实反映没有大纲这个事实）`() {
        for (fixture in listOf("sample-chinese.pdf", "sample-with-image.pdf", "sample-with-table.pdf")) {
            val content = extractFixtureContent(fixture)
            assertTrue("$fixture 不应该有大纲，但 outline=${content.outline}", content.outline.isEmpty())
        }
    }

    @Test
    fun `目标页解析不出来的目录项被跳过，不影响其它目录项，也不让整份文档抽取失败`() {
        val context = RuntimeEnvironment.getApplication()
        val brokenFile = buildDocumentWithOneResolvableAndOneUnresolvableOutlineItem()

        val content = PdfTextExtractor.extractContent(context, brokenFile)

        // 能解析的那一项正常保留；指向不存在的命名目标（PDNamedDestination 在文档编目
        // 查不到）的那一项被跳过——见类 KDoc 引用的上游源码行为。
        assertEquals(listOf(OutlineEntry(title = "可以跳转的标题", pageNumber = 1, depth = 0)), content.outline)
        // 大纲解析失败不应该连累文字抽取。
        assertEquals(listOf("page one text"), content.paragraphs)
    }

    /**
     * 用 PDFBox 底层 API 拼一份"两个大纲项，一个能正常解析、一个指向不存在的命名目标"
     * 的文档——手法和 [PdfTextExtractorImageTest.buildDocumentWithOneValidAndOneCorruptImage]
     * 同一个路子：直接操作底层对象构造出"正常路径走不到"的异常情况。
     */
    private fun buildDocumentWithOneResolvableAndOneUnresolvableOutlineItem(): File {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)

        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val contentStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page)
        val font = com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA
        contentStream.beginText()
        contentStream.setFont(font, 12f)
        contentStream.newLineAtOffset(50f, 700f)
        contentStream.showText("page one text")
        contentStream.endText()
        contentStream.close()

        val outline = PDDocumentOutline()
        document.documentCatalog.documentOutline = outline

        val resolvable = PDOutlineItem()
        resolvable.title = "可以跳转的标题"
        val pageDest = PDPageXYZDestination()
        pageDest.page = page
        resolvable.destination = pageDest
        outline.addLast(resolvable)

        val unresolvable = PDOutlineItem()
        unresolvable.title = "指向不存在命名目标的标题"
        // 命名目标查文档编目的 /Dests 名字树查不到时 findDestinationPage 返回 null——
        // 这里故意不往编目里注册这个名字。
        unresolvable.destination = PDNamedDestination("does-not-exist")
        outline.addLast(unresolvable)

        val output = File.createTempFile("broken-outline-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()
        return output
    }
}
