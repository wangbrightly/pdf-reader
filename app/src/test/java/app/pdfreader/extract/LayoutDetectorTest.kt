package app.pdfreader.extract

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [LayoutDetector] 里不碰 ONNX native 调用的纯逻辑部分——真正的 [LayoutDetector.detect]
 * 端到端行为（含 native 推理）在 `LayoutDetectorInstrumentedTest`（真机），这里只测
 * [LayoutDetector.parseDetections]/[LayoutDetector.scaleFactorFor]/
 * [LayoutDetector.preprocessToChw] 这三个不依赖 ONNX 的纯函数。这几条断言背后的数值
 * (label 顺序、归一化公式、640 输入尺寸)全部来自 2026-09-04 真机探针已经验证过的结果，
 * 这里是把探针证明过的逻辑固化成回归测试，不是重新猜规格。
 */
@RunWith(RobolectricTestRunner::class)
class LayoutDetectorTest {

    private fun row(clsId: Int, score: Float, x1: Float = 0f, y1: Float = 0f, x2: Float = 10f, y2: Float = 10f) =
        floatArrayOf(clsId.toFloat(), score, x1, y1, x2, y2)

    @Test
    fun `过阈值的行按cls_id映射成标签并保留分数和坐标`() {
        val raw = arrayOf(row(clsId = 8, score = 0.943f, x1 = 191f, y1 = 351f, x2 = 1626f, y2 = 678f))
        val result = LayoutDetector.parseDetections(raw, threshold = 0.5f)
        assertEquals(1, result.size)
        assertEquals("table", result[0].label)
        assertEquals(0.943f, result[0].score, 0.0001f)
        assertEquals(191f, result[0].left, 0.0001f)
        assertEquals(351f, result[0].top, 0.0001f)
        assertEquals(1626f, result[0].right, 0.0001f)
        assertEquals(678f, result[0].bottom, 0.0001f)
    }

    @Test
    fun `低于阈值的行被过滤掉`() {
        val raw = arrayOf(row(clsId = 2, score = 0.499f))
        val result = LayoutDetector.parseDetections(raw, threshold = 0.5f)
        assertEquals(0, result.size)
    }

    @Test
    fun `等于阈值的行保留`() {
        val raw = arrayOf(row(clsId = 2, score = 0.5f))
        val result = LayoutDetector.parseDetections(raw, threshold = 0.5f)
        assertEquals(1, result.size)
    }

    @Test
    fun `混合多行只保留过阈值的且标签各自正确`() {
        val raw = arrayOf(
            row(clsId = 8, score = 0.943f), // table
            row(clsId = 2, score = 0.509f), // text
            row(clsId = 2, score = 0.380f), // text，过不了阈值
            row(clsId = 0, score = 0.102f), // paragraph_title，过不了阈值
        )
        val result = LayoutDetector.parseDetections(raw, threshold = 0.5f)
        assertEquals(setOf("table", "text"), result.map { it.label }.toSet())
        assertEquals(2, result.size)
    }

    @Test
    fun `cls_id超出标签表范围的行被丢弃不崩溃`() {
        val raw = arrayOf(row(clsId = 99, score = 0.99f))
        val result = LayoutDetector.parseDetections(raw, threshold = 0.5f)
        assertEquals(0, result.size)
    }

    @Test
    fun `23个类别标签顺序照抄inference_yml`() {
        assertEquals(
            listOf(
                "paragraph_title", "image", "text", "number", "abstract", "content",
                "figure_title", "formula", "table", "table_title", "reference",
                "doc_title", "footnote", "header", "algorithm", "footer", "seal",
                "chart_title", "chart", "formula_number", "header_image",
                "footer_image", "aside_text",
            ),
            LayoutDetector.LABEL_LIST,
        )
    }

    @Test
    fun `scale_factor是目标尺寸除以各自维度不是同一个整体比例`() {
        // 真机探针那份 fixture：原图 1818x2569（宽x高）。
        val factor = LayoutDetector.scaleFactorFor(bitmapWidth = 1818, bitmapHeight = 2569)
        assertEquals(2, factor.size)
        assertEquals(640f / 2569, factor[0], 0.0001f) // 高的比例
        assertEquals(640f / 1818, factor[1], 0.0001f) // 宽的比例
    }

    @Test
    fun `预处理输出长度是3倍640乘640`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val chw = LayoutDetector.preprocessToChw(bitmap)
        assertEquals(3 * 640 * 640, chw.size)
    }

    @Test
    fun `纯色图归一化公式正确且RGB平面按R_G_B顺序排列不是BGR`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        // R=200 G=100 B=50，三个通道数值都不同，能测出平面顺序有没有被搞反。
        bitmap.eraseColor(Color.rgb(200, 100, 50))
        val chw = LayoutDetector.preprocessToChw(bitmap)

        val plane = 640 * 640
        val expectedR = (200 / 255f - 0.485f) / 0.229f
        val expectedG = (100 / 255f - 0.456f) / 0.224f
        val expectedB = (50 / 255f - 0.406f) / 0.225f

        // 纯色图缩放后（bilinear，源图本身是纯色，插值结果还是同一个颜色）取中心点，
        // 避开边缘可能出现的插值边界效应。
        val centerIndex = (320 * 640) + 320
        assertEquals(expectedR, chw[centerIndex], 0.01f)
        assertEquals(expectedG, chw[plane + centerIndex], 0.01f)
        assertEquals(expectedB, chw[2 * plane + centerIndex], 0.01f)
    }

    @Test
    fun `预处理不修改也不回收传入的原始bitmap`() {
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        LayoutDetector.preprocessToChw(bitmap)
        assert(!bitmap.isRecycled) { "调用方后续可能还要用这张原图（比如裁剪表格区域），不能被这一步顺手回收" }
        assertEquals(Color.BLUE, bitmap.getPixel(0, 0))
    }
}
