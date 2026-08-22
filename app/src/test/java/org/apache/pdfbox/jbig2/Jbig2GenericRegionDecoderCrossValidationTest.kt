package org.apache.pdfbox.jbig2

import app.pdfreader.extract.Jbig2GenericRegionDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.random.Random

/**
 * 交叉验证 [Jbig2GenericRegionDecoder]（自己手写的 JBIG2 通用区域解码器，完整背景
 * 见该类 KDoc）解码结果是否跟 Apache PDFBox 项目的 `jbig2-imageio`（已经过大量
 * 真实使用验证的成熟实现，这次只当"标准答案"用，不打进正式 APK——见
 * `build.gradle.kts` 里这条 `testImplementation` 依赖的注释）一致。
 *
 * ## 为什么不用一份"真实"的 JBIG2 图片文件当测试 fixture
 *
 * 本机没有装 JBIG2 编码器（`jbig2enc` 需要 Homebrew 从源码编译，装机过程中卡在
 * 网络问题，见 NOTES.md 完整背景），没法现造一份"内容已知、可以人工核对"的真实
 * JBIG2 图片。改成不追求"图片内容有意义"，只验证"喂同一段字节，两套独立实现
 * （自己写的、`jbig2-imageio` 的）解出来的结果是不是一样"——用
 * [kotlin.random.Random]（固定种子，跑多次结果稳定可复现）生成随机字节当算术
 * 编码payload，构造几种不同参数组合（4 种模板 × 开关 TPGDON）的最小合法段。
 *
 * 这个验证方式的逻辑：如果两套完全独立实现的解码算法，对任意字节输入都产出
 * 完全一致的结果，那么"我这份实现和已经被广泛验证过的参考实现在算法上等价"这件
 * 事本身就成立——不需要额外验证"某一份具体真实数据解出来是不是这本书原来该有
 * 的样子"，等价性已经是比"对一份 fixture"更强的证据（覆盖的是算法本身，不是
 * 某一份数据的巧合）。
 *
 * ## 为什么这个测试文件放在 `org.apache.pdfbox.jbig2` 包下
 *
 * `JBIG2Document`/`JBIG2Page` 的构造函数/`getBitmap()` 是 `protected`——跟
 * `AndroidBridge`（这次已经撤回、不再使用的第一版尝试）用的是同一个"同包可访问
 * protected 成员"语言规则。这次是在 `src/test`（JVM/Robolectric 环境，不是
 * Android 运行时）里用，`javax.imageio.stream.ImageInputStream` 在这个环境下
 * 真实可用（NOTES.md #27 装机验证走不通的限制只发生在 Android 运行时，不影响
 * 桌面 JVM 上跑的单元测试），所以这次能直接用 `MemoryCacheImageInputStream`，
 * 不需要再手写一份 `ImageInputStream` 实现。
 */
class Jbig2GenericRegionDecoderCrossValidationTest {

