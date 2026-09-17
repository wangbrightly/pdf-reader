package app.pdfreader.annotation

/**
 * 批注的种类。书签（页级、不挂在具体文字上）是后续增量，到时候再加值——存储格式里
 * [TextAnnotation.kind] 落的是枚举名字符串，加新值不影响已存的数据，读到认不出的
 * 种类整条跳过（见 [AnnotationStore]）。
 */
enum class AnnotationKind { HIGHLIGHT, UNDERLINE, NOTE }

/**
 * 批注钉在文字上的位置。
 *
 * 这个 App 把 PDF 文字抽出来**重新排版**（不显示原版页面），所以不能像普通 PDF
 * 阅读器那样用纸面坐标定位——同一段文字在不同字号下位置完全不同，纸面坐标在重排
 * 之后没有意义。改成记"第几页、页内第几段、段内第几个字到第几个字"。
 *
 * [quotedText] 是这套方案的保险。段落怎么切、上下标怎么并入正文，这些抽取逻辑
 * 本身还在演进（见 NOTES #59/#65/#70 几次改动），改完之后老批注的字符偏移就会
 * 错位。存一份原话，解析时先按偏移取文字跟原话比对，对不上就按原话重新搜，
 * 见 [AnchorResolver]。
 */
data class TextAnchor(
    /** 1-based 页码，跟阅读进度用的页码口径一致。 */
    val page: Int,
    val paragraphIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val quotedText: String,
)

/**
 * 一条批注。[id] 用 UUID，不用"第几条"这种序号——删掉中间某条之后序号会整体挪位，
 * 而批注要能被稳定引用（后续增量的批注列表、跳转、删除都靠它）。
 */
data class TextAnnotation(
    val id: String,
    val kind: AnnotationKind,
    val anchor: TextAnchor,
    val createdAt: Long,
    /**
     * 笔记正文，只有 [AnnotationKind.NOTE] 才有，高亮/下划线是 null。
     *
     * 存储格式里是可选字段：增量 2 存下来的老数据没有这个键，读出来就是 null，
     * 不能因为缺字段就读不出整条批注——批注是用户自己攒的数据，往后加字段时
     * 老数据必须照常打得开。
     */
    val note: String? = null,
)
