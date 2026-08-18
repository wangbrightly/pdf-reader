package app.pdfreader.ui

/**
 * 纯逻辑：把"目录（大纲）项指向第几页"换算成"应该滚动到 [app.pdfreader.MainActivity]
 * 的 `contentContainer` 里第几个 [DisplayBlock]"，不依赖 Bitmap/PDFBox/任何 Android
 * API。写法沿用 [app.pdfreader.extract.ImagePlacement] 的思路（按页归类，不追求
 * 页面内精确位置），拆成两步：
 *
 * 1. [paragraphIndexForPage]：目标页码 → 应该定位到 `PdfContent.paragraphs` 的哪个
 *    下标。跟 [app.pdfreader.extract.ImagePlacement.afterParagraphIndex] 刻意反过来
 *    ——那边是"找这一页最后一个段落"（图片跟在文字后面），这里是"找这一页第一个
 *    段落"（跳转应该停在这一页内容的开头，不是结尾）。如果目标页本身没有文字段落
 *    （比如整页是表格/图片降级出来的），退到下一个有段落的页；如果目标页比所有
 *    段落所在页都靠后，退到最后一个段落——两种情况都好过完全不滚动。
 * 2. [blockIndexForParagraph]：段落下标 → 展示块下标。[app.pdfreader.MainActivity.buildDisplayBlocks]
 *    按"图片(afterIndex=-1) → 段落0 → 图片(afterIndex=0) → 段落1 → ……"的顺序把段落
 *    和图片合并成一份展示块列表，所以段落 p 对应的展示块下标 = p + 排在它前面的图片
 *    数量（即 `afterParagraphIndex < p` 的图片，覆盖 -1 到 p-1 这个区间，与
 *    `buildDisplayBlocks` 里 `appendImagesAfter` 的调用顺序完全对应）。
 *
 * [blockIndexForPage] 把两步串起来，是 `MainActivity` 点击目录项时直接调用的入口。
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
     * @param imageAfterParagraphIndices 每张图片的 [app.pdfreader.extract.ExtractedImage.afterParagraphIndex]。
     * @param paragraphIndex 目标段落下标（0-based）。
     * @return 这个段落在展示块列表里的下标（0-based）。
     */
    fun blockIndexForParagraph(imageAfterParagraphIndices: List<Int>, paragraphIndex: Int): Int {
        val imagesBefore = imageAfterParagraphIndices.count { it < paragraphIndex }
        return paragraphIndex + imagesBefore
    }

    /** 组合 [paragraphIndexForPage] + [blockIndexForParagraph]，供 `MainActivity` 直接调用。 */
    fun blockIndexForPage(
        paragraphPages: List<Int>,
        imageAfterParagraphIndices: List<Int>,
        targetPage: Int,
    ): Int? {
        val paragraphIndex = paragraphIndexForPage(paragraphPages, targetPage) ?: return null
        return blockIndexForParagraph(imageAfterParagraphIndices, paragraphIndex)
    }
}
