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
        // 下标 0（日期）、2（网址）、3（页码，跟日期/网址同页）判定为水印；下标 1
        // （书名）只出现在这一页、不满足"标题行"检测的重复率门槛，不处理，见类 KDoc
        // "标题行"一节。
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

    // ---- 标题行（2026-08-19 补）：见 RunningFooterFilter 类 KDoc"标题行"一节 ----

    @Test
    fun `短文字在多数页一字不差重复出现，判定为运行标题水印`() {
        // 6 页文档，"幸福生命手册（2025）"在其中 5 页都出现（超过一半），应该被判定
        // 为运行标题；每页各自的正文段落不重复，不该被误伤。
        val lines = mutableListOf<PageTextLine>()
        for (page in 1..6) {
            if (page != 4) lines.add(PageTextLine("幸福生命手册（2025）", page = page))
            lines.add(PageTextLine("第${page}页各自不同的正文内容。", page = page))
        }
        val titleIndices = lines.indices.filter { lines[it].text == "幸福生命手册（2025）" }.toSet()

        assertEquals(titleIndices, RunningFooterFilter.noiseIndices(lines))
    }

    @Test
    fun `重复次数够但占比不到一半，不判定为运行标题（长文档里偶尔重复的分隔符）`() {
        // 20 页文档，"* * *"这个分隔符只在 4 页出现——次数够 3 次，但占比 4/20=20%
        // 远低于一半，不该被当成运行标题过滤掉。
        val lines = mutableListOf<PageTextLine>()
        for (page in 1..20) {
            if (page % 5 == 0) lines.add(PageTextLine("* * *", page = page))
            lines.add(PageTextLine("第${page}页各自不同的正文内容。", page = page))
        }
        val separatorIndices = lines.indices.filter { lines[it].text == "* * *" }

        val result = RunningFooterFilter.noiseIndices(lines)
        separatorIndices.forEach { assertEquals(false, it in result) }
    }

    @Test
    fun `占比够但只出现2次，不判定为运行标题（次数门槛没过）`() {
        // 2 页文档，两页都有同一句短话——占比 100%，但只有 2 个不同页，没到"至少 3 个
        // 不同页"这个绝对数量门槛，避免短文档里偶然重复两次就被误判。
        val lines = listOf(
            PageTextLine("同一句话", page = 1),
            PageTextLine("同一句话", page = 2),
        )
        assertEquals(emptySet<Int>(), RunningFooterFilter.noiseIndices(lines))
    }

    @Test
    fun `重复的文字太长，不当作标题行处理，就算重复率再高也不过滤`() {
        val longText = "这是一段刻意写得很长的重复文字，长度超过标题行长度上限，" +
            "即使在所有页都完整重复出现，也不应该被当成运行标题过滤掉，因为真正的" +
            "运行标题/页眉一般都很短。"
        val lines = (1..5).map { PageTextLine(longText, page = it) }
        assertEquals(emptySet<Int>(), RunningFooterFilter.noiseIndices(lines))
    }
}
