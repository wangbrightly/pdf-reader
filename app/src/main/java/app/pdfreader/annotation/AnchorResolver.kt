package app.pdfreader.annotation

/** [AnchorResolver.resolve] 的结果，见该函数 KDoc。 */
sealed interface AnchorResolution {
    data class Resolved(val paragraphIndex: Int, val startOffset: Int, val endOffset: Int) : AnchorResolution

    /** 这一页里找不到锚点的原话。调用方仍拿得到 [TextAnchor.quotedText]，不算丢数据。 */
    data object Lost : AnchorResolution
}

object AnchorResolver {

    /**
     * 把存下来的锚点解析成"这一页当前的文字里，该高亮哪一段"。
     *
     * 三级策略，从最有把握到最没把握：
     * 1. 按存的段落序号 + 字符偏移取一段文字，跟 [TextAnchor.quotedText] 一致就直接用
     *    （绝大多数情况——抽取逻辑没变过的文档每次打开结果都一样）；
     * 2. 对不上（抽取算法改过、段落边界变了）就在整页里搜原话，搜到就用搜到的位置；
     *    同一页出现多次时取**离原来段落序号最近**的那处——不能随便挑第一个命中的，
     *    用户高亮的是某一处，跳到另一处等于替他改了批注内容；
     * 3. 整页都搜不到就 [AnchorResolution.Lost]，**不猜**一个位置出来。宁可这条批注
     *    在正文里不显示（列表里还看得到原话），也不要把高亮画到一段用户没标过的
     *    文字上——那是在伪造用户的痕迹。
     *
     * @param paragraphsOnPage 这一页当前抽出来的所有段落文字，顺序即段落序号。
     */
    fun resolve(anchor: TextAnchor, paragraphsOnPage: List<String>): AnchorResolution {
        if (anchor.quotedText.isEmpty() || paragraphsOnPage.isEmpty()) return AnchorResolution.Lost

        val storedParagraph = paragraphsOnPage.getOrNull(anchor.paragraphIndex)
        if (storedParagraph != null &&
            anchor.startOffset in 0..storedParagraph.length &&
            anchor.endOffset in anchor.startOffset..storedParagraph.length &&
            storedParagraph.substring(anchor.startOffset, anchor.endOffset) == anchor.quotedText
        ) {
            return AnchorResolution.Resolved(anchor.paragraphIndex, anchor.startOffset, anchor.endOffset)
        }

        val hit = paragraphsOnPage
            .mapIndexedNotNull { index, paragraph ->
                paragraph.indexOf(anchor.quotedText).takeIf { it >= 0 }?.let { index to it }
            }
            .minByOrNull { (index, _) -> kotlin.math.abs(index - anchor.paragraphIndex) }
            ?: return AnchorResolution.Lost

        val (paragraphIndex, startOffset) = hit
        return AnchorResolution.Resolved(paragraphIndex, startOffset, startOffset + anchor.quotedText.length)
    }
}
