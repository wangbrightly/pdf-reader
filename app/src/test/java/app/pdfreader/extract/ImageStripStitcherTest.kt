package app.pdfreader.extract

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ImageStripStitcher] 的单元测试——纯逻辑（除了 [Bitmap.createBitmap]/[android.graphics
 * .Canvas] 这两个 Robolectric 能正常模拟尺寸的 Android API），见该类 KDoc 完整背景。
 *
 * Robolectric 的 Bitmap 影子类不还原真实像素内容（这个项目其它测试已经踩过、
 * 绕过这个限制的经验——见 [PdfTextExtractorJpegSubsamplingTest]），这里的测试
 * 只断言尺寸/数量，不断言像素颜色。
 */
@RunWith(RobolectricTestRunner::class)
class ImageStripStitcherTest {

    private fun bitmap(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    @Test
    fun `少于3张图片时原样返回`() {
        val images = listOf(bitmap(125, 1078), bitmap(125, 1078))
        assertSame(images, ImageStripStitcher.stitchIfTiled(images))
    }

    @Test
    fun `单张图片原样返回`() {
        val images = listOf(bitmap(750, 1078))
        assertSame(images, ImageStripStitcher.stitchIfTiled(images))
    }

    @Test
    fun `6条等宽竖条+1条更窄的余数条拼接成一整张`() {
        // 对应真机诊断日志实测到的真实模式：125x1078 x6 + 19x1078。
        val images = (0 until 6).map { bitmap(125, 1078) } + bitmap(19, 1078)
        val result = ImageStripStitcher.stitchIfTiled(images)

        assertEquals(1, result.size)
        assertEquals(125 * 6 + 19, result.single().width)
        assertEquals(1078, result.single().height)
    }

    @Test
    fun `高度不完全一致时不拼接，原样返回`() {
        val images = listOf(bitmap(125, 1078), bitmap(125, 1080), bitmap(125, 1078))
        assertSame(images, ImageStripStitcher.stitchIfTiled(images))
    }

    @Test
    fun `除最后一张外宽度不一致时不拼接，原样返回`() {
        // 三张不同宽度、同高度的图片——更像是页面上互不相关的并排图片，不是切片。
        val images = listOf(bitmap(200f.toInt(), 500), bitmap(300, 500), bitmap(150, 500))
        assertSame(images, ImageStripStitcher.stitchIfTiled(images))
    }

    @Test
    fun `最后一张比统一宽度更宽时不拼接，原样返回`() {
        // "余数条"该比统一宽度窄，比统一宽度还宽说明不是这个模式。
        val images = listOf(bitmap(125, 800), bitmap(125, 800), bitmap(125, 800), bitmap(200, 800))
        assertSame(images, ImageStripStitcher.stitchIfTiled(images))
    }

    @Test
    fun `所有条宽度完全相同（没有余数条）也能正确拼接`() {
        val images = (0 until 4).map { bitmap(100, 600) }
        val result = ImageStripStitcher.stitchIfTiled(images)

        assertEquals(1, result.size)
        assertEquals(400, result.single().width)
        assertEquals(600, result.single().height)
    }

    /**
     * 见类 KDoc"判断标准"第 4 条完整背景：真机反馈"Board of Directors"页误判——
     * 10 张董事头像（真机确认过的真实尺寸 318×353，高宽比约 1.1）同时满足前 3 条
     * 判断标准（≥3 张、等高、等宽），在加高宽比门槛之前会被误判成"切片"，10 个人
     * 的照片被强行拼接成一张 3180×353 的宽图。这条测试直接照抄真机尺寸，不能拼接。
     */
    @Test
    fun `多张同尺寸但接近正方形的证件照不拼接，原样返回（Board of Directors 真机反例）`() {
        val images = (0 until 10).map { bitmap(318, 353) }
        assertSame(images, ImageStripStitcher.stitchIfTiled(images))
    }

    @Test
    fun `高宽比刚好等于门槛时仍然拼接，略低于门槛时不拼接`() {
        val atThreshold = (0 until 3).map { bitmap(100, 300) } // 300/100 = 3，等于 MIN_STRIP_ASPECT_RATIO。
        val atResult = ImageStripStitcher.stitchIfTiled(atThreshold)
        assertEquals(1, atResult.size)

        val belowThreshold = (0 until 3).map { bitmap(100, 299) } // 299/100 < 3。
        assertSame(belowThreshold, ImageStripStitcher.stitchIfTiled(belowThreshold))
    }
}
