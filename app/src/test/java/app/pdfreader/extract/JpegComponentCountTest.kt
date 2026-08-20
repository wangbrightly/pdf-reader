package app.pdfreader.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [JpegComponentCount] 的单元测试——纯字节解析，不依赖 Robolectric/Android API，
 * 见该类 KDoc 完整背景。用项目里已有的三份真实 JPEG fixture 交叉验证（同一批
 * fixture 也被 [PdfTextExtractorJpegSubsamplingTest] 用于测试降采样解码路径）。
 */
class JpegComponentCountTest {

    private fun loadBytes(name: String): ByteArray = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(name)?.readBytes(),
    ) { "找不到测试 fixture：src/test/resources/$name" }

    @Test
    fun `RGB JPEG 解析出3个颜色分量`() {
        assertEquals(3, JpegComponentCount.of(loadBytes("small-quadrant.jpg")))
        assertEquals(3, JpegComponentCount.of(loadBytes("large-quadrant.jpg")))
    }

    @Test
    fun `CMYK JPEG 解析出4个颜色分量`() {
        assertEquals(4, JpegComponentCount.of(loadBytes("cmyk-quadrant.jpg")))
    }

    @Test
    fun `空字节数组解析不出结果，返回null`() {
        assertNull(JpegComponentCount.of(ByteArray(0)))
    }

    @Test
    fun `不是JPEG（缺少SOI标记）解析不出结果，返回null`() {
        assertNull(JpegComponentCount.of(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
    }

    @Test
    fun `只有SOI没有任何后续内容解析不出结果，返回null`() {
        assertNull(JpegComponentCount.of(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }

    @Test
    fun `数据在SOF标记前被截断解析不出结果，返回null`() {
        val full = loadBytes("small-quadrant.jpg")
        // 截到刚好只剩 SOI + 前几个字节，找不到完整的 SOF 段。
        assertNull(JpegComponentCount.of(full.copyOfRange(0, 10)))
    }
}
