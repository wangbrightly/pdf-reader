package app.pdfreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [OutlineNavigation] 的单元测试——"目录项给的页码 → 应该滚动到 contentContainer 里
 * 第几个 DisplayBlock" 这个纯逻辑，不依赖 Bitmap/PDFBox/任何 Android API，普通 JUnit
 * 就能跑，不需要 Robolectric。写法和 [app.pdfreader.extract.ImagePlacementTest] 同一
 * 个路子（[app.pdfreader.extract.ImagePlacement] 解决"图片插在哪个段落之后"，这里
 * 解决"目录项对应哪个段落、这个段落对应哪个展示块下标"）。
 */
class OutlineNavigationTest {

    // ---- paragraphIndexForPage：目标页码 → 段落下标 ----

    @Test
    fun `目标页有段落时，定位到该页第一个段落`() {
        // 段落 0、1 在第 1 页，段落 2、3 在第 2 页——目标页 2 应该定位到段落 2（第一个），
        // 不是段落 3（最后一个），这样目录项点进去正好停在这一页内容的开头。
        val paragraphPages = listOf(1, 1, 2, 2)
        assertEquals(2, OutlineNavigation.paragraphIndexForPage(paragraphPages, targetPage = 2))
    }

    @Test
    fun `目标页没有段落时，退到下一个有段落的页`() {
        // 第 2 页整页是表格/图片，没有文字段落——目录项指向第 2 页时，退到第 3 页
        // 的第一个段落，好过完全不滚动。
        val paragraphPages = listOf(1, 1, 3, 3)
        assertEquals(2, OutlineNavigation.paragraphIndexForPage(paragraphPages, targetPage = 2))
    }

    @Test
    fun `目标页比最后一个有段落的页还靠后时，退到最后一个段落`() {
        val paragraphPages = listOf(1, 2)
        assertEquals(1, OutlineNavigation.paragraphIndexForPage(paragraphPages, targetPage = 5))
    }

    @Test
    fun `没有任何段落时返回 null`() {
        assertNull(OutlineNavigation.paragraphIndexForPage(emptyList(), targetPage = 1))
    }

    // ---- blockIndexForParagraph：段落下标 → 展示块下标（考虑插在前面的图片）----

    @Test
    fun `没有图片时，展示块下标等于段落下标`() {
        assertEquals(0, OutlineNavigation.blockIndexForParagraph(emptyList(), paragraphIndex = 0))
        assertEquals(2, OutlineNavigation.blockIndexForParagraph(emptyList(), paragraphIndex = 2))
    }

    @Test
    fun `插在段落之前的图片会让展示块下标往后移`() {
        // 一张图片插在段落 -1（最前面）之后，段落 0 对应的展示块下标应该是 1（图片占了 0）。
        assertEquals(1, OutlineNavigation.blockIndexForParagraph(listOf(-1), paragraphIndex = 0))
    }

    @Test
    fun `只有插在目标段落之前的图片才计入偏移，之后的不算`() {
        // 图片分别插在段落 -1、0、2 之后。定位到段落 1 时，只有 afterIndex=-1、0 两张
        // 图片排在它前面（各占一个展示块），afterIndex=2 那张图片排在它后面不影响。
        val imageAfterIndices = listOf(-1, 0, 2)
        assertEquals(1 + 2, OutlineNavigation.blockIndexForParagraph(imageAfterIndices, paragraphIndex = 1))
    }

    // ---- blockIndexForPage：组合以上两步，给 MainActivity 直接用的入口 ----

    @Test
    fun `组合计算——目标页有段落且前面插了图片`() {
        val paragraphPages = listOf(1, 1, 2, 2)
        val imageAfterIndices = listOf(-1) // 一张图片插在所有段落之前
        // 目标页 2 → 段落下标 2（该页第一个段落）→ 展示块下标 2 + 1（前面那张图片）= 3。
        assertEquals(3, OutlineNavigation.blockIndexForPage(paragraphPages, imageAfterIndices, targetPage = 2))
    }

    @Test
    fun `没有任何段落时组合计算也返回 null`() {
        assertNull(OutlineNavigation.blockIndexForPage(emptyList(), emptyList(), targetPage = 1))
    }

