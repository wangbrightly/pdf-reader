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
 * ## 行列间距均匀性检查（2026-08-19 加上，同一天真机又测出会漏检真表格，撤回了）
 *
 * 表格区域裁剪上线后真机连续测出两份文档的新误判，都不是"空间不重叠"这种情况
 * （横竖线确实落在同一块区域），而是页面自带的装饰元素（页边距装饰边框、章节
 * 分隔线、列表项背景徽章……网页转 PDF 场景里常见）凑巧同时满足"横竖各至少 3 条、
 * 且互相重叠"这两个条件——比如一份文档里，页面顶部/底部各有一条装饰性边框线
 * （贡献 2 条相距极远的横线），中间又零散地混进几条不相关的分隔线，横线数够了，
 * 竖线同理，包围盒因此摊开到几乎整个页面，把标题和好几段正文都裁进了"表格"图片。
 *
 * 当时的修法：加一条"行列间距要大致均匀"的规则（要求最大间距不超过间距中位数的
 * 2 倍），思路是"真表格的行高/列宽不会相差悬殊，装饰元素+零散分隔线的间距通常
 * 很不均匀"。真机验证过这条规则确实挡住了那两次误判。
 *
 * **同一天晚些时候真机又测出反例，这条规则本身撤回了**：一份技术规格数据表
 * （频响特性表，行业内常见排版）真机测出线段分布是"两条不相关的线挤在一起（比如
 * 标题下的分隔线）+ 后面一段真正均匀的表格行"，整页的间距被这两条不相关的线拉得
 * 不均匀，规则把真表格也一起挡掉了，表格因此被错误地当成普通文字来排版。
 *
 * 尝试过"先按位置分组、只检查最密集那一组的均匀性"这个修法，但验证时发现：这个
 * 修法在数学上没法把"真表格 + 一条不相关的线"和"纯装饰线凑巧挤在一起"这两种情况
 * 分开——本类已有的一条测试用例（"页边距装饰边框+零散分隔线"）构造出的坐标分布，
 * 和真机这次遇到的真表格坐标分布，形状是一样的（一小撮紧挨着的线 + 一条离得很远
 * 的线），单看线段位置的数字分不出哪个是真表格、哪个是装饰线，要分清楚必须引入
 * 新的信息（比如线段长度、离页面边缘的距离），工作量和不确定性都明显更大。
 *
 * 权衡下来，用户选择撤回这条规则，接受"装饰线+零散分隔线可能被重新误判成表格"
 * 这个风险，换回"更多真表格能被正确识别"——两个方向的误判都会发生，选哪个方向
 * 犯错是产品判断，不是纯技术判断，这次的选择记在这里供以后参考，不代表以后遇到
 * 同类问题都该照这个方向选。
 *
 * ## 横竖跨度比例检查（2026-08-26 加上）：真机第三次误判，这次真的引入了"新信息"
 *
 * 真机反馈一份年报"Board of Directors"页——3 位董事的简介卡片，每张卡片有照片
 * 边框+姓名标签（竖向排列，X 跨度只有约 150pt）、职位标题栏+右侧斜线装饰纹理
 * （横向排列，X 跨度约 511pt）。这些线段各自都是"装饰元素"，但坐标巧合分布满足
 * "≥3 条独立横线 + ≥3 条独立竖线，且横竖的包围盒有重叠"——跟上面两次误判
 * （页边距装饰边框、封面设计页）是同一个检测器的第三种触发场景，但这次连
 * "整页图片"这种干净信号都没有（照片不是占满整页，见 [PdfTextExtractor]
 * NOTES.md #39 的 `scanHasFullPageImage` 判断，那次修复对这次不适用）。
 *
 * 上一节明确写过"要分清楚必须引入新的信息（比如线段长度、离页面边缘的距离）"，
 * 这次真的用上了线段长度：**真表格的横线跨度和竖线跨度应该大致吻合**——横线
 * 是从最左边的竖线（第一列）画到最右边的竖线（最后一列），竖线是从最上面的
 * 横线（表头）画到最下面的横线（表尾），两者围的是同一个矩形，理论上 `hMaxX-
 * hMinX`（横线们的整体 X 跨度）应该约等于 `vMaxX-vMinX`（竖线们的整体 X 跨度），
 * Y 方向同理。这次的误判恰恰在这里露了馅：照片边框+姓名标签的竖线 X 跨度只有
 * 约 150pt（都挤在 x=42~192 之间），但职位标题栏+装饰纹理的横线 X 跨度却有
 * 约 511pt（延伸到 x=553）——3.4 倍的落差，因为这些"横线"和"竖线"根本不属于
 * 同一个视觉整体，只是恰好经过同一片区域。
 *
 * 用现有测试套件反过来验证过这条新检查不会伤到任何已有场景：3 列 4 行的真实
 * 网格（[TableGridDetectorTest] 里"3列4行的规整网格线..."那条）横竖跨度完全
 * 相等，比例 1.0；表头行略高的正常表格同样是 1.0；连上一节"用户明确接受的
 * 回归"那条测试（页边距装饰边框+零散分隔线）的横竖跨度比例也只有约 1.1-1.4
 * 倍，仍然在阈值内，这条已经被用户拍板接受的取舍不受这次改动影响——这次的
 * 检查瞄准的是"两批线段视觉上根本不是一回事、只是碰巧同区域"这个新的、
 * 更极端的误判模式，跟"同一批真的分隔线，只是间距不均匀"（上一节撤回的那条
 * 规则想解决但办不到的问题）是不同性质的问题，恰好能用不同的几何信号分开。
 *
 * 阈值选 [MAX_EXTENT_RATIO] = 2 倍——真表格/已接受的装饰线场景比例都在 1.4 倍
 * 以内，这次的真实误判是 3.4 倍，中间留了很宽的空档，不是贴着任何一个样本的
 * 边界值凑出来的。
 *
 * **已知局限（如实记录，不是没想到）**：一个真实表格如果标题栏明显宽于表格本身
 * （比如表格上方有一条通栏的说明文字下划线，宽度远超表格实际列宽），这条新
 * 检查可能会误伤——这跟上一节"行列间距均匀性检查"被"一条不相关的线"破坏是
 * 同一类风险，只是这次触发条件更具体（要求那条不相关的线本身很长、且被误认成
 * 表格的一部分），真机目前还没有实际撞见过这种反例，如果以后出现，需要重新
 * 评估这条检查要不要继续留着。
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

    /** 见类 KDoc"横竖跨度比例检查"一节——横线整体跨度和竖线整体跨度的比值超过这个倍数就不像同一个表格。 */
    private const val MAX_EXTENT_RATIO = 2f

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

        // 见类 KDoc"横竖跨度比例检查"一节——真表格的横线跨度（hMaxX-hMinX）和
        // 竖线跨度（vMaxX-vMinX）围的是同一个矩形，应该大致相等；Y 方向同理。
        // `coerceAtLeast(1f)` 避免除以接近 0 的宽度（理论上 MIN_LINE_LENGTH_PT
        // 已经保证线段本身不会退化成一个点，这里只是防御性写法）。
        val hWidth = hMaxX - hMinX
        val vWidth = vMaxX - vMinX
        val widthRatio = maxOf(hWidth, vWidth) / minOf(hWidth, vWidth).coerceAtLeast(1f)
        if (widthRatio > MAX_EXTENT_RATIO) return null
        val hHeight = hMaxY - hMinY
        val vHeight = vMaxY - vMinY
        val heightRatio = maxOf(hHeight, vHeight) / minOf(hHeight, vHeight).coerceAtLeast(1f)
        if (heightRatio > MAX_EXTENT_RATIO) return null

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

    /** 单链聚类，返回每一簇的代表值（簇内取平均）——[clusterCount] 只需要簇的数量。 */
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
}
