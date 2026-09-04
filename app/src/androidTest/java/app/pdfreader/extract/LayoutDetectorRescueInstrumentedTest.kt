package app.pdfreader.extract

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pdfreader.ui.DisplayBlock
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v0.3.0 路线 B（见 `V0.3-DECISION.md` 第 8 节、`PdfTextExtractor.tryLayoutDetectorRescue`
 * KDoc）端到端真机验证——`hasScatteredLayout` 误触发 + CV 救援这条完整链路，之前只
 * 分别验证过两半（纯几何逻辑单测、[LayoutDetector.detect] 本身准确），这条测试第一次
 * 把两半接起来在真机上跑一次真实数据。
 *
 * fixture `borderless-table.pdf`（2026-09-04 手写构造，见生成脚本
 * `/tmp/make_borderless_table_pdf.py`，未入库——本机没有可用的 Chromium 内核，改用
 * 直接写 PDF 内容流的方式，跟项目 NOTES #66"手写 COS 结构造 fixture"同一个思路）：
 * 一段说明文字 → 6 行 3 列的纯文字"表格"（只有 `Tj` 文字绘制，**零** `re`/`S`/`f` 之类
 * 的图形操作符，`TableGridDetector` 保证找不到任何网格线）→ 一段说明文字。列间距
 * 148pt/140pt，远超 `LINE_MERGE_MAX_X_GAP_PT=20`，同一行的 3 个单元格不会被
 * `mergeSameLineRuns` 合并，6 行 × 3 列同 Y 多条文字的信号，应该会触发
 * `hasScatteredLayout`（`MIN_OVERLAPPING_Y_GROUPS=4`）。
 *
 * **这条测试的结果本身就是信息，不是非过不可的硬指标**：CV 模型对着一份没有任何
 * 视觉表格线索（不像真实文档常见的"隐形表格"那样至少有对齐/底纹这些视觉暗示）的
 * 纯文字网格，认不认得出"这是表格"没有先验保证——如果模型判断不出来，
 * [PdfTextExtractor.tryLayoutDetectorRescue] 应该正确回退到今天的整页栅格化（安全
 * 默认值），这同样是正确行为，不是 bug。两种结果分别断言、都记录进 log，人工核对。
 */
@RunWith(AndroidJUnit4::class)
class LayoutDetectorRescueInstrumentedTest {

    private fun readTestAsset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    @Test
    fun `无边框表格误触发hasScatteredLayout时救援逻辑走完整链路不崩溃`() {
        val pdfBytes = readTestAsset("borderless-table.pdf")
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val tempFile = File.createTempFile("rescue-test", ".pdf", targetContext.cacheDir)
        tempFile.writeBytes(pdfBytes)

        val session = PdfTextExtractor.Session.open(targetContext, tempFile)
        val content = session.loadPage(1)
        session.close()

        val textBlocks = content.blocks.filterIsInstance<DisplayBlock.Text>()
        val imageBlocks = content.blocks.filterIsInstance<DisplayBlock.Image>()

        Log.i("RescueTest", "blocks 总数=${content.blocks.size} text=${textBlocks.size} image=${imageBlocks.size}")
        content.blocks.forEachIndexed { i, block ->
            when (block) {
                is DisplayBlock.Text -> Log.i("RescueTest", "[$i] Text: ${block.text.take(60)}")
                is DisplayBlock.Image -> Log.i("RescueTest", "[$i] Image: ${block.bitmap.width}x${block.bitmap.height}")
            }
        }

        if (textBlocks.isNotEmpty()) {
            Log.i("RescueTest", "=== CV 救援生效：表格局部裁剪，正文保留重排能力 ===")
            assertTrue("救援生效时应该能看到前后两段说明文字", textBlocks.size >= 2)
            assertTrue("救援生效时应该有裁剪出来的表格图片", imageBlocks.isNotEmpty())
            assertTrue(
                "文字内容应该包含表格前的说明文字",
                textBlocks.any { it.text.contains("before the table") },
            )
            assertTrue(
                "文字内容应该包含表格后的说明文字",
                textBlocks.any { it.text.contains("after the table") },
            )
        } else {
            Log.i("RescueTest", "=== CV 没能识别出这份纯文字网格是表格，安全回退到整页栅格化（今天的既有行为）===")
            assertTrue("回退时至少应该有整页图片，不能是空白", imageBlocks.isNotEmpty())
        }
    }
}
