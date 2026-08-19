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
 * 疑似表格在页面里的包围盒，PDF 原生坐标系（y 轴向上，跟 [LineSegment] 同一套单位），
 * 不含任何内边距——调用方（[PdfTextExtractor]）需要裁剪渲染/换算展示位置时自己按需
 * 加内边距，这一层只负责"网格线本身围出来的范围"这个几何事实。
 */
data class TableRegion(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

/**
 * "这一页看起来像不像表格"的启发式判断：数一数页面里有多少条相互独立的横线和竖线，
 * 横竖都够多就判定为"疑似表格"，同时算出表格在页面里的大致包围盒。详见 [PdfTextExtractor]
 * 类注释"表格检测"一节的完整设计理由，这里只记录这层纯逻辑本身的判断规则。
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
 * 文字抽取+reflow 路径——文字本身仍然可读，只是表格的行列对齐效果会被 reflow 打散，
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
 * 加了空间重叠检查：横线们的整体 X 范围和竖线们的整体 X 范围要有重叠、横线们的
 * 整体 Y 范围和竖线们的整体 Y 范围也要有重叠，两个条件都满足才继续判定为表格——
 * 这对应"一批横线和一批竖线要落在页面上大致同一块地方，才可能是真的在交织成
 * 网格"这个几何常识。真表格的横线跨越表格宽度、竖线跨越表格高度，天然会互相
 * 重叠，这条检查不会误伤真表格，只会挡掉"两批线段凑巧都够数、但压根不在同一块
 * 地方"这种巧合。
 *
 * ## 表格区域裁剪（2026-08-19 增量）：只把表格本身降级成图片，不连累同页正文
 *
 * 用户反馈"一页上既有表格又有正文时，能不能像 EPUB 阅读器那样文字是文字、表格是
 * 表格"——早期版本只回答"这一页像不像表格"这一个布尔值，[PdfTextExtractor] 据此
 * 把整页都渲染成图片，哪怕表格只占页面一小块、其余大段正文也跟着丢失重排能力。
 *
 * [tableRegionOrNull] 在原有判断基础上多返回一个包围盒——横线的整体范围和竖线的
 * 整体范围各自的并集，就是表格在页面上的大致占地范围（真表格的横线跨越表格宽度、
 * 竖线跨越表格高度，这个并集天然就是表格的包围盒，不需要额外的聚类算法）。
 * [looksLikeTable] 改成基于 [tableRegionOrNull] 派生（`!= null`），两者共享同一套
 * 判断逻辑，不是两份独立维护的代码，原有的 [looksLikeTable] 测试用例不用改。
 *
 * **已知局限（有意的降级范围，不是遗漏）**：一页有多个互相分离的表格时，这里只
 * 算出一个包围盒（涵盖所有疑似表格线段），会把两个表格之间的正文也一并划进"表格
 * 区域"——多表格分离聚类是明显更复杂的算法，真实文档一页出现多个分离表格本身
 * 也比较少见，这次不做，跟本类一贯的"保守但简单"原则一致。
 *
 * ## 行列间距均匀性检查（2026-08-19 真机连续两次实测发现的新误判修复）
 *
 * 表格区域裁剪上线后真机连续测出两份文档的新误判，都不是"空间不重叠"这种情况
 * （横竖线确实落在同一块区域），而是页面自带的装饰元素（页边距装饰边框、章节
 * 分隔线、列表项背景徽章……网页转 PDF 场景里常见）凑巧同时满足"横竖各至少 3 条、
 * 且互相重叠"这两个条件——比如一份文档里，页面顶部/底部各有一条装饰性边框线
 * （贡献 2 条相距极远的横线），中间又零散地混进几条不相关的分隔线，横线数够了，
 * 竖线同理，包围盒因此摊开到几乎整个页面，把标题和好几段正文都裁进了"表格"图片。
 *
 * 关键区别：**真表格的行列间距大致均匀（行高/列宽即使不完全相等，也不会相差
 * 悬殊），装饰元素+零散分隔线的间距通常很不均匀**——一两条挨得很远的边框线之间
 * 夹着一大段空白，中间又有几条挤在一起的无关线段。[hasUniformSpacing] 把横线
 * （竖线同理）按聚类中心排序算出相邻间距，要求"最大间距不超过间距中位数的
 * [MAX_GAP_TO_MEDIAN_RATIO] 倍"——真表格哪怕表头行比数据行高出不少，这个比例
 * 通常也在 2 倍以内；装饰边框+零散线段这种"一头一尾+中间几条挤在一起"的分布，
 * 最大间距往往是中位数的好几倍，能被这条规则挡住。
 *
 * 用中位数而不是平均数：平均数会被那一两个超大间距本身拉高，中位数更能代表"正常
 * 间距应该是多少"，不会被要检测的那个异常值污染。
 *
 * **代价**：column 宽度极其悬殊的表格（比如一栏是窄的序号列、紧挨着一栏是很宽的
 * 说明文字列）可能被误判成"不够均匀"而漏检，退化成按文字重排——跟本类一贯的
 * "宁可漏检、不可错杀"原则一致，用户明确要求"要误判都误判成文字，结果要一致"，
 * 这次收紧就是照这个方向走的。
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

    /** 见类注释"行列间距均匀性检查"一节。 */
    private const val MAX_GAP_TO_MEDIAN_RATIO = 2f

    fun looksLikeTable(segments: List<LineSegment>): Boolean = tableRegionOrNull(segments) != null

    /**
     * 见类注释"表格区域裁剪"一节。返回 `null` 表示不像表格（判断标准跟 [looksLikeTable]
     * 完全一致），否则返回表格的包围盒。
     */
    fun tableRegionOrNull(segments: List<LineSegment>): TableRegion? {
        val horizontals = segments.filter { it.isHorizontal() }
        val verticals = segments.filter { it.isVertical() }
        val horizontalYs = horizontals.map { (it.y1 + it.y2) / 2f }
        val verticalXs = verticals.map { (it.x1 + it.x2) / 2f }
        if (clusterCount(horizontalYs) < MIN_GRID_LINES) return null
        if (clusterCount(verticalXs) < MIN_GRID_LINES) return null
        if (!hasUniformSpacing(horizontalYs) || !hasUniformSpacing(verticalXs)) return null

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
        if (!xOverlaps || !yOverlaps) return null

        return TableRegion(
            minX = minOf(hMinX, vMinX),
            minY = minOf(hMinY, vMinY),
            maxX = maxOf(hMaxX, vMaxX),
            maxY = maxOf(hMaxY, vMaxY),
        )
    }

    private fun LineSegment.length(): Float = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()

    private fun LineSegment.isHorizontal(): Boolean =
        abs(y2 - y1) <= AXIS_TOLERANCE_PT && length() >= MIN_LINE_LENGTH_PT

    private fun LineSegment.isVertical(): Boolean =
        abs(x2 - x1) <= AXIS_TOLERANCE_PT && length() >= MIN_LINE_LENGTH_PT

    /** 单链聚类（single-linkage）：排序后相邻值差距超过容差才算新的一簇。 */
    private fun clusterCount(values: List<Float>): Int = clusters(values).size

    /**
     * 单链聚类，返回每一簇的代表值（簇内取平均）——[clusterCount] 只要簇的数量，
     * [hasUniformSpacing] 还需要簇的具体位置来算间距，两者共用这个实现，不重复
     * 写一遍聚类逻辑。
     */
    private fun clusters(values: List<Float>): List<Float> {
        if (values.isEmpty()) return emptyList()
        val sorted = values.sorted()
        val groups = mutableListOf(mutableListOf(sorted[0]))
        for (i in 1 until sorted.size) {
            if (sorted[i] - sorted[i - 1] > CLUSTER_TOLERANCE_PT) {
                groups.add(mutableListOf(sorted[i]))
            } else {
                groups.last().add(sorted[i])
            }
        }
        return groups.map { it.average().toFloat() }
    }

    /** 见类注释"行列间距均匀性检查"一节。 */
    private fun hasUniformSpacing(values: List<Float>): Boolean {
        val centers = clusters(values)
        if (centers.size < 2) return false
        val gaps = (1 until centers.size).map { centers[it] - centers[it - 1] }
        val sortedGaps = gaps.sorted()
        val median = if (sortedGaps.size % 2 == 0) {
            (sortedGaps[sortedGaps.size / 2 - 1] + sortedGaps[sortedGaps.size / 2]) / 2f
        } else {
            sortedGaps[sortedGaps.size / 2]
        }
        if (median <= 0f) return false
        return gaps.max() <= median * MAX_GAP_TO_MEDIAN_RATIO
    }
}
