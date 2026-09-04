package app.pdfreader.extract

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [LayoutDetector.detect] 端到端行为——真正走 ONNX native 推理，跟 [Jpeg2000Decoder]
 * 同一个约束，Robolectric 测不了，必须在真机/模拟器跑。这份测试是把 2026-09-04
 * 探针（`DocLayoutOnnxProbeTest`，当时直接手写 ONNX Runtime 调用，模型还在
 * androidTest/assets）验证过的行为，改用正式的 [LayoutDetector] API 重新走一遍——
 * 数值应该跟探针那次完全一致（同一个模型、同一份 fixture、同一套预处理参数），
 * 这里锁进断言当回归测试，不是重新探索。
 *
 * 模型现在从 `app/src/main/assets/pp-doclayout-m.onnx` 读（已经打进正式 APK，
 * 见 [LayoutDetector.open] KDoc），要用 `targetContext`（被测应用的 Context）而
 * 不是 `context`（测试 APK 自己的 Context，读不到 main/assets 里的文件）。
 */
@RunWith(AndroidJUnit4::class)
class LayoutDetectorInstrumentedTest {

    private fun readTestAsset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    private fun renderFixturePage(): Bitmap {
        val pdfBytes = readTestAsset("sample-with-table.pdf")
        val tempFile = File.createTempFile("layout-detector-test", ".pdf", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        tempFile.writeBytes(pdfBytes)
        return ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val dpi = 220f // 跟 PdfTextExtractor.TABLE_PAGE_RENDER_DPI 同一个值
                    val scale = dpi / 72f
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }

    @Test
    fun `open成功加载真实模型`() {
        val detector = LayoutDetector.open(InstrumentationRegistry.getInstrumentation().targetContext)
        assertNotNull("main assets 里的模型应该能正常加载建 session", detector)
        detector?.close()
    }

    @Test
    fun `表格与正文混排页检测出高置信度table区域`() {
        val detector = LayoutDetector.open(InstrumentationRegistry.getInstrumentation().targetContext)
        requireNotNull(detector) { "模型加载失败，没法继续这条测试" }

        val pageBitmap = renderFixturePage()
        val detections = detector.detect(pageBitmap)
        detector.close()
        pageBitmap.recycle()

        // 跟 2026-09-04 探针那次数值完全一致（同一模型/fixture/预处理），见类 KDoc。
        val table = detections.firstOrNull { it.label == "table" }
        assertNotNull("应该检测出一个 table 区域", table)
        assertTrue("table 置信度应该跟探针那次一样高（实测 0.943）", table!!.score > 0.9f)
        assertTrue("bbox 应该落在原图像素范围内", table.left >= 0 && table.right <= pageBitmap.width)
    }

    @Test
    fun `空白页不会误判出高置信度检测`() {
        val detector = LayoutDetector.open(InstrumentationRegistry.getInstrumentation().targetContext)
        requireNotNull(detector) { "模型加载失败，没法继续这条测试" }

        val blankBitmap = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
        blankBitmap.eraseColor(Color.WHITE)
        val detections = detector.detect(blankBitmap)
        detector.close()
        blankBitmap.recycle()

        assertTrue("纯白页不应该有任何过阈值的检测，全空是正确行为", detections.isEmpty())
    }
}
