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
 * ## 标题行（2026-08-19 补）：靠"重复率"而不是靠格式，门槛拉高换安全
 *
 * 比如"幸福生命手册（2025）"这种在每页重复出现的文档标题，没有固定的结构特征可以用
 * 正则精确匹配，唯一能用的信号是"这段文字在很多页上一字不差地重复出现"——这个信号
 * 同时也会命中真实书籍里合理重复的内容（章节分隔符、反复出现的引言/警示语），所以
 * 用两道门槛把误伤面压到最低，两道都要满足：
 *
 * 1. **长度**（[MAX_TITLE_LIKE_LENGTH]）：超过这个长度不当标题处理——运行标题/页眉
 *    通常是短短一行书名/章节名，真实的正文段落（哪怕是重复出现的警示语）一般不会
 *    压缩到这么短还能表达完整意思。
 * 2. **重复率**（[MIN_REPEATED_PAGES] 且 [MIN_REPEATED_PAGE_FRACTION]）：既要求
 *    "至少在这么多个不同页出现过"（绝对数量，排除短文档里偶然重复两次的巧合），
 *    也要求"占全文档页数的比例达到一半以上"（排除长文档里"偶尔重复"的合理内容，
 *    比如每隔几十页出现一次的分隔符）——真正的运行标题/页眉几乎每一页都有，
 *    比例门槛能把它跟"确实只是碰巧重复了几次"的普通内容区分开。
 *
 * 两道门槛都满足才过滤，任何一道不满足就保留——宁可漏掉真水印，也不错杀正常内容，
 * 跟本类其它三种检测同一套"保守"原则。
 */
object RunningFooterFilter {
    private val URL_ONLY = Regex("""^https?://\S+$""")
    private val DATE_TIME_ONLY = Regex("""^\d{4}/\d{1,2}/\d{1,2}\s+\d{1,2}:\d{2}(:\d{2})?$""")
    private val PAGE_COUNTER_ONLY = Regex("""^\d+/\d+$""")

    /** 见类注释"标题行"一节。 */
    private const val MAX_TITLE_LIKE_LENGTH = 60
    private const val MIN_REPEATED_PAGES = 3
    private const val MIN_REPEATED_PAGE_FRACTION = 0.5

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
        val titleLikeIndices = titleLikeNoiseIndices(lines)
        return (urlOrDateIndices + counterIndices + titleLikeIndices).toSet()
    }

    /** 见类注释"标题行"一节：靠"短文字 + 高比例重复出现在不同页"识别运行标题/页眉。 */
    private fun titleLikeNoiseIndices(lines: List<PageTextLine>): List<Int> {
        val totalPages = lines.mapTo(mutableSetOf()) { it.page }.size
        if (totalPages == 0) return emptyList()
        val result = mutableListOf<Int>()
        for (indices in lines.indices.groupBy { lines[it].text.trim() }.values) {
            val text = lines[indices.first()].text.trim()
            if (text.isEmpty() || text.length > MAX_TITLE_LIKE_LENGTH) continue
            val distinctPages = indices.mapTo(mutableSetOf()) { lines[it].page }.size
            val fraction = distinctPages.toDouble() / totalPages
            if (distinctPages >= MIN_REPEATED_PAGES && fraction >= MIN_REPEATED_PAGE_FRACTION) {
                result.addAll(indices)
            }
        }
        return result
    }
}
