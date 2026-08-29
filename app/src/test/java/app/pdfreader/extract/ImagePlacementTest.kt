package app.pdfreader.extract

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ImagePlacement] 的单元测试——"这张图该插在哪个段落之后"这个纯逻辑，不依赖
 * Bitmap/PDFBox/任何 Android API，普通 JUnit 就能跑，不需要 Robolectric。
 *
 * 对应 SELECTION.md 第 4 节兜底方案第 3 条："按它们在原页面中大致所处的段落位置，
 * 插入到对应段落之间"——这里落地成"按页归类"这个降级版本：不追求图片在页面内的
 * 精确纵坐标，只知道"这张图在第几页"，插在"属于这一页、顺序最后的那个文字段落"
 * 之后；这一页如果一个文字段落都没有（比如整页就是一张图），就退到前一个仍有文字
 * 的页；如果连前面所有页都没有文字段落（比如文档从头就是图片），插在最前面。
 */
class ImagePlacementTest {

    @Test
    fun `单页单段落时图片插在该段落之后`() {
        val index = ImagePlacement.afterParagraphIndex(paragraphPages = listOf(1), imagePage = 1)
        assertEquals(0, index)
    }

    @Test
    fun `同页有多个段落时图片插在该页最后一个段落之后`() {
        val index = ImagePlacement.afterParagraphIndex(paragraphPages = listOf(1, 1, 1), imagePage = 1)
        assertEquals(2, index)
    }

    @Test
    fun `跨页时图片插在自己所在页最后一个段落之后，不越到下一页`() {
        // 段落 0、1 在第 1 页，段落 2、3 在第 2 页。
        val paragraphPages = listOf(1, 1, 2, 2)

        assertEquals(1, ImagePlacement.afterParagraphIndex(paragraphPages, imagePage = 1))
        assertEquals(3, ImagePlacement.afterParagraphIndex(paragraphPages, imagePage = 2))
    }

    @Test
    fun `图片所在页比最后一个文字段落所在页还靠后时，插在全部段落之后`() {
        // 只有第 1、2 页有文字，图片在第 3 页（比如末页是纯图片页）。
        val paragraphPages = listOf(1, 2)
        val index = ImagePlacement.afterParagraphIndex(paragraphPages, imagePage = 3)
        assertEquals(1, index)
    }

    @Test
    fun `图片所在页比第一个文字段落所在页还靠前时，插在所有段落之前`() {
        // 第 1 页是纯图片页（没有文字段落），文字从第 2 页才开始。
        val paragraphPages = listOf(2, 2)
        val index = ImagePlacement.afterParagraphIndex(paragraphPages, imagePage = 1)
        assertEquals(-1, index)
    }

    @Test
    fun `没有任何文字段落时，图片一律插在最前面`() {
        val index = ImagePlacement.afterParagraphIndex(paragraphPages = emptyList(), imagePage = 1)
        assertEquals(-1, index)
    }

    // ---- afterParagraphIndexForRegion：2026-08-19 增量，见类注释"表格区域裁剪"配套一节 ----

    @Test
    fun `表格前后都有正文时，表格图片插在表格开始位置之前那个段落之后`() {
        // 第 2 页有 3 段正文，分别在距页顶 50、80、400——表格区域从 90 开始，应该
        // 插在下标 1（坐标 80）之后，不是这一页最后一段（下标 2，坐标 400）之后。
        val paragraphPages = listOf(1, 2, 2, 2)
        val paragraphTopY = listOf(50f, 50f, 80f, 400f)
        val index = ImagePlacement.afterParagraphIndexForRegion(
            paragraphPages,
            paragraphTopY,
            page = 2,
            regionTopY = 90f,
        )
        assertEquals(2, index)
    }

    @Test
    fun `表格在页面最开头（表格上方没有正文）时，图片插在前一页最后一个段落之后`() {
        val paragraphPages = listOf(1, 1, 2, 2)
        val paragraphTopY = listOf(50f, 80f, 300f, 400f)
        val index = ImagePlacement.afterParagraphIndexForRegion(
            paragraphPages,
            paragraphTopY,
            page = 2,
            regionTopY = 50f, // 比这一页第一段（300）还靠上，说明表格在页面最顶部。
        )
        assertEquals(1, index)
    }

    @Test
    fun `同一页两个表格（分别在正文前后）应该分别插在各自对应的位置`() {
        val paragraphPages = listOf(1, 1, 1)
        val paragraphTopY = listOf(50f, 200f, 400f)
        val firstTable = ImagePlacement.afterParagraphIndexForRegion(
            paragraphPages,
            paragraphTopY,
            page = 1,
            regionTopY = 100f,
        )
        val secondTable = ImagePlacement.afterParagraphIndexForRegion(
            paragraphPages,
            paragraphTopY,
            page = 1,
            regionTopY = 300f,
        )
        assertEquals(0, firstTable)
        assertEquals(1, secondTable)
    }

    @Test
    fun `没有任何文字段落时，表格图片一律插在最前面`() {
        val index = ImagePlacement.afterParagraphIndexForRegion(
            emptyList(),
            emptyList(),
            page = 1,
            regionTopY = 50f,
        )
        assertEquals(-1, index)
    }

    // ---- afterParagraphIndexByTopY：2026-08-28 真机反馈修复（"图片和文字分开了"）配套一节 ----

    @Test
    fun `图片落在两个段落中间时 插在topY较小的那个段落之后`() {
        // 段落分别距页顶 20、150；图片距页顶 70，落在两者中间，应该插在段落 0 之后。
        val index = ImagePlacement.afterParagraphIndexByTopY(paragraphTopYs = listOf(20f, 150f), imageTopY = 70f)
        assertEquals(0, index)
    }

    @Test
    fun `图片在所有段落下方时 插在最后一个段落之后`() {
        val index = ImagePlacement.afterParagraphIndexByTopY(paragraphTopYs = listOf(20f, 150f), imageTopY = 220f)
        assertEquals(1, index)
    }

    @Test
    fun `图片在所有段落上方时 插在最前面`() {
        val index = ImagePlacement.afterParagraphIndexByTopY(paragraphTopYs = listOf(100f, 200f), imageTopY = 10f)
        assertEquals(-1, index)
    }

    @Test
    fun `没有任何文字段落时 图片按topY一律插在最前面`() {
        val index = ImagePlacement.afterParagraphIndexByTopY(paragraphTopYs = emptyList(), imageTopY = 50f)
        assertEquals(-1, index)
    }

    @Test
    fun `多张图片落在同一段落区间时 各自算出同一个插入下标`() {
        val paragraphTopYs = listOf(20f, 150f)
        assertEquals(0, ImagePlacement.afterParagraphIndexByTopY(paragraphTopYs, imageTopY = 60f))
        assertEquals(0, ImagePlacement.afterParagraphIndexByTopY(paragraphTopYs, imageTopY = 90f))
    }
}
