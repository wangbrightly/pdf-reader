package app.pdfreader.extract

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TableGridDetector] 的单元测试——纯逻辑，输入一批矢量线段（来自页面 content stream
 * 里的 `re`/`m l S` 等图形操作符，见 [PdfTextExtractor] 类注释"表格检测"一节），
 * 输出"这一页像不像表格"的布尔判断，不依赖 PDFBox/Bitmap/任何 Android API。
 *
 * 用真实用 Chromium 打印出的含 `<table border>` 的 PDF 反编译 content stream 实测过
 * （`sample-with-table.pdf`，见 [PdfTextExtractorTableTest]）：Chromium 不是用
 * `m l S`（描边线）画表格边框，而是每条边框画成一个"细长的填充矩形"（`re` + `f`，
 * 宽或高只有 1pt），例如 `8 79 193 1 re`（一条横线）和 `8 79 1 36 re`（一条竖线）。
 * 这意味着检测逻辑不能只认"stroke 的直线"，矩形的长边（无论是描边还是填充）都要
 * 当作候选线段——[LineSegment] 不区分"这条线是怎么画出来的"，只关心"这条边在页面
 * 坐标系里的两个端点"，画法细节交给 PDFBox 引擎适配层处理（不在这层纯逻辑里）。
 */
class TableGridDetectorTest {

    @Test
    fun `没有任何线段时不像表格（纯文字页的典型情况）`() {
        assertFalse(TableGridDetector.looksLikeTable(emptyList()))
    }

    @Test
    fun `3列4行的规整网格线判定为像表格`() {
        // 4 行需要 5 条横线边界，3 列需要 4 条竖线边界——用真实 fixture 反编译出的
        // 坐标数量级（0-700pt 量级的 A4 页面）构造，模拟真实表格的网格线。
        val horizontalYs = listOf(79f, 115f, 151f, 186f, 220f)
        val verticalXs = listOf(8f, 200f, 442f, 634f)
        val segments = mutableListOf<LineSegment>()
        val left = verticalXs.first()
        val right = verticalXs.last()
        for (y in horizontalYs) segments.add(LineSegment(left, y, right, y))
        val top = horizontalYs.first()
        val bottom = horizontalYs.last()
        for (x in verticalXs) segments.add(LineSegment(x, top, x, bottom))

        assertTrue(TableGridDetector.looksLikeTable(segments))
    }

    @Test
    fun `只有一条长横线（页眉下的分隔线）不像表格`() {
        val segments = listOf(LineSegment(50f, 700f, 550f, 700f))
        assertFalse(TableGridDetector.looksLikeTable(segments))
    }

    @Test
    fun `只有孤立的竖线（多栏排版之间的分隔线）不像表格`() {
        // 常见的双栏/多栏文字排版中间常有一条竖线分隔——不该被误判成表格，
        // 这正是 SELECTION.md/任务描述里"别把正常多栏排版识别成表格"的边界情况。
        val segments = listOf(LineSegment(300f, 100f, 300f, 700f))
        assertFalse(TableGridDetector.looksLikeTable(segments))
    }

    @Test
    fun `单个矩形边框（2条横线+2条竖线）不足以判定为表格`() {
        // 一个文本框/图片的边框只是"一个方框"，不是"网格"——方框只有 2 条横线
        // 和 2 条竖线，达不到"至少 3 条同方向线"的网格阈值，保守放过，不误判。
        val segments = listOf(
            LineSegment(50f, 100f, 300f, 100f), // 上边
            LineSegment(50f, 400f, 300f, 400f), // 下边
            LineSegment(50f, 100f, 50f, 400f),  // 左边
            LineSegment(300f, 100f, 300f, 400f), // 右边
        )
        assertFalse(TableGridDetector.looksLikeTable(segments))
    }

    @Test
    fun `极短的线段（字形笔画或装饰性小标记）不计入网格判断`() {
        // 故意构造大量短线段（长度远小于表格线的典型长度），即使数量凑够也不该
        // 被误判——过滤掉这些"噪声"是为了不被无关的矢量图形干扰。
        val segments = (0 until 10).map { i ->
            LineSegment(i * 5f, 100f, i * 5f + 2f, 100f) // 每条只有 2pt 长
        }
        assertFalse(TableGridDetector.looksLikeTable(segments))
    }

    @Test
    fun `倾斜线段（非水平非竖直）不计入行列统计`() {
        // 对角线不是表格的组成部分（比如水印斜线），即使数量很多也不该触发。
        val segments = (0 until 10).map { i -> LineSegment(i * 10f, i * 10f, i * 10f + 50f, i * 10f + 50f) }
        assertFalse(TableGridDetector.looksLikeTable(segments))
    }

    @Test
    fun `坐标有细微误差（同一条网格线的两条边缘各差1pt）仍能聚类成同一条线`() {
        // 对应"细长填充矩形画边框"这个真实观察：一条逻辑上的横线，实际由 y=79 和
        // y=80 两条边组成（矩形厚度 1pt），必须聚类成同一条线，不能被错误地当成
        // 两条不同的线，否则会拉高线条数、也会让判断标准失去意义。
        val segments = mutableListOf<LineSegment>()
        val verticalXs = listOf(8f, 9f, 200f, 201f, 442f, 443f, 634f, 635f)
        val rows = listOf(79f to 80f, 115f to 116f, 151f to 152f, 186f to 187f, 220f to 221f)
        for ((y1, y2) in rows) {
            segments.add(LineSegment(8f, y1, 634f, y1))
            segments.add(LineSegment(8f, y2, 634f, y2))
        }
        for (x in verticalXs) {
            segments.add(LineSegment(x, 79f, x, 220f))
        }

        assertTrue(TableGridDetector.looksLikeTable(segments))
    }
}
