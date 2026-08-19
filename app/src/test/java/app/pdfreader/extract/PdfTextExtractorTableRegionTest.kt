package app.pdfreader.extract

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [PdfTextExtractor.tableCropRect]/[PdfTextExtractor.isWithinTableBand] 的单元测试——
 * 纯几何计算，不依赖 Bitmap/PDFBox，见两个函数的 KDoc（"表格区域裁剪"配套的坐标
 * 换算，2026-08-19 增量）。用 `dpi=72`（缩放系数恰好是 1.0）构造测试用例，方便手工
 * 验算，不引入浮点误差干扰。挂 [RobolectricTestRunner] 只是因为用到了
 * `android.graphics.Rect`（普通 JUnit 环境下安卓类没有真实实现，调用会直接抛
 * `RuntimeException`），跟"这层逻辑本身依不依赖 Android 平台"无关——[TableRegion]/
 * 数学计算部分不需要 Robolectric，`Rect` 只是个纯数据容器，选它单纯是因为
 * [PdfTextExtractor.tableCropRect] 的返回类型已经是 `Rect`，不需要为了这条测试
 * 再引入一个自定义的四元组类型。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorTableRegionTest {

    @Test
    fun `dpi=72 时缩放系数是1，裁剪矩形等于区域坐标加内边距、再做一次页面坐标转像素坐标`() {
        // region: PDF 坐标系 (100,200)-(300,400)，页高 800。
        // 内边距 6pt：期望 left=100-6=94，right=300+6=306。
        // y 轴转换（PDF 原点左下、像素原点左上）：top(像素)=页高-maxY-6=800-400-6=394，
        // bottom(像素)=页高-minY+6=800-200+6=606。
        val region = TableRegion(minX = 100f, minY = 200f, maxX = 300f, maxY = 400f)
        val rect = PdfTextExtractor.tableCropRect(
            region,
            pageHeightPt = 800f,
            dpi = 72f,
            bitmapWidth = 1000,
            bitmapHeight = 1000,
        )
        assertEquals(Rect(94, 394, 306, 606), rect)
    }

    @Test
    fun `区域靠近页面边缘、加内边距后会越界时，裁剪矩形被卡在图片实际范围内`() {
        // region 贴着页面左上角，minX=0/maxY=100（页高也是 100，说明区域贴着页顶）
        // ——加了 6pt 内边距之后 left/top 理论上会算出负数，必须卡在 0。
        val region = TableRegion(minX = 0f, minY = 50f, maxX = 50f, maxY = 100f)
        val rect = PdfTextExtractor.tableCropRect(
            region,
            pageHeightPt = 100f,
            dpi = 72f,
            bitmapWidth = 100,
            bitmapHeight = 100,
        )
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertTrue("right 不应该超过图片实际宽度 100，实际是 ${rect.right}", rect.right <= 100)
        assertTrue("bottom 不应该超过图片实际高度 100，实际是 ${rect.bottom}", rect.bottom <= 100)
        assertTrue("裁剪矩形宽度应该是正数，不能裁出空矩形", rect.width() > 0)
        assertTrue("裁剪矩形高度应该是正数，不能裁出空矩形", rect.height() > 0)
    }

    @Test
    fun `dpi 变化时裁剪矩形按比例缩放`() {
        val region = TableRegion(minX = 100f, minY = 200f, maxX = 300f, maxY = 400f)
        val at72Dpi = PdfTextExtractor.tableCropRect(region, 800f, 72f, 2000, 2000)
        val at144Dpi = PdfTextExtractor.tableCropRect(region, 800f, 144f, 2000, 2000)
        // 144dpi 是 72dpi 的两倍，裁剪矩形的宽高也应该大致翻倍（整数取整允许 1px 误差）。
        assertTrue(kotlin.math.abs(at144Dpi.width() - at72Dpi.width() * 2) <= 2)
        assertTrue(kotlin.math.abs(at144Dpi.height() - at72Dpi.height() * 2) <= 2)
    }

    @Test
    fun `落在表格纵向范围内（含内边距）的行判定为在表格区域内`() {
        val region = TableRegion(minX = 100f, minY = 200f, maxX = 300f, maxY = 400f)
        // topYDirAdj = 800-400-6 = 394，bottomYDirAdj = 800-200+6 = 606。
        assertTrue(PdfTextExtractor.isWithinTableBand(394f, region, pageHeightPt = 800f)) // 上边界（含）
        assertTrue(PdfTextExtractor.isWithinTableBand(606f, region, pageHeightPt = 800f)) // 下边界（含）
        assertTrue(PdfTextExtractor.isWithinTableBand(500f, region, pageHeightPt = 800f)) // 中间
    }

    @Test
    fun `落在表格纵向范围之外的行判定为不在表格区域内`() {
        val region = TableRegion(minX = 100f, minY = 200f, maxX = 300f, maxY = 400f)
        assertFalse(PdfTextExtractor.isWithinTableBand(393f, region, pageHeightPt = 800f)) // 刚好在上边界之前
        assertFalse(PdfTextExtractor.isWithinTableBand(607f, region, pageHeightPt = 800f)) // 刚好在下边界之后
        assertFalse(PdfTextExtractor.isWithinTableBand(0f, region, pageHeightPt = 800f)) // 页面最顶部
        assertFalse(PdfTextExtractor.isWithinTableBand(800f, region, pageHeightPt = 800f)) // 页面最底部
    }

    // ---- isSegmentOnPage：真机踩坑，见类 KDoc"表格区域裁剪"配套一节 ----

    @Test
    fun `两个端点都在页面范围内的线段判定为在页面上`() {
        val segment = LineSegment(x1 = 50f, y1 = 100f, x2 = 200f, y2 = 100f)
        assertTrue(PdfTextExtractor.isSegmentOnPage(segment, pageWidth = 612f, pageHeight = 792f))
    }

    @Test
    fun `坐标跨度极大、明显超出页面范围的线段判定为不在页面上`() {
        // 真机实测复现的真实数值：某份文档的线段 Y 坐标跨度到 -4284~5076，
        // 而这份文档的 MediaBox 是正常的 0~792。
        val segment = LineSegment(x1 = 0f, y1 = -4284f, x2 = 612f, y2 = -4284f)
        assertFalse(PdfTextExtractor.isSegmentOnPage(segment, pageWidth = 612f, pageHeight = 792f))
    }

    @Test
    fun `只有一个端点在页面范围内的线段保守地判定为不在页面上`() {
        val segment = LineSegment(x1 = 50f, y1 = 100f, x2 = 50f, y2 = 900f) // 900 超出页高 792。
        assertFalse(PdfTextExtractor.isSegmentOnPage(segment, pageWidth = 612f, pageHeight = 792f))
    }

    @Test
    fun `贴着页面边缘、在容差范围内的线段仍然判定为在页面上`() {
        val segment = LineSegment(x1 = -0.5f, y1 = 0f, x2 = 612.5f, y2 = 792f)
        assertTrue(PdfTextExtractor.isSegmentOnPage(segment, pageWidth = 612f, pageHeight = 792f))
    }
}
