package app.pdfreader.extract

import org.junit.Assert.assertEquals
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
    fun `页面上方3条不相关横线和下方3条不相关竖线不构成表格`() {
        // 真机反馈发现的真实误判场景：一个 10 页文档里 7 页被误判成表格，追查是
        // 旧逻辑只数"页面里横线够不够多、竖线够不够多"，不管这些线在页面上到底
        // 长在哪——页眉附近有几条装饰分隔线，页脚附近又有几条不相关的竖线（比如
        // 多个独立的强调竖条），凑巧各自够 3 条，就被判成"网格"，但这两批线段
        // 根本不在同一块区域，谈不上"交织成表格"。这条测试的横线全部集中在页面
        // 顶部（y=650-700），竖线全部集中在页面底部（y=50-100），两批线段的
        // Y 范围完全不重叠，不应该被判定为表格。
        val segments = mutableListOf<LineSegment>()
        for (y in listOf(650f, 675f, 700f)) segments.add(LineSegment(50f, y, 550f, y))
        for (x in listOf(100f, 300f, 500f)) segments.add(LineSegment(x, 50f, x, 100f))

        assertFalse(TableGridDetector.looksLikeTable(segments))
    }

    @Test
    fun `左右两栏排版各自的分隔线不构成表格（横线在左栏、竖线在右栏）`() {
        // 跟上一条测试同一个精神，换一个方向：横线集中在页面左半边（x=50-250），
        // 竖线集中在页面右半边（x=400-450），X 范围不重叠，同样不该判定为表格。
        val segments = mutableListOf<LineSegment>()
        for (y in listOf(100f, 200f, 300f)) segments.add(LineSegment(50f, y, 250f, y))
        for (x in listOf(400f, 420f, 450f)) segments.add(LineSegment(x, 500f, x, 700f))

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

    // ---- 行列间距均匀性检查：2026-08-19 真机连续两次实测发现的误判修复，
    // 见类 KDoc"行列间距均匀性检查"一节 ----

    @Test
    fun `页边距装饰边框+零散分隔线（横竖线数够但间距悬殊）不判定为表格`() {
        // 模拟真机实测到的真实模式：3 条线挤在页面顶部附近（装饰边框+一两条零散
        // 分隔线），第 4 条远在页面底部（装饰边框的另一边），横竖线数都够
        // MIN_GRID_LINES，也互相重叠，但间距极不均匀——不该被判定为表格。
        val segments = mutableListOf<LineSegment>()
        for (y in listOf(40f, 45f, 50f, 700f)) segments.add(LineSegment(0f, y, 550f, y))
        for (x in listOf(100f, 105f, 110f, 500f)) segments.add(LineSegment(x, 0f, x, 750f))

        assertFalse(TableGridDetector.looksLikeTable(segments))
        assertEquals(null, TableGridDetector.tableRegionOrNull(segments))
    }

    @Test
    fun `表头行比数据行略高的正常表格（间距不完全相等但比例温和）仍判定为表格`() {
        // 表头行 50pt 高，数据行 30pt 高——间距不均匀但幅度温和（真实表格常见），
        // 不该被"行列间距均匀性检查"误伤。
        val horizontalYs = listOf(0f, 50f, 80f, 110f, 140f) // 相邻间距 50,30,30,30。
        val verticalXs = listOf(0f, 200f, 400f, 600f)
        val segments = mutableListOf<LineSegment>()
        val left = verticalXs.first()
        val right = verticalXs.last()
        for (y in horizontalYs) segments.add(LineSegment(left, y, right, y))
        val top = horizontalYs.first()
        val bottom = horizontalYs.last()
        for (x in verticalXs) segments.add(LineSegment(x, top, x, bottom))

        assertTrue(TableGridDetector.looksLikeTable(segments))
    }

    // ---- tableRegionOrNull：2026-08-19 增量，见类 KDoc"表格区域裁剪"一节 ----

    @Test
    fun `不像表格时 tableRegionOrNull 返回 null`() {
        assertEquals(null, TableGridDetector.tableRegionOrNull(emptyList()))
        val onlyOneLongLine = listOf(LineSegment(50f, 700f, 550f, 700f))
        assertEquals(null, TableGridDetector.tableRegionOrNull(onlyOneLongLine))
    }

    @Test
    fun `3列4行的规整网格线算出的包围盒正好是网格线本身的范围`() {
        val horizontalYs = listOf(79f, 115f, 151f, 186f, 220f)
        val verticalXs = listOf(8f, 200f, 442f, 634f)
        val segments = mutableListOf<LineSegment>()
        val left = verticalXs.first()
        val right = verticalXs.last()
        for (y in horizontalYs) segments.add(LineSegment(left, y, right, y))
        val top = horizontalYs.first()
        val bottom = horizontalYs.last()
        for (x in verticalXs) segments.add(LineSegment(x, top, x, bottom))

        assertEquals(
            TableRegion(minX = 8f, minY = 79f, maxX = 634f, maxY = 220f),
            TableGridDetector.tableRegionOrNull(segments),
        )
    }

    @Test
    fun `looksLikeTable 和 tableRegionOrNull 的判断结果永远一致`() {
        // 两者共享同一套判断逻辑，不应该出现"looksLikeTable 说是表格，
        // tableRegionOrNull 却返回 null"这种不一致。
        val horizontalYs = listOf(79f, 115f, 151f, 186f, 220f)
        val verticalXs = listOf(8f, 200f, 442f, 634f)
        val tableSegments = mutableListOf<LineSegment>()
        for (y in horizontalYs) tableSegments.add(LineSegment(8f, y, 634f, y))
        for (x in verticalXs) tableSegments.add(LineSegment(x, 79f, x, 220f))

        val notTableSegments = listOf(LineSegment(50f, 700f, 550f, 700f))

        assertEquals(
            TableGridDetector.looksLikeTable(tableSegments),
            TableGridDetector.tableRegionOrNull(tableSegments) != null,
        )
        assertEquals(
            TableGridDetector.looksLikeTable(notTableSegments),
            TableGridDetector.tableRegionOrNull(notTableSegments) != null,
        )
    }
}
