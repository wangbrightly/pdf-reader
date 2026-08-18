package app.pdfreader.extract

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RunningFooterFilter] 的单元测试——纯逻辑，不依赖 PDFBox/Android，见该类 KDoc。
 */
class RunningFooterFilterTest {

    @Test
    fun `纯网址一行判定为水印`() {
        val lines = listOf(PageTextLine("https://baike.azpdl.net/#/entry/abc-123", page = 1))
        assertEquals(setOf(0), RunningFooterFilter.noiseIndices(lines))
    }

    @Test
    fun `纯日期时间一行判定为水印`() {
        val lines = listOf(PageTextLine("2026/7/10 23:21", page = 1))
        assertEquals(setOf(0), RunningFooterFilter.noiseIndices(lines))
    }

    @Test
    fun `网址前后有其它文字就不算纯网址行，不判定为水印`() {
        val lines = listOf(PageTextLine("详情见 https://example.com 这一节", page = 1))
        assertEquals(emptySet<Int>(), RunningFooterFilter.noiseIndices(lines))
    }

    @Test
    fun `孤立的页码计数行（同一页没有网址或日期）不判定为水印，避免误伤菜谱分数`() {
        // 比如菜谱"3/4 杯面粉"单独成段——同一页没有网址/日期这两个更确定的水印特征，
        // 不该被误伤。
        val lines = listOf(PageTextLine("3/4", page = 1))
        assertEquals(emptySet<Int>(), RunningFooterFilter.noiseIndices(lines))
    }

    @Test
    fun `页码计数行和纯网址行同一页出现时，页码计数行也判定为水印`() {
        val lines = listOf(
            PageTextLine("2026/7/10 23:21", page = 1),
            PageTextLine("幸福生命手册（2025）", page = 1),
            PageTextLine("https://baike.azpdl.net/#/entry/abc-123", page = 1),
            PageTextLine("22/136", page = 1),
        )
        // 下标 0（日期）、2（网址）、3（页码，跟日期/网址同页）判定为水印；
        // 下标 1（书名）不处理，见类 KDoc"故意不处理标题行"一节。
        assertEquals(setOf(0, 2, 3), RunningFooterFilter.noiseIndices(lines))
    }

    @Test
    fun `页码计数行和水印特征不在同一页时不判定为水印`() {
        val lines = listOf(
            PageTextLine("https://baike.azpdl.net/#/entry/abc-123", page = 1),
            PageTextLine("22/136", page = 2), // 不同页，不该被这一页的网址带上。
        )
        assertEquals(setOf(0), RunningFooterFilter.noiseIndices(lines))
    }

    @Test
    fun `真实的正文段落不判定为水印`() {
        val lines = listOf(
            PageTextLine("这是一段正常的书本正文内容，讲的是自由与责任的关系。", page = 1),
            PageTextLine("公民有投票、监督政府、结社、集会等参与公共事务的权利。", page = 1),
        )
        assertEquals(emptySet<Int>(), RunningFooterFilter.noiseIndices(lines))
    }

    @Test
    fun `空列表返回空集合`() {
        assertEquals(emptySet<Int>(), RunningFooterFilter.noiseIndices(emptyList()))
    }
}
