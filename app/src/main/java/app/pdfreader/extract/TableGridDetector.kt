package app.pdfreader.extract

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 一条矢量线段的两个端点，坐标单位是 PDF 页面坐标系里的 pt（与 [PdfTextExtractor]
 * 里 `TextPosition.yDirAdj` 同一套坐标系，具体是"设备空间"还是"用户空间"不重要——
 * 检测逻辑只关心同一页内线段之间的相对位置，不跨页比较、也不换算成像素）。
 *
 * 不携带"这条线是怎么画出来的"（stroke 直线 / 填充矩形的一条边）——这个区分交给
 * [PdfTextExtractor] 里的 PDFBox 适配层（`TableGridStreamEngine`），这一层只做纯粹的
 * 几何判断，方便脱离 PDFBox 单独做 TDD。
 */
data class LineSegment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/**
 * "这一页看起来像不像表格"的启发式判断：数一数页面里有多少条相互独立的横线和竖线，
 * 横竖都够多就判定为"疑似表格"。详见 [PdfTextExtractor] 类注释"表格检测"一节的
 * 完整设计理由，这里只记录这层纯逻辑本身的判断规则。
 *
 * ## 阈值选择：宁可漏检，不可错杀
 *
 * 用真实 fixture（`sample-with-table.pdf`，见 [PdfTextExtractorTableTest]）反编译
 * content stream 实测确认：Chromium 打印 `<table border>` 时，每条边框画成一个
 * "细长的填充矩形"而不是描边直线（例如 `8 79 193 1 re` 是一条横线，`8 79 1 36 re`
 * 是一条竖线）——矩形的长边（不管是 stroke 还是 fill）都会被 PDFBox 适配层转换成
 * [LineSegment] 交给这里判断，短边（矩形的"厚度"，通常只有 1pt）会被
 * [MIN_LINE_LENGTH_PT] 过滤掉，不会被误认成另一条线。
 *
 * 判断标准是"至少 [MIN_GRID_LINES] 条互相独立的横线，且至少 [MIN_GRID_LINES] 条
 * 互相独立的竖线"——選 3 而不是 2，是为了把"一个矩形边框/文本框"（只有 2 条横线
 * +2 条竖线，是一个"框"而不是"网格"）排除在外：单独一个方框在页面上很常见（图片
 * 边框、强调用的文本框），如果 2 条就算表格会有很高的误判率；至少 3 条横线+3 条
 * 竖线意味着至少存在"表格内部的一条分隔线"，才是"网格"区别于"边框"的关键特征。
 * 这是"保守策略"的核心体现，见任务要求"错过一些表格没关系，但不能把正常内容
 * 误判成图片丢失重排能力"。
 *
 * 代价：只有外边框、内部完全没有分隔线的表格（较少见的排版）会被漏检，继续走
 * 文字抽取+重排路径——文字本身仍然可读，只是表格的行列对齐效果会被 reflow 打散，
 * 这与"没检测到表格"时的表现一致，不是新增的坏结果。
 *
 * ## 空间重叠检查（2026-08-18 真机实测发现的误判修复）
 *
 * 早期版本只数"页面里横线够不够多、竖线够不够多"，不管这些线实际长在页面哪个
 * 区域——真机反馈一个 10 页文档 7 页被误判成表格：页面上方有几条跟表格无关的
 * 装饰分隔线（比如章节标题下的横线），下方又有几条不相关的竖线（比如多个独立
 * 的强调竖条），两批线段各自凑够 [MIN_GRID_LINES] 条，就被判成"网格"，但它们
 * 根本不在同一块区域，谈不上交织成表格。
 *
 * 加了 [regionsOverlap]：横线们的整体 X 范围和竖线们的整体 X 范围要有重叠、
 * 横线们的整体 Y 范围和竖线们的整体 Y 范围也要有重叠，两个条件都满足才继续判定
 * 为表格——这对应"一批横线和一批竖线要落在页面上大致同一块地方，才可能是真的
 * 在交织成网格"这个几何常识。真表格的横线跨越表格宽度、竖线跨越表格高度，
 * 天然会互相重叠，这条检查不会误伤真表格，只会挡掉"两批线段凑巧都够数、但压根
 * 不在同一块地方"这种巧合。
 */
object TableGridDetector {
    /** 判定"这条线段是水平/竖直"的容差：允许因为矩形厚度导致的 1pt 左右偏差。 */
    private const val AXIS_TOLERANCE_PT = 1.5f

    /** 短于这个长度的线段当噪声丢弃（字形笔画、矩形边框的"厚度"边、装饰性小标记）。 */
    private const val MIN_LINE_LENGTH_PT = 15f

    /** 两条线的坐标差在这个范围内，视为同一条逻辑线（见类注释里矩形厚度的例子）。 */
    private const val CLUSTER_TOLERANCE_PT = 3f

    /** 至少要有这么多条互相独立的横线 + 竖线，才判定为"网格"而不是"边框"。 */
    private const val MIN_GRID_LINES = 3

    fun looksLikeTable(segments: List<LineSegment>): Boolean {
        val horizontals = segments.filter { it.isHorizontal() }
        val verticals = segments.filter { it.isVertical() }
        val horizontalYs = horizontals.map { (it.y1 + it.y2) / 2f }
        val verticalXs = verticals.map { (it.x1 + it.x2) / 2f }
        if (clusterCount(horizontalYs) < MIN_GRID_LINES) return false
        if (clusterCount(verticalXs) < MIN_GRID_LINES) return false
        return regionsOverlap(horizontals, verticals)
    }

    /**
     * 横线们的整体范围和竖线们的整体范围要在 X、Y 两个方向上都有重叠，才可能是同
     * 一块表格区域——见类注释"空间重叠检查"一节。
     */
    private fun regionsOverlap(horizontals: List<LineSegment>, verticals: List<LineSegment>): Boolean {
        val hMinX = horizontals.minOf { minOf(it.x1, it.x2) }
        val hMaxX = horizontals.maxOf { maxOf(it.x1, it.x2) }
        val hMinY = horizontals.minOf { minOf(it.y1, it.y2) }
        val hMaxY = horizontals.maxOf { maxOf(it.y1, it.y2) }
        val vMinX = verticals.minOf { minOf(it.x1, it.x2) }
        val vMaxX = verticals.maxOf { maxOf(it.x1, it.x2) }
        val vMinY = verticals.minOf { minOf(it.y1, it.y2) }
        val vMaxY = verticals.maxOf { maxOf(it.y1, it.y2) }

        val xOverlaps = hMinX <= vMaxX && vMinX <= hMaxX
        val yOverlaps = vMinY <= hMaxY && hMinY <= vMaxY
        return xOverlaps && yOverlaps
    }

    private fun LineSegment.length(): Float = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()

    private fun LineSegment.isHorizontal(): Boolean =
        abs(y2 - y1) <= AXIS_TOLERANCE_PT && length() >= MIN_LINE_LENGTH_PT

    private fun LineSegment.isVertical(): Boolean =
        abs(x2 - x1) <= AXIS_TOLERANCE_PT && length() >= MIN_LINE_LENGTH_PT

    /** 单链聚类（single-linkage）：排序后相邻值差距超过容差才算新的一簇。 */
    private fun clusterCount(values: List<Float>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        var clusters = 1
        for (i in 1 until sorted.size) {
            if (sorted[i] - sorted[i - 1] > CLUSTER_TOLERANCE_PT) clusters++
        }
        return clusters
    }
}
