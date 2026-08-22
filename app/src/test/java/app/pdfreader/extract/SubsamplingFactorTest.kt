package app.pdfreader.extract

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PdfTextExtractor.subsamplingFactor] 的单元测试——纯数学，不依赖 PDFBox/Bitmap，见该
 * 函数 KDoc"真机踩坑"一节（2026-08-19，真机反馈 258 页文档加载要 25 秒）。
 *
 * 阈值 2026-08-22 从 2000px 调到 3000px（真机反馈"放大以后分辨率远小于 WPS"，见
 * [PdfTextExtractor.MAX_IMAGE_DIMENSION_PX] KDoc"2026-08-22 分辨率优化"一节完整
 * 背景），这里的断言跟着新阈值改写。
 */
class SubsamplingFactorTest {

    @Test
    fun `长边不超过阈值时不降采样`() {
        assertEquals(1, PdfTextExtractor.subsamplingFactor(1200, 800))
        assertEquals(1, PdfTextExtractor.subsamplingFactor(3000, 1500)) // 恰好等于阈值。
    }

    @Test
    fun `长边刚超过阈值一点，降采样到刚好不超过阈值的最大倍数`() {
        // 3001 长边：降到 1/2（1500.5）没问题，不需要降到 1/4。
        assertEquals(2, PdfTextExtractor.subsamplingFactor(3001, 1000))
    }

    @Test
    fun `源图片是长边的4倍`() {
        // 12000 长边：1/2=6000 还超标，1/4=3000 刚好不超标。
        assertEquals(4, PdfTextExtractor.subsamplingFactor(12000, 9000))
    }

    @Test
    fun `高是长边时同样按长边判断`() {
        assertEquals(4, PdfTextExtractor.subsamplingFactor(9000, 12000))
    }

    @Test
    fun `极端巨大的源图片也能算出合理倍数，不会死循环`() {
        assertEquals(32, PdfTextExtractor.subsamplingFactor(96000, 1000))
    }
}
