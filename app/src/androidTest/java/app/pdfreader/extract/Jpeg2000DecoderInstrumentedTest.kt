package app.pdfreader.extract

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [Jpeg2000Decoder] 依赖 `jp2-android` 的 native `.so`（真实 Android ABI 的
 * ELF 二进制，不是 macOS/桌面 JVM 能加载的格式），Robolectric 纯 JVM 单测
 * 环境里 `System.loadLibrary` 会直接失败——这是 POC 阶段（见
 * `/Users/mac/.claude/plans/iridescent-foraging-floyd.md`）就确认过的硬约束，
 * 这份测试必须放进 `androidTest`（instrumentation test），跑在真机/模拟器的
 * 真实 Android 运行时上，`gradle connectedAndroidTest` 触发。
 *
 * 四份 fixture 都是 POC 阶段用来验证 native 稳定性时生成/提取的原始数据，
 * 直接复用（不是新造的）：
 * - `jpx-normal.jp2`：`opj_compress` 生成的 16×16 灰阶无损小图，像素值公式
 *   `((y*16+x)*13) % 256`，装机验证过跟 macOS `sips`（跟 OpenJPEG 完全独立的
 *   另一套 JPEG2000 实现）解码结果逐像素一致。
 * - `jpx-truncated.jp2`：`jpx-normal.jp2` 从中间截断（保留前 60% 字节）。
 * - `jpx-fake-header.jp2`：只有合法的 JP2 magic number，后面是随机字节。
 * - `jpx-notes43-real-sample.jp2`：NOTES.md #43 那份 Internet Archive 扫描书
 *   （LuraDocument 产出）真实嵌入的 JPX 图片流，直接从 PDF 里提取的原始字节
 *   （931×1250，RGB，有损压缩）。
 */
@RunWith(AndroidJUnit4::class)
class Jpeg2000DecoderInstrumentedTest {

    private fun readAsset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    @Test
    fun 正常JP2数据解码成功且像素值正确() {
        val bitmap = Jpeg2000Decoder.decode(readAsset("jpx-normal.jp2"))
        requireNotNull(bitmap) { "正常数据应该解码成功" }
        assertEquals(16, bitmap.width)
        assertEquals(16, bitmap.height)

        fun expectedGray(x: Int, y: Int) = (y * 16 + x) * 13 % 256
        for ((x, y) in listOf(0 to 0, 1 to 0, 2 to 0, 15 to 15, 8 to 8)) {
            val pixel = bitmap.getPixel(x, y)
            val gray = expectedGray(x, y)
            assertEquals("像素($x,$y) 红色分量", gray, Color.red(pixel))
            assertEquals("像素($x,$y) 绿色分量", gray, Color.green(pixel))
            assertEquals("像素($x,$y) 蓝色分量", gray, Color.blue(pixel))
        }
    }

    @Test
    fun 截断的数据流优雅返回null不崩溃() {
        val bitmap = Jpeg2000Decoder.decode(readAsset("jpx-truncated.jp2"))
        assertNull("截断流应该解码失败", bitmap)
    }

    @Test
    fun 伪造头部的随机数据优雅返回null不崩溃() {
        val bitmap = Jpeg2000Decoder.decode(readAsset("jpx-fake-header.jp2"))
        assertNull("伪造头部+随机字节应该解码失败", bitmap)
    }

    /**
     * 这条测试本身不做逐像素比对（POC 阶段已经用 macOS `sips` 做过一次完整的
     * 交叉验证，见 NOTES.md #48），只确认真机环境下同一份真实数据能稳定解码
     * 成功、尺寸正确——这是"NOTES #43 那份文档打开后不再是占位图"这个用户
     * 可见结果的最小可自动化回归防线。
     */
    @Test
    fun NOTES43真实样本解码成功且尺寸正确() {
        val bitmap = Jpeg2000Decoder.decode(readAsset("jpx-notes43-real-sample.jp2"))
        requireNotNull(bitmap) { "NOTES #43 真实样本应该解码成功" }
        assertEquals(931, bitmap.width)
        assertEquals(1250, bitmap.height)
    }
}
