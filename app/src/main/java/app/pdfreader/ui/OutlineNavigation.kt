package app.pdfreader.ui

import kotlin.math.abs

/**
 * 纯逻辑：把"目录（大纲）项指向第几页"换算成"应该滚动到 [app.pdfreader.MainActivity]
 * 的 `contentContainer` 里第几个 [DisplayBlock]"，不依赖 Bitmap/PDFBox/任何 Android
 * API。拆成两步：
 *
 * 1. [paragraphIndexForDestination]：目标页码（+可选的页内精确位置）→ 应该定位到
 *    `PdfContent.paragraphs` 的哪个下标。
 * 2. [blockIndexForParagraph]：段落下标 → 展示块下标。[app.pdfreader.MainActivity.buildDisplayBlocks]
 *    按"图片(afterIndex=-1) → 段落0 → 图片(afterIndex=0) → 段落1 → ……"的顺序把段落
 *    和图片合并成一份展示块列表，所以段落 p 对应的展示块下标 = p + 排在它前面的图片
 *    数量（即 `afterParagraphIndex < p` 的图片，覆盖 -1 到 p-1 这个区间，与
 *    `buildDisplayBlocks` 里 `appendImagesAfter` 的调用顺序完全对应）。
 *
 * [blockIndexForDestination] 把两步串起来，是 `MainActivity` 点击目录项时直接调用的
 * 入口。
 *
 * ## 页内精确定位（2026-08-19 增量）
 *
 * 最初的版本只按页归类（沿用 [app.pdfreader.extract.ImagePlacement] 的思路），不管
 * 目标章节在这一页里具体在哪个位置——一律跳到"这一页第一个段落"。用户反馈"目录跳转
 * 位置不够准"：一页内容较多、章节标题不在页首时，跳转落点和标题实际位置有明显偏差；
 * 同一页里有多个目录项（比如"第三章"和它的"3.1 小节"都在同一页）时，两个目录项会
 * 跳到完全相同的位置，分不出来。
 *
 * 根因是"按页归类"这一层直接丢掉了 PDF 目录项本来就带的更精确信息——大部分 `/XYZ`
 * 类型的目录目标（PDF 里最常见的写法）本身就指定了页内的具体 Y 坐标（见
 * [app.pdfreader.extract.PdfTextExtractor.targetTopYOrNull]），只是原来的实现没有用。
 * [paragraphIndexForDestination] 在有这个坐标（[app.pdfreader.extract.OutlineEntry.targetTopY]）
 * 时，直接在目标页的段落里找"第一行位置最接近目标坐标"的那一段，不再总是取页首那一段；
 * 没有这个坐标（`targetY == null`，比如目标是 `/Fit` 这类没有精确坐标的类型，或者
 * 那一页恰好没有任何文字段落）时，退化成原来的"按页取第一段"逻辑
 * （[paragraphIndexForPage]），这是有意的降级，不是遗漏。
 */
object OutlineNavigation {

    /**
     * @param paragraphPages 每个文字段落所在的页码，按段落顺序排列（页码从 1 起）。
     * @param targetPage 目录项指向的页码。
     * @return 应该定位到的段落下标（0-based）；没有任何段落时返回 `null`（无法定位，
     *   调用方应该放弃这次滚动，不强行跳到某个不存在的位置）。
     */
    fun paragraphIndexForPage(paragraphPages: List<Int>, targetPage: Int): Int? {
        if (paragraphPages.isEmpty()) return null
        val index = paragraphPages.indexOfFirst { it >= targetPage }
        return if (index >= 0) index else paragraphPages.lastIndex
    }

    /**
     * [paragraphIndexForPage] 的页内精确版——见类注释"页内精确定位"一节。
     *
     * @param paragraphPages 每个文字段落所在的页码，按段落顺序排列（页码从 1 起）。
     * @param paragraphTopY 每个段落第一行距页面顶部的距离（pt），跟 [paragraphPages]
     *   下标一一对应，跟 [targetY] 同一套坐标系。
     * @param targetPage 目录项指向的页码。
     * @param targetY 目录项在目标页内的精确位置；`null` 表示解析不出来，直接退化成
     *   [paragraphIndexForPage]。
     * @return 应该定位到的段落下标（0-based）；没有任何段落时返回 `null`。
     */
    fun paragraphIndexForDestination(
        paragraphPages: List<Int>,
        paragraphTopY: List<Float>,
        targetPage: Int,
        targetY: Float?,
    ): Int? {
        if (targetY != null) {
            val candidatesOnPage = paragraphPages.indices.filter { paragraphPages[it] == targetPage }
            if (candidatesOnPage.isNotEmpty()) {
                return candidatesOnPage.minByOrNull { abs(paragraphTopY[it] - targetY) }
            }
        }
        return paragraphIndexForPage(paragraphPages, targetPage)
    }

    /**
     * @param imageAfterParagraphIndices 每张图片的 [app.pdfreader.extract.ExtractedImage.afterParagraphIndex]。
     * @param paragraphIndex 目标段落下标（0-based）。
     * @return 这个段落在展示块列表里的下标（0-based）。
     */
    fun blockIndexForParagraph(imageAfterParagraphIndices: List<Int>, paragraphIndex: Int): Int {
        val imagesBefore = imageAfterParagraphIndices.count { it < paragraphIndex }
        return paragraphIndex + imagesBefore
    }

    /** 组合 [paragraphIndexForPage] + [blockIndexForParagraph]，只按页跳转，不看页内位置。 */
    fun blockIndexForPage(
        paragraphPages: List<Int>,
        imageAfterParagraphIndices: List<Int>,
        targetPage: Int,
    ): Int? {
        val paragraphIndex = paragraphIndexForPage(paragraphPages, targetPage) ?: return null
        return blockIndexForParagraph(imageAfterParagraphIndices, paragraphIndex)
    }

    /**
     * 组合 [paragraphIndexForDestination] + [blockIndexForParagraph]，供 `MainActivity`
     * 点击目录项时直接调用——见类注释"页内精确定位"一节。
     */
    fun blockIndexForDestination(
        paragraphPages: List<Int>,
        paragraphTopY: List<Float>,
        imageAfterParagraphIndices: List<Int>,
        targetPage: Int,
        targetY: Float?,
    ): Int? {
        val paragraphIndex = paragraphIndexForDestination(paragraphPages, paragraphTopY, targetPage, targetY)
            ?: return null
        return blockIndexForParagraph(imageAfterParagraphIndices, paragraphIndex)
    }
}