    // ---- paragraphIndexForDestination：目标页码 + 页内精确位置 → 段落下标 ----
    // 见类注释"页内精确定位"一节：2026-08-19 增量，修复"目录跳转位置不够准"的反馈。

    @Test
    fun `目标页内有多个段落时，定位到距目标坐标最近的那一段，不是页首那一段`() {
        // 第 2 页有 3 个段落，分别在距页顶 100、300、500 pt。目录项目标坐标是 310——
        // 应该落在坐标 300 那一段（下标 2），而不是页首坐标 100 那一段（下标 1）。
        val paragraphPages = listOf(1, 2, 2, 2, 3)
        val paragraphTopY = listOf(50f, 100f, 300f, 500f, 50f)
        assertEquals(
            2,
            OutlineNavigation.paragraphIndexForDestination(
                paragraphPages,
                paragraphTopY,
                targetPage = 2,
                targetY = 310f,
            ),
        )
    }

    @Test
    fun `同一页两个不同的目录项应该落到不同的段落（这正是页内定位要解决的问题）`() {
        // "第三章"和它的"3.1 小节"都在第 2 页——旧的按页跳转会让两个目录项跳到
        // 完全相同的位置，加了页内坐标之后应该能区分开。
        val paragraphPages = listOf(1, 2, 2, 2)
        val paragraphTopY = listOf(50f, 80f, 90f, 400f)
        val chapterIndex = OutlineNavigation.paragraphIndexForDestination(
            paragraphPages,
            paragraphTopY,
            targetPage = 2,
            targetY = 82f, // "第三章"标题坐标
        )
        val sectionIndex = OutlineNavigation.paragraphIndexForDestination(
            paragraphPages,
            paragraphTopY,
            targetPage = 2,
            targetY = 395f, // "3.1 小节"标题坐标
        )
        assertEquals(1, chapterIndex)
        assertEquals(3, sectionIndex)
    }

    @Test
    fun `targetY 为 null 时退化成按页跳转（页首那一段）`() {
        val paragraphPages = listOf(1, 2, 2, 2)
        val paragraphTopY = listOf(50f, 80f, 90f, 400f)
        assertEquals(
            1,
            OutlineNavigation.paragraphIndexForDestination(paragraphPages, paragraphTopY, targetPage = 2, targetY = null),
        )
    }

    @Test
    fun `有 targetY 但目标页恰好没有任何段落时，退化成按页跳转的降级逻辑`() {
        // 第 2 页整页是表格/图片，没有文字段落——即使目录项带了页内坐标也没地方可用，
        // 应该跟没有坐标时一样退到第 3 页的第一个段落。
        val paragraphPages = listOf(1, 1, 3, 3)
        val paragraphTopY = listOf(50f, 200f, 50f, 200f)
        assertEquals(
            2,
            OutlineNavigation.paragraphIndexForDestination(
                paragraphPages,
                paragraphTopY,
                targetPage = 2,
                targetY = 120f,
            ),
        )
    }

    // ---- blockIndexForDestination：组合以上 + blockIndexForParagraph，MainActivity 直接用的入口 ----

    @Test
    fun `组合计算——按页内坐标定位到具体段落，再加上前面插入的图片偏移`() {
        val paragraphPages = listOf(1, 2, 2)
        val paragraphTopY = listOf(50f, 80f, 400f)
        val imageAfterIndices = listOf(-1) // 一张图片插在所有段落之前
        // targetY=395 应该落到段落下标 2（坐标 400 那一段）→ 展示块下标 2 + 1 = 3。
        assertEquals(
            3,
            OutlineNavigation.blockIndexForDestination(
                paragraphPages,
                paragraphTopY,
                imageAfterIndices,
                targetPage = 2,
                targetY = 395f,
            ),
        )
    }

    @Test
    fun `没有任何段落时组合计算（页内定位版）也返回 null`() {
        assertNull(
            OutlineNavigation.blockIndexForDestination(
                emptyList(),
                emptyList(),
                emptyList(),
                targetPage = 1,
                targetY = null,
            ),
        )
    }
}