    /**
     * 拼出一个完整的、内嵌组织形式（无文件头）的 JBIG2 字节流：一个段头 + 一个
     * "立即通用区域"段（类型 38）。段头格式、区域段信息字段格式见
     * [Jbig2GenericRegionDecoder] 里对应函数的 KDoc，这里反过来把同样的格式
     * 组装出来。
     */
    private fun buildGenericRegionStream(
        width: Int,
        height: Int,
        gbTemplate: Int,
        isTPGDon: Boolean,
        payload: ByteArray,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun writeU32(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        // --- 段数据（区域段信息 17 字节 + 通用区域段标志 1 字节 + AT 像素 + payload）---
        val data = java.io.ByteArrayOutputStream()
        fun dataWriteU32(v: Int) {
            data.write((v ushr 24) and 0xFF)
            data.write((v ushr 16) and 0xFF)
            data.write((v ushr 8) and 0xFF)
            data.write(v and 0xFF)
        }
        dataWriteU32(width)
        dataWriteU32(height)
        dataWriteU32(0) // x
        dataWriteU32(0) // y
        data.write(0) // 区域段标志：合成操作符=0（OR，这里只有一个区域，取值不影响解码本身）。
        // 通用区域段标志：bit4 ext=0，bit3 tpgdon，bit2-1 gbTemplate，bit0 mmr=0。
        val flags = ((if (isTPGDon) 1 else 0) shl 3) or (gbTemplate shl 1)
        data.write(flags)
        // 默认 AT 像素（见 Jbig2GenericRegionDecoder.isDefaultAtPixels）。
        val atPairs = when (gbTemplate) {
            0 -> listOf(3 to -1, -3 to -1, 2 to -2, -2 to -2)
            1 -> listOf(3 to -1)
            2 -> listOf(2 to -1)
            else -> listOf(2 to -1)
        }
        for ((ax, ay) in atPairs) {
            data.write(ax and 0xFF)
            data.write(ay and 0xFF)
        }
        data.write(payload)
        val dataBytes = data.toByteArray()

        // --- 页信息段（类型 48，7.4.8）：参考实现的 JBIG2Page.composePageBitmap()
        // 强制要求有这个段才能拿到 bitmap（否则 getPageInformationSegment() 返回
        // null，NPE）——真机扫出来的这本书的段类型列表本来就是 [48, 38]，这里
        // 补齐 48 也让测试 fixture 更贴近真实数据的形状。宽高跟通用区域一致、
        // defaultPixelValue=0，命中参考实现 fitsPage() 的"整页一个区域"快速
        // 路径，不用关心合成操作符/位块传送这些跟本次实现范围无关的细节。
        val pageInfoData = java.io.ByteArrayOutputStream()
        fun pageInfoWriteU32(v: Int) {
            pageInfoData.write((v ushr 24) and 0xFF)
            pageInfoData.write((v ushr 16) and 0xFF)
            pageInfoData.write((v ushr 8) and 0xFF)
            pageInfoData.write(v and 0xFF)
        }
        pageInfoWriteU32(width)
        pageInfoWriteU32(height)
        pageInfoWriteU32(0) // resolutionX，不使用。
        pageInfoWriteU32(0) // resolutionY，不使用。
        pageInfoData.write(0) // 标志：全 0（合成操作符=0，defaultPixelValue=0，等）。
        pageInfoData.write(0) // 条带信息高字节：isStriped=0。
        pageInfoData.write(0) // 条带信息低字节：maxStripeSize=0。
        val pageInfoBytes = pageInfoData.toByteArray()

        // --- 段头：页信息段（段号 0）---
        writeU32(0)
        out.write(48) // 类型=48（页信息），页关联字段=1 字节。
        out.write(0) // 引用段计数=0。
        out.write(1) // 页关联=1。
        writeU32(pageInfoBytes.size)
        out.write(pageInfoBytes)

        // --- 段头：立即通用区域段（段号 1）---
        writeU32(1)
        out.write(38) // 类型=38（立即通用区域），页关联字段=1 字节。
        out.write(0) // 引用段计数=0。
        out.write(1) // 页关联=1。
        writeU32(dataBytes.size)
        out.write(dataBytes)

        return out.toByteArray()
    }

    private fun decodeWithReference(bytes: ByteArray): Bitmap? {
        val stream = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        val document = JBIG2Document(stream)
        val page = document.getPage(1) ?: return null
        return page.bitmap
    }

    private fun assertBitmapsMatch(width: Int, height: Int, gbTemplate: Int, isTPGDon: Boolean, seed: Int) {
        val payload = Random(seed).nextBytes(256)
        val streamBytes = buildGenericRegionStream(width, height, gbTemplate, isTPGDon, payload)

        val mine = Jbig2GenericRegionDecoder.decode(streamBytes)
        val reference = decodeWithReference(streamBytes)

        assertTrue(
            "自己写的解码器应该能解出结果（gbTemplate=$gbTemplate isTPGDon=$isTPGDon seed=$seed），" +
                "实际返回 null，说明命中了某个提前 return 的分支，需要检查是不是判断条件写错了",
            mine != null,
        )
        requireNotNull(reference) { "参考实现应该总能解出结果（它没有 Jbig2GenericRegionDecoder 那些已知局限的提前退出）" }

        assertEquals("宽度应该一致", width, mine!!.width)
        assertEquals("高度应该一致", height, mine.height)
        assertEquals("行字节数（rowStride）应该一致", reference.rowStride, mine.rowStride)

        var mismatchCount = 0
        var firstMismatchAt: String? = null
        for (y in 0 until height) {
            for (x in 0 until width) {
                val mineBit = (mine.packedBits[y * mine.rowStride + (x shr 3)].toInt() ushr (7 - (x and 7))) and 1
                val refBit = reference.getPixel(x, y).toInt()
                if (mineBit != refBit) {
                    mismatchCount++
                    if (firstMismatchAt == null) firstMismatchAt = "(x=$x, y=$y) 自己=$mineBit 参考=$refBit"
                }
            }
        }
        assertEquals(
            "gbTemplate=$gbTemplate isTPGDon=$isTPGDon seed=$seed：应该逐像素完全一致，" +
                "不一致的像素数=$mismatchCount（共 ${width * height} 像素），第一处不一致：$firstMismatchAt",
            0,
            mismatchCount,
        )
    }

    @Test
    fun `模板0（无TPGDON）解码结果跟参考实现逐像素一致`() {
        assertBitmapsMatch(width = 32, height = 24, gbTemplate = 0, isTPGDon = false, seed = 1)
    }

    @Test
    fun `模板0（开TPGDON）解码结果跟参考实现逐像素一致`() {
        assertBitmapsMatch(width = 32, height = 24, gbTemplate = 0, isTPGDon = true, seed = 2)
    }

    @Test
    fun `模板1解码结果跟参考实现逐像素一致`() {
        assertBitmapsMatch(width = 32, height = 24, gbTemplate = 1, isTPGDon = false, seed = 3)
    }

    @Test
    fun `模板2解码结果跟参考实现逐像素一致`() {
        assertBitmapsMatch(width = 32, height = 24, gbTemplate = 2, isTPGDon = false, seed = 4)
    }

    @Test
    fun `模板3解码结果跟参考实现逐像素一致`() {
        assertBitmapsMatch(width = 32, height = 24, gbTemplate = 3, isTPGDon = false, seed = 5)
    }

    @Test
    fun `宽度不是8的整数倍时（有行尾填充位）解码结果仍然一致`() {
        // 29 不是 8 的倍数，故意验证 paddedWidth 相关的边界处理（每行最后一个字节
        // 只有部分位是真正的图像内容，剩下的是 6.2.5.7 3d 步骤定义的填充位）。
        assertBitmapsMatch(width = 29, height = 17, gbTemplate = 0, isTPGDon = false, seed = 6)
    }

    @Test
    fun `不同随机种子的payload多次验证，降低偶然一致的概率`() {
        for (seed in 100..104) {
            assertBitmapsMatch(width = 20, height = 20, gbTemplate = 0, isTPGDon = true, seed = seed)
        }
    }
}
