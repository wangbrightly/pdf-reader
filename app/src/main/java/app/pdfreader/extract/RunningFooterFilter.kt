package app.pdfreader.extract

/** 一行被抽取出来的文字 + 它所在的页码（1-based）——[RunningFooterFilter] 的输入单元。 */
data class PageTextLine(val text: String, val page: Int)

/**
 * 识别"浏览器打印 PDF 时自动加在每页的页眉/页脚水印"，从抽取结果里过滤掉。
 *
 * ## 背景
 *
 * 真机反馈一份"网页打印成 PDF"的文档，图片（整页渲染的表格页）之间夹着一段很显眼
 * 的文字：打印时间（`2026/7/10 23:21`）、文档标题（`幸福生命手册（2025）`）、来源
 * 网址、页码计数（`22/136`）——这是浏览器"打印到 PDF"功能自带的页眉页脚，几乎每一页
 * 都有，不是书本身的正文，用户反馈"可以去掉吗"。
 *
 * ## 只处理三种narrow、可精确匹配的样式，不处理标题行
 *
 * 用正则精确匹配"这一整段文字只有、且刚好是"下面三种样式之一，不是"文字里包含"：
 *
 * - 纯网址一行（[URL_ONLY]）：正常书籍正文段落不会是"一整段只有一个网址、没有任何
 *   其它文字"，这种样式本身就足够罕见，不需要再叠加"要出现很多次才算"的门槛。
 * - 纯日期时间一行（[DATE_TIME_ONLY]）：同上，一整段只有"年/月/日 时:分"这几个数字
 *   和分隔符，正常正文不会长这样。
 * - 纯"当前页/总页数"一行（[PAGE_COUNTER_ONLY]）：**这一种单独出现有真实的误判
 *   风险**——数学题、菜谱里的分数（"3/4 杯面粉"）单独成段并不罕见。所以这一种不是
 *   无条件过滤，而是加了个上下文条件：只有当**同一页**上还存在纯网址或纯日期时间
 *   这两种更确定的水印特征时，才把它一起当水印过滤——这样"孤立出现的分数"不会被
 *   误伤，只有真的跟网址/日期同一页出现的"页码计数"才会被判定为水印的一部分。
 *
 * **故意不处理标题行**（比如"幸福生命手册（2025）"这种在每页重复出现的文档标题）：
 * 这一行没有固定的结构特征可以用正则精确匹配，唯一能用的信号是"这段文字在很多页
 * 上一字不差地重复出现"——但这个信号同时也会命中真实书籍里合理重复的内容（章节
 * 分隔符、反复出现的引言/警示语），误伤面比前三种大得多，这次先不做，等用户确认
 * 确实还需要再处理。
 */
object RunningFooterFilter {
    private val URL_ONLY = Regex("""^https?://\S+$""")
    private val DATE_TIME_ONLY = Regex("""^\d{4}/\d{1,2}/\d{1,2}\s+\d{1,2}:\d{2}(:\d{2})?$""")
    private val PAGE_COUNTER_ONLY = Regex("""^\d+/\d+$""")

    /** @return [lines] 里判定为页眉/页脚水印的下标集合（0-based），调用方据此过滤掉。 */
    fun noiseIndices(lines: List<PageTextLine>): Set<Int> {
        val urlOrDateIndices = lines.indices.filter { index ->
            val text = lines[index].text.trim()
            URL_ONLY.matches(text) || DATE_TIME_ONLY.matches(text)
        }
        val pagesWithUrlOrDate = urlOrDateIndices.mapTo(mutableSetOf()) { lines[it].page }
        val counterIndices = lines.indices.filter { index ->
            val text = lines[index].text.trim()
            PAGE_COUNTER_ONLY.matches(text) && lines[index].page in pagesWithUrlOrDate
        }
        return (urlOrDateIndices + counterIndices).toSet()
    }
}
