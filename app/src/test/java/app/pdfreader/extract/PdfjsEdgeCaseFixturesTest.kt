package app.pdfreader.extract

import app.pdfreader.ui.DisplayBlock
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 纯诊断性探测：从 mozilla/pdf.js 项目 test/pdfs/ 测试语料库里挑的真实边缘案例
 * 文件（CMYK JPEG、JBIG2 通用区域/符号词典/Huffman、页面 `/Rotate`），跑一遍
 * 项目现有的 [PdfTextExtractor.Session] 加载路径，记录每份文件是"正常解码"还是
 * "走占位图"、有没有抛出未被内部 `runCatching` 兜住的异常。
 *
 * 不是回归测试——这批 fixture 不是这个项目自己积累的真实用户反馈，是借用另一个
 * 成熟 PDF 阅读器项目多年 bug 报告攒下来的边缘案例语料库，目的是主动撞出
 * NOTES.md 里还没记录过的边界，而不是等真机用户撞见。断言只保底"不抛出未捕获
 * 异常"（这是比"多一张占位图"更严重的问题），图片解码成功与否只打印诊断信息，
 * 不做强断言——已知很多 fixture 命中的是明确在 NOTES.md 里记录过"仍返回 null
 * 走占位图"的范围外情况（Huffman 编码符号词典等），这些 fixture 本来就该走
 * 占位图，不是这次要修的东西。
 */
@RunWith(RobolectricTestRunner::class)
class PdfjsEdgeCaseFixturesTest {

