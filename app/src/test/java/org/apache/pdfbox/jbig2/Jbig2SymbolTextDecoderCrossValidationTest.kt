package org.apache.pdfbox.jbig2

import app.pdfreader.extract.Jbig2SymbolTextDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * [Jbig2SymbolTextDecoder]（符号词典+文字区域解码器）的交叉验证测试。
 *
 * ## 背景：这条验证等了两天
 *
 * 符号词典解码器 2026-08-23 写完后一直标着"未验证"——本机没有 JBIG2 编码器
 * （jbig2enc 两次装不上），没法像 [Jbig2GenericRegionDecoderCrossValidationTest]
 * 那样在本地构造合法的符号词典测试数据；唯一出路是真机那本书的真实字节，当时
 * 卡在设备锁屏。这次用户配合装机复现，诊断代码（一次性，有开关保护）从 logcat
 * 导出了最小的一张符号词典图片：主数据流 5073 字节 + 共享符号词典流（Globals）
 * 4624 字节，就是这两个 fixture。
 *
 * ## 参考实现怎么喂 Globals：直接拼接两个流
 *
 * jbig2-imageio 的 `JBIG2Globals` 只是个空容器（`addSegment`/`getSegment`，没有
 * 从字节流解析的入口），要凑齐它得自己解析段头。但观察 fixture 的段编号发现一个
 * 更简单的合法形态：**Globals 流里的段编号是 0-12，主数据流的第一个段（页信息段）
 * 编号是 13——全局段和页段本来就是一个连续编号序列的两半**。把
 * `globals + main` 直接拼接，得到的就是一个完整的"无文件头内嵌组织形式"
 * JBIG2 流（全局段页关联=0、页段页关联=1），参考实现单流构造函数直接吃得下，
 * 跟 [Jbig2GenericRegionDecoderCrossValidationTest] 喂合成数据是同一条入口，
 * 不需要碰 `JBIG2Globals` 的段拼装。
 *
 * ## 验证强度
 *
 * 跟通用区域那次同级别：喂同一段真实字节，两套独立实现（自己手写的、Apache
 * jbig2-imageio 的完整规范实现）逐像素比对。符号词典解码是 JBIG2 标准里最复杂
 * 的几块（符号词典合成、Huffman/算术混合、文字区域的符号引用+8 种 reference
 * corner 摆放），任何一处状态机写错都会让像素对不上——"解出一张看着像的图"
 * 不算数，逐像素一致才算。
 */
class Jbig2SymbolTextDecoderCrossValidationTest {

    private fun loadBytes(name: String): ByteArray = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(name)?.readBytes(),
    ) { "找不到测试 fixture：src/test/resources/$name" }

    @Test
    fun `真机符号词典编码的JBIG2 跟Apache参考实现逐像素一致`() {
        val main = loadBytes("jbig2-symbol-main.jb2")
        val globals = loadBytes("jbig2-symbol-globals.jb2")

        val mine = Jbig2SymbolTextDecoder.decode(main, globals)
        assertNotNull("自己写的符号词典解码器应该能解出这份真机数据，返回 null 说明命中了某个提前退出分支", mine)

        val reference = JBIG2Document(
            MemoryCacheImageInputStream(ByteArrayInputStream(globals + main)),
        ).getPage(1)?.bitmap
        assertNotNull("参考实现应该能解出拼接后的流（第一页）", reference)

        assertEquals("宽度应该一致", reference!!.width, mine!!.width)
        assertEquals("高度应该一致", reference.height, mine.height)
        assertEquals("行字节数（rowStride）应该一致", reference.rowStride, mine.rowStride)

        var mismatchCount = 0
        var firstMismatchAt: String? = null
        for (y in 0 until mine.height) {
            for (x in 0 until mine.width) {
                val mineBit = (mine.packedBits[y * mine.rowStride + (x shr 3)].toInt() ushr (7 - (x and 7))) and 1
                val refBit = reference.getPixel(x, y).toInt()
                if (mineBit != refBit) {
                    mismatchCount++
                    if (firstMismatchAt == null) firstMismatchAt = "(x=$x, y=$y) 自己=$mineBit 参考=$refBit"
                }
            }
        }
        assertEquals(
            "真机符号词典数据应该跟参考实现逐像素完全一致，" +
                "不一致的像素数=$mismatchCount（共 ${mine.width.toLong() * mine.height} 像素），第一处不一致：$firstMismatchAt",
            0,
            mismatchCount,
        )
    }
}
