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
}
