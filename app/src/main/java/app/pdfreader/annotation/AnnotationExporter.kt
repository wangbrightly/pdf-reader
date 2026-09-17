package app.pdfreader.annotation

/**
 * 把一份文档的批注导成 Markdown 文本。
 *
 * 用途是"把读书时标的东西搬到别处接着用"（笔记软件、邮件、聊天窗口），所以格式
 * 按**人直接读**来设计，不是给程序再解析回去的——要机器读的结构化数据本来就在
 * `filesDir/annotations/` 下的 JSON 文件 里（见 [AnnotationStore]），不需要这份再承担一遍。
 *
 * 纯函数，不碰 Android API，可以完整单测。
 */
object AnnotationExporter {

    fun toMarkdown(documentName: String, annotations: List<TextAnnotation>): String {
        val header = "# $documentName"
        if (annotations.isEmpty()) return "$header\n\n（还没有批注）"

        val body = annotations
            .sortedWith(compareBy({ it.anchor.page }, { it.anchor.paragraphIndex }, { it.anchor.startOffset }))
            .groupBy { it.anchor.page }
            .entries
            .joinToString("\n\n") { (page, onThisPage) ->
                "## 第 $page 页\n\n" + onThisPage.joinToString("\n") { lineFor(it) }
            }
        return "$header\n\n$body"
    }

    private fun lineFor(annotation: TextAnnotation): String {
        val label = when (annotation.kind) {
            AnnotationKind.HIGHLIGHT -> "高亮"
            AnnotationKind.UNDERLINE -> "下划线"
            AnnotationKind.NOTE -> "笔记"
            AnnotationKind.BOOKMARK -> "书签"
        }
        // 跨行选中的原话自带换行，直接塞进列表项会把一条拆成两条，压成空格。
        val quoted = annotation.anchor.quotedText.replace(Regex("\\s*\\n\\s*"), " ")
        val head = if (quoted.isEmpty()) "- 【$label】" else "- 【$label】$quoted"
        val note = annotation.note
        // 原话和用户自己写的话必须一眼分得开——搬进笔记软件后分不清哪句是书里的、
        // 哪句是自己想的，这份导出就白导了，所以笔记正文另起一行用引用块。
        return if (note.isNullOrBlank()) head else "$head\n  > ${note.replace(Regex("\\s*\\n\\s*"), " ")}"
    }
}
