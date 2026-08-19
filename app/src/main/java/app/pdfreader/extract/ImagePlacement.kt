package app.pdfreader.extract

/**
 * 纯逻辑：算出"一张图片应该插在哪个文字段落之后"，不依赖 Bitmap/PDFBox/任何
 * Android API，是 [PdfTextExtractor] 图片降级方案里唯一值得单独拆出来做 TDD 的部分
 * （其余部分——遍历 XObject、解码 Bitmap——本质是调用第三方库，行为由 PDFBox 决定，
 * 不是"我们自己的逻辑"）。
 *
 * ## 为什么是"按页归类"而不是"页面内精确纵坐标"
 * SELECTION.md 第 4 节兜底方案原话："按它们在原页面中大致所处的段落位置，插入到
 * 对应段落之间……不追求图片精确嵌入原位置"。调研过程中确认 PDFBox-Android 确实有
 * 能拿到页面内精确坐标的机制（继承 `PDFGraphicsStreamEngine`，在 `drawImage`
 * 回调里读当前变换矩阵 CTM，算出图片在页面坐标系里的包围盒），但这条路径要求：
 * 1. 额外维护一份与文字行完全独立的坐标空间处理逻辑（图形流引擎和文字流引擎是
 *    PDFBox 里两套不同的 `PDFStreamEngine` 子类，各自维护自己的 CTM/图形状态栈）；
 * 2. 现有 [PdfTextExtractor] 的段落切分启发式（比较相邻行 y 间距）本身只在单页内
 *    有意义——y 坐标每翻一页就从页顶重新开始，这是本次增量顺带发现并修的一个潜在
 *    bug（见 [PdfTextExtractor] 里 `linesToParagraphs` 现在会在跨页时强制切段落）；
 *    要做"图片精确插入某一行文字之间"，就必须先把这套单页坐标系推广成跨页也能比较
 *    的统一坐标，工程量和出错面显著变大，换来的精度收益在"独立浮动展示、不追求
 *    精确嵌入原位置"这个产品目标下不成比例。
 *
 * 权衡下来选"按页归类"：只需要知道"这张图在第几页"（[PDResources] 遍历时天然
 * 知道）和"这个文字段落在第几页"（[PdfTextExtractor] 的 `PDFTextStripper` 子类里
 * `getCurrentPageNo()` 本来就有），不需要 CTM、不需要新的坐标系，实现简单、稳健，
 * 且已经能达成"独立浮动展示在大致对应的段落之间"这个降级目标。
 */
object ImagePlacement {

    /**
     * @param paragraphPages 每个文字段落所在的页码，按段落顺序排列（页码从 1 起，
     *   与 [PdfTextExtractor] 里 `PDFTextStripper.getCurrentPageNo()` 的编号一致，
     *   但本函数不关心具体从几开始，只关心相对大小关系）。
     * @param imagePage 这张图片所在的页码。
     * @return 应该插入在 `paragraphPages` 的哪个下标之后（0-based）；`-1` 表示插在
     *   所有段落之前（图片所在页比第一个有文字的页还靠前，或者整个文档没有任何
     *   文字段落）。
     */
    fun afterParagraphIndex(paragraphPages: List<Int>, imagePage: Int): Int {
        var result = -1
        for (index in paragraphPages.indices) {
            if (paragraphPages[index] <= imagePage) {
                result = index
            } else {
                break
            }
        }
        return result
    }

    /**
     * [afterParagraphIndex] 的页内精确版——2026-08-19 增量，配合 [PdfTextExtractor]
     * "表格区域裁剪"这个功能：一页里表格只占一部分，表格前后都可能有正文段落，图片
     * （表格渲染出来的那张）该插在"表格开始位置之前最后一个段落"之后，不是简单地插
     * 在"这一页最后一个段落"之后（那样会把表格后面的正文也排到表格图片前面去）。
     *
     * @param paragraphPages 每个文字段落所在的页码，按段落顺序排列。
     * @param paragraphTopY 每个段落第一行距页面顶部的距离（pt），跟 [paragraphPages]
     *   下标一一对应，跟 [regionTopY] 同一套坐标系（都是"距页顶多少 pt"）。
     * @param page 表格所在的页码。
     * @param regionTopY 表格区域顶部距页面顶部的距离（pt）。
     * @return 应该插入在哪个段落下标之后（0-based）；`-1` 表示插在所有段落之前。
     */
    fun afterParagraphIndexForRegion(
        paragraphPages: List<Int>,
        paragraphTopY: List<Float>,
        page: Int,
        regionTopY: Float,
    ): Int {
        var result = -1
        for (index in paragraphPages.indices) {
            val isBeforeRegion = paragraphPages[index] < page ||
                (paragraphPages[index] == page && paragraphTopY[index] < regionTopY)
            if (isBeforeRegion) result = index
        }
        return result
    }
}
