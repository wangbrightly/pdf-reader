package app.pdfreader.annotation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AnchorResolver] 的单元测试。
 *
 * 这个 App 把 PDF 文字抽出来**重新排版**，不显示原版页面，所以批注没法像普通 PDF
 * 阅读器那样钉在纸面坐标上（那套坐标在重排后没有意义）。改成记"第几页第几段第几
 * 个字到第几个字"，代价是抽取算法一改（段落怎么切、上下标怎么处理都动过好几次，
 * 见 NOTES #59/#65/#70）位置就会错位。所以锚点里**同时存一份原话**，解析时先按
 * 位置取文字跟原话比对，对不上就在这一页里重新搜原话——这个类就是这套"先对位置、
 * 再搜原文、最后认输"的逻辑，纯 Kotlin 无 Android 依赖，可以完整单测。
 *
 * 认输（[AnchorResolution.Lost]）不等于丢数据：调用方仍然拿得到锚点里存的原话，
 * 可以在批注列表里展示"这条批注找不到位置了"，只是正文里不再高亮。
 */
class AnchorResolverTest {

    private fun anchor(
        paragraphIndex: Int,
        startOffset: Int,
        endOffset: Int,
        quotedText: String,
    ) = TextAnchor(
        page = 1,
        paragraphIndex = paragraphIndex,
        startOffset = startOffset,
        endOffset = endOffset,
        quotedText = quotedText,
    )

    @Test
    fun `位置和原话都对得上时，原样返回存的位置`() {
        val paragraphs = listOf("印刷厂的老师傅习惯用拇指和食指捻一下纸角。")

        val resolution = AnchorResolver.resolve(anchor(0, 10, 16, "拇指和食指捻"), paragraphs)

        assertEquals(AnchorResolution.Resolved(0, 10, 16), resolution)
    }

    @Test
    fun `段落前面多了几个字导致位置偏移时，按原话搜回正确位置`() {
        // 典型场景：抽取算法改进后，段首多识别出了一个之前被吞掉的词。
        val paragraphs = listOf("后来，印刷厂的老师傅习惯用拇指和食指捻一下纸角。")

        val resolution = AnchorResolver.resolve(anchor(0, 9, 15, "拇指和食指捻"), paragraphs)

        val expectedStart = paragraphs[0].indexOf("拇指和食指捻")
        assertEquals(AnchorResolution.Resolved(0, expectedStart, expectedStart + 6), resolution)
    }

    @Test
    fun `段落被重新切分、原话跑到另一段时，也能搜到`() {
        // 典型场景：NOTES #59 那类"紧凑列表识别"改动会改变段落边界，原来第 0 段的
        // 后半截可能变成独立的第 1 段。
        val paragraphs = listOf("后来车间换了自动检测。", "手感变成一串读数，读数更准。")

        val resolution = AnchorResolver.resolve(anchor(0, 11, 18, "手感变成一串读数"), paragraphs)

        assertEquals(AnchorResolution.Resolved(1, 0, 8), resolution)
    }

    @Test
    fun `同一页里原话出现多次时，选离原来段落最近的那一处`() {
        // 不能随便挑第一个命中的——用户高亮的是某一处，跳到另一处等于改了他的批注
        // 内容。离原始段落序号最近是个确定性的规则，比"第一个命中"更接近原意。
        val paragraphs = listOf("读数更准。", "读数更准。", "读数更准。")

        val resolution = AnchorResolver.resolve(anchor(2, 0, 4, "读数更准"), paragraphs)

        assertEquals(AnchorResolution.Resolved(2, 0, 4), resolution)
    }

    @Test
    fun `整页都搜不到原话时认输，不猜一个位置出来`() {
        val paragraphs = listOf("完全无关的一段文字。")

        val resolution = AnchorResolver.resolve(anchor(0, 0, 6, "手感变成一串读数"), paragraphs)

        assertEquals(AnchorResolution.Lost, resolution)
    }

    @Test
    fun `段落序号越界时不崩溃，退回按原话搜索`() {
        // 页面内容变少（比如某页从"逐段重排"变成"整页栅格化"再变回来）时会出现。
        val paragraphs = listOf("手感变成一串读数，读数更准。")

        val resolution = AnchorResolver.resolve(anchor(7, 0, 8, "手感变成一串读数"), paragraphs)

        assertEquals(AnchorResolution.Resolved(0, 0, 8), resolution)
    }

    @Test
    fun `字符偏移越界时不崩溃，退回按原话搜索`() {
        val paragraphs = listOf("手感变成一串读数。")

        val resolution = AnchorResolver.resolve(anchor(0, 90, 98, "手感变成一串读数"), paragraphs)

        assertEquals(AnchorResolution.Resolved(0, 0, 8), resolution)
    }

    @Test
    fun `空页面直接认输`() {
        val resolution = AnchorResolver.resolve(anchor(0, 0, 4, "读数更准"), emptyList())

        assertEquals(AnchorResolution.Lost, resolution)
    }
}
