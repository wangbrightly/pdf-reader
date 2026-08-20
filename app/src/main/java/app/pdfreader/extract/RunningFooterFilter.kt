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
 * - 网址后面紧跟页码计数（[URL_WITH_TRAILING_COUNTER]，2026-08-19 真机反馈补）：同一份
 *   "网页打印成 PDF"来源的文档，不同文档/不同页footer 的行间距不一样，有的文档里网址
 *   行和页码计数行的垂直间距小于段落切分阈值，被 [PdfTextExtractor.linesToParagraphs]
 *   合并成了同一段（`appendLine` 在网址和数字之间插一个空格），变成
 *   `https://example.com/xxx 5/136` 这样一整段——跟纯网址一样，一段话"只有网址+页码
 *   计数、没有其它文字"这个样式本身也足够罕见，不需要额外门槛，无条件当水印处理。
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
 *
 * ## 2026-08-20 增量：按需加载场景下的"样本学习 + 按页应用"
 *
 * [noiseIndices] 是"喂全文档所有行，一次性判断"这个模型——[titleLikeNoiseIndices]
 * 需要看到跨页重复率，天然要求"已经知道全文档有多少行"。文字按需加载（按页加载，
 * 见 [PdfTextExtractor.Session.loadPage]）上线后，"打开文档"这一步不再有"全文档
 * 所有行"可用，但也不能什么都不做——真机反馈过的水印场景（打印时间、来源网址、
 * 重复书名）几乎总是从文档一开始就存在、样式贯穿全文，不需要真的看完全文档才能
 * 学出规律。
 *
 * 拆成两半：
 * 1. **URL/日期/页码计数**（[URL_ONLY]/[DATE_TIME_ONLY]/[URL_WITH_TRAILING_COUNTER]/
 *    [PAGE_COUNTER_ONLY]）——这几条判断本来就只需要"同一页内"的上下文（页码计数
 *    要不要算噪音，只看这一页有没有同时出现网址/日期），跟"看没看过全文档"无关，
 *    可以直接对任意一批行调用，不用改——[regexNoiseIndices] 把这部分逻辑提取出来
 *    单独复用，[noiseIndices]（全文档一次性判断，`extractContent` 那条旧路径继续用）
 *    和 [pageNoiseIndices]（按页判断，`Session.loadPage` 用）内部都调它。
 * 2. **标题类重复**——需要看到"这段文字在多少页出现过"这个跨页统计，没法只看一页。
 *    改成"先用一个页数有限的样本（比如文档前 150 页）学出'这些具体文字应该算噪音'，
 *    存成一个 [Set]，后续按页处理时直接查表"——[learnTitleLikeNoiseTexts] 是学习
 *    入口（样本范围内复用现有 [titleLikeNoiseIndices] 的判断逻辑不变，只是把命中的
 *    下标换算成命中的具体文本），[pageNoiseIndices] 拿着学到的集合按页查表。
 *
 *    已知局限：如果一份文档的页眉/页脚样式要到样本范围之外才第一次出现（比如前
 *    150 页没有，第 151 页才开始有），会漏检——真实场景里页眉页脚样式几乎总是
 *    从头到尾一致，这个假设成立的概率很高，但不是 100% 保证，跟本类一贯的"宁可
 *    漏检"取舍一致。
 */
object RunningFooterFilter {
    private val URL_ONLY = Regex("""^https?://\S+$""")
    private val DATE_TIME_ONLY = Regex("""^\d{4}/\d{1,2}/\d{1,2}\s+\d{1,2}:\d{2}(:\d{2})?$""")
    private val URL_WITH_TRAILING_COUNTER = Regex("""^https?://\S+\s+\d+/\d+$""")
    private val PAGE_COUNTER_ONLY = Regex("""^\d+/\d+$""")

    /** 见类注释"标题行"一节。 */
    private const val MAX_TITLE_LIKE_LENGTH = 60
    private const val MIN_REPEATED_PAGES = 3
    private const val MIN_REPEATED_PAGE_FRACTION = 0.5

    /** @return [lines] 里判定为页眉/页脚水印的下标集合（0-based），调用方据此过滤掉。 */
    fun noiseIndices(lines: List<PageTextLine>): Set<Int> =
        regexNoiseIndices(lines) + titleLikeNoiseIndices(lines)

    /**
     * 见类注释"样本学习 + 按页应用"一节：给一批样本行（通常是文档前若干页），学出
     * "这些具体文字应该算标题类噪音"，返回命中的文本内容集合（已经 `trim()` 过，
     * 跟 [pageNoiseIndices] 里的比较方式一致）。
     */
    fun learnTitleLikeNoiseTexts(sampleLines: List<PageTextLine>): Set<String> {
        val indices = titleLikeNoiseIndices(sampleLines).toSet()
        return indices.mapTo(mutableSetOf()) { sampleLines[it].text.trim() }
    }

    /**
     * 见类注释"样本学习 + 按页应用"一节：[pageLines] 应该是同一页的行（[loadPage] 的
     * 调用场景），URL/日期/页码计数走无状态正则（对这一页单独判断也成立），标题类
     * 直接查 [learnedTitleTexts]（[learnTitleLikeNoiseTexts] 学出来的集合），不再
     * 需要看到其它页的内容。
     */
    fun pageNoiseIndices(pageLines: List<PageTextLine>, learnedTitleTexts: Set<String>): Set<Int> {
        val titleIndices = pageLines.indices.filter { pageLines[it].text.trim() in learnedTitleTexts }
        return regexNoiseIndices(pageLines) + titleIndices
    }

    /** [URL_ONLY]/[DATE_TIME_ONLY]/[URL_WITH_TRAILING_COUNTER]/[PAGE_COUNTER_ONLY] 这四条判断，见类注释。 */
    private fun regexNoiseIndices(lines: List<PageTextLine>): Set<Int> {
        val urlOrDateIndices = lines.indices.filter { index ->
            val text = lines[index].text.trim()
            URL_ONLY.matches(text) || DATE_TIME_ONLY.matches(text) || URL_WITH_TRAILING_COUNTER.matches(text)
        }
        val pagesWithUrlOrDate = urlOrDateIndices.mapTo(mutableSetOf()) { lines[it].page }
        val counterIndices = lines.indices.filter { index ->
            val text = lines[index].text.trim()
            PAGE_COUNTER_ONLY.matches(text) && lines[index].page in pagesWithUrlOrDate
        }
        return (urlOrDateIndices + counterIndices).toSet()
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
