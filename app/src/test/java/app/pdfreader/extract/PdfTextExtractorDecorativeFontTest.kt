package app.pdfreader.extract

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * [PdfTextExtractor.isDecorativeSymbolFont] 的端到端测试——见该函数 KDoc 完整
 * 背景（2026-09-03 真机反馈"这本书里出现了很多单行的'l'和'O'"，追出来是
 * `HFSS.pdf` 这份技术手册用 Wingdings 图标字体表示流程步骤间的箭头图标，
 * PDF 的 `ToUnicode` 映射如实记录"这是键盘 l 键"，提取出来的字母没有语义）。
 *
 * 构造 fixture 的技巧：PDFBox-Android 没有内置真正的 Wingdings 字体资源（那是
 * Windows 系统字体，不是 PDF 标准 14 内置字体之一），所以用标准 Helvetica 字体
 * 画完文字之后，直接改写页面 `/Resources/Font` 字典里那个字体对象的 `/BaseFont`
 * 条目，把名字改成 `ERXUTR+Wingdings-Regular`（真机诊断实测到的真实字体名）
 * 再保存——`PDFont.getName()` 每次都从 `COSDictionary` 里读，不是构造时缓存
 * 死的，这个改名对重新加载后的 `TextPosition.font.name` 生效（已用独立的
 * probe 验证过），不需要真的嵌入一份 Wingdings 字体程序，因为
 * [PdfTextExtractor.isDecorativeSymbolFont] 只看字体名字符串，不看字形数据。
 *
 * **踩过的坑**：`page.resources.getFont(name)` 对标准 14 内置字体（比如
 * `PDType1Font.HELVETICA`）拿到的不是一份独立拷贝，是 PdfBox-Android 内部
 * 那个跨整个 JVM 进程共享的静态单例——第一版直接在这个单例的 `cosObject` 上
 * `setItem` 改名，"Wingdings 字体的孤立图标字符被过滤"这条测试单独跑没问题，
 * 但跟同一测试类里另一条用了普通 `PDType1Font.HELVETICA`（不改名）的反例测试
 * 一起跑（同一个 JVM 进程内顺序执行）就会失败——改名的副作用泄漏到了同一个
 * 进程里其它测试用到的同一个共享单例上，后一条测试的"普通 Helvetica"其实也
 * 被污染成了"Wingdings"。修法是先用 `COSDictionary(COSDictionary)` 拷贝构造
 * 函数复制一份独立的字典再改名、封装成新的 `PDType1Font` 塞回
 * `page.resources`，不碰共享单例本身的状态。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorDecorativeFontTest {

    private fun renameFont(page: PDPage, newName: String) {
        page.resources.fontNames.forEach { name ->
            val original = page.resources.getFont(name)
            val independentDict = COSName.getPDFName(newName).let {
                com.tom_roush.pdfbox.cos.COSDictionary(original.cosObject).apply {
                    setItem(COSName.BASE_FONT, it)
                }
            }
            page.resources.put(name, PDType1Font(independentDict))
        }
    }

    @Test
    fun `Wingdings字体的孤立图标字符被过滤 不出现在提取文字里`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage(PDRectangle(300f, 200f))
        document.addPage(page)
        val stream = PDPageContentStream(document, page)
        // 真机反馈的真实形状："l"(图标)单独一行，后面紧跟正常步骤说明文字。
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("l")
        stream.endText()
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 120f)
        stream.showText("Generate mesh")
        stream.endText()
        stream.close()
        renameFont(page, "ERXUTR+Wingdings-Regular")

        val file = File.createTempFile("wingdings-doc", ".pdf")
        file.deleteOnExit()
        document.save(file)
        document.close()

        val content = PdfTextExtractor.extractContent(context, file)
        assertTrue(
            "Wingdings 图标字符不该出现在提取文字里，实际 paragraphs=${content.paragraphs}",
            content.paragraphs.none { it.contains("l") },
        )
    }

    /**
     * 反例：同样的孤立 "l"，字体名如果不是 Wingdings/Webdings（这里用真机反馈
     * 之前的默认字体 Helvetica，不改名），就是普通正文里合理出现的字母 l，
     * 不该被过滤掉——确认判断条件精确匹配字体名，不是"孤立单字符就删"。
     */
    @Test
    fun `普通字体里的孤立字母l不会被过滤`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage(PDRectangle(300f, 200f))
        document.addPage(page)
        val stream = PDPageContentStream(document, page)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, 12f)
        stream.newLineAtOffset(20f, 150f)
        stream.showText("l")
        stream.endText()
        stream.close()

        val file = File.createTempFile("normal-l-doc", ".pdf")
        file.deleteOnExit()
        document.save(file)
        document.close()

        val content = PdfTextExtractor.extractContent(context, file)
        assertTrue("普通字体的字母 l 应该正常保留", content.paragraphs.any { it.contains("l") })
    }

    /**
     * 反例（用户明确关心的边界）：`Symbol` 字体里的真实希腊字母（技术文档公式里
     * 的物理量变量，比如这里用 α 模拟）不该被过滤——`Symbol` 不在
     * [PdfTextExtractor.isDecorativeSymbolFont] 的判断范围内，只精确匹配
     * Wingdings/Webdings 这两个纯图标字体家族。
     */
    @Test
    fun `Symbol字体的希腊字母公式内容不会被过滤`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        val page = PDPage(PDRectangle(300f, 200f))
        document.addPage(page)
        val stream = PDPageContentStream(document, page)
        stream.beginText()
        stream.setFont(PDType1Font.SYMBOL, 12f)
        stream.newLineAtOffset(20f, 150f)
        // 直接传 Unicode 希腊字母——PDFType1Font.SYMBOL 内部按 SymbolEncoding 把它
        // 映射到 Adobe Symbol 字体里对应的字形，传 ASCII 'a' 会抛
        // IllegalArgumentException（试过，SymbolEncoding 不认 U+0061），这才是这个
        // 字体真实的使用方式，也是真机 HFSS.pdf 里希腊字母公式变量的真实来源。
        stream.showText("α")
        stream.endText()
        stream.close()

        val file = File.createTempFile("symbol-font-doc", ".pdf")
        file.deleteOnExit()
        document.save(file)
        document.close()

        val content = PdfTextExtractor.extractContent(context, file)
        assertEquals("Symbol 字体内容应该正常保留，不该被当成装饰字体过滤掉", 1, content.paragraphs.size)
    }
}