    private fun loadFixtureFile(name: String): File {
        val resourceStream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("pdfjs-edge-cases/$name"),
        ) { "找不到测试 fixture：src/test/resources/pdfjs-edge-cases/$name" }
        val tempFile = File.createTempFile(name, ".pdf")
        tempFile.deleteOnExit()
        resourceStream.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        return tempFile
    }

    private data class ImageOutcome(val width: Int, val height: Int, val cornerColor: Int) {
        // 占位图函数按 PLACEHOLDER_LONG_SIDE_PX=400 精确缩放长边，真正占位图的长边应该
        // 落在 395-400 附近；单纯"尺寸小"不能证明是占位图（可能是真解码出来的小图），
        // 只有长边贴近 400 才是占位图的可靠信号，再用左上角像素颜色（#EEEEEE 底色）二次确认。
        val looksLikePlaceholder: Boolean get() =
            maxOf(width, height) in 390..400 && cornerColor == android.graphics.Color.parseColor("#EEEEEE")
    }

    private fun probe(fixtureName: String): String {
        val context = RuntimeEnvironment.getApplication()
        val file = loadFixtureFile(fixtureName)
        val sb = StringBuilder()
        sb.append("=== $fixtureName ===\n")

        // 直接读页面级 /Rotate，跟 Session 加载路径分开验证，方便对照。
        runCatching {
            PDDocument.load(file).use { doc ->
                val rotations = (0 until doc.numberOfPages).map { doc.getPage(it).rotation }
                sb.append("  页数=${doc.numberOfPages} /Rotate 值=$rotations\n")
            }
        }.onFailure { sb.append("  [PDDocument.load 失败] ${it}\n") }

        val loadResult = runCatching {
            PdfTextExtractor.Session.open(context, file).use { session ->
                val outcomes = mutableListOf<ImageOutcome>()
                var textBlockCount = 0
                for (pageNo in 1..session.pageCount) {
                    val blocks = session.loadPage(pageNo).blocks
                    textBlockCount += blocks.filterIsInstance<DisplayBlock.Text>().size
                    blocks.filterIsInstance<DisplayBlock.Image>().forEach {
                        outcomes.add(ImageOutcome(it.bitmap.width, it.bitmap.height, it.bitmap.getPixel(0, 0)))
                    }
                }
                outcomes to textBlockCount
            }
        }

        loadResult.onSuccess { (outcomes, textBlockCount) ->
            sb.append("  文字块数=$textBlockCount 图片数=${outcomes.size}\n")
            outcomes.forEachIndexed { i, o ->
                val verdict = if (o.looksLikePlaceholder) "占位图" else "非占位图（真实解码或未知内容）"
                sb.append("    图片[$i] ${o.width}x${o.height} 左上角=#${Integer.toHexString(o.cornerColor)} -> $verdict\n")
            }
        }.onFailure { e ->
            sb.append("  [Session 加载路径抛出未捕获异常] ${e::class.simpleName}: ${e.message}\n")
        }
        return sb.toString()
    }

    @Test
    fun `探测 pdfjs 边缘案例语料库对现有解码链路的影响`() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())

        val fixtures = listOf(
            // CMYK
            "cmykjpeg.pdf",
            "function_based_shading_cmyk.pdf",
            // JBIG2：通用区域/符号词典/文件头边界/Huffman（已知未覆盖范围）
            "jbig2_file_header.pdf",
            "jbig2_symbol_offset.pdf",
            "JBIG2Globals.pdf",
            "jbig2_huffman_1.pdf",
            "jbig2_huffman_2.pdf",
            // 页面 /Rotate
            "hello_world_rotated.pdf",
            "image-rotated-black-white-ratio.pdf",
            "rotated.pdf",
            "rotation.pdf",
        )

        val report = StringBuilder()
        val crashed = mutableListOf<String>()
        for (name in fixtures) {
            val result = runCatching { probe(name) }
            result.onSuccess { report.append(it).append("\n") }
            result.onFailure { e ->
                crashed.add(name)
                report.append("=== $name ===\n  [探测本身抛出异常，说明连 Session.open 都过不去] ${e}\n\n")
            }
        }

        println("\n########## pdfjs 边缘案例探测报告 ##########\n$report############################################\n")

        assertTrue(
            "以下 fixture 让加载路径抛出了未被内部 runCatching 兜住的异常，" +
                "属于比'占位图'更严重的问题，需要人工核查：$crashed",
            crashed.isEmpty(),
        )
    }

    /**
     * 定位 JBIG2Globals.pdf 反复复现的"同一份文件、独立全新 JVM 之间解析结果不一致"
     * （有时 2 张图 0 段文字，有时 0 张图 300/3/1 段文字）——怀疑根因是 [PdfTextExtractor
     * .Session.open] 内部起的 `footerLearningThread`/`outlineThread` 两个后台线程，
     * 跟调用方紧接着同步调用的 [PdfTextExtractor.Session.loadPage] 并发读同一个
     * `PDDocument`（PDFBox-Android 官方文档没有声称线程安全），这份 fixture 只有 1 页，
     * 页脚学习线程的取样范围（最多 150 页）会正好覆盖这唯一的一页，命中概率因此比
     * 多页文档高得多。这里同一个 JVM 内直接对比"open 后立刻 loadPage"（有并发）vs
     * "先 awaitFooterLearningForTest+awaitOutlineForTest 等两个后台线程跑完再
     * loadPage"（无并发）跑多轮，如果后者稳定、前者不稳定，就实锤是这个并发竞争。
     */
    @Test
    fun `JBIG2Globals 反复解析定位是否为后台线程并发竞争`() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
        val file = loadFixtureFile("JBIG2Globals.pdf")

        fun onePass(awaitBackgroundThreadsFirst: Boolean): String {
            return PdfTextExtractor.Session.open(RuntimeEnvironment.getApplication(), file).use { session ->
                if (awaitBackgroundThreadsFirst) {
                    session.awaitFooterLearningForTest()
                    session.awaitOutlineForTest()
                }
                val blocks = session.loadPage(1).blocks
                val imgCount = blocks.filterIsInstance<DisplayBlock.Image>().size
                val textCount = blocks.filterIsInstance<DisplayBlock.Text>().size
                "图片=$imgCount 文字=$textCount"
            }
        }

        val racyResults = (1..40).map { onePass(awaitBackgroundThreadsFirst = false) }
        val safeResults = (1..40).map { onePass(awaitBackgroundThreadsFirst = true) }

        println(
            "\n########## JBIG2Globals 并发竞争定位 ##########\n" +
                "不等后台线程（有并发风险）8 次结果：$racyResults\n" +
                "先 await 两个后台线程（无并发）8 次结果：$safeResults\n" +
                "############################################\n",
        )
    }
}
