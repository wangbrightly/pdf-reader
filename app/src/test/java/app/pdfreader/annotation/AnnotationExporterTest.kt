package app.pdfreader.annotation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AnnotationExporter] 的单元测试——把一份文档的批注导成 Markdown。
 *
 * 导出的用途是"把读书时标的东西搬到别处接着用"（笔记软件、邮件、聊天窗口），
 * 所以格式要求是**人能直接读**，不是给程序再解析回去的（那份结构化数据本来就
 * 在 `filesDir/annotations/` 下的 JSON 文件 里，要机器读直接拿那个文件）。
 */
class AnnotationExporterTest {

    private fun annotation(
        kind: AnnotationKind,
        page: Int,
        quotedText: String,
        note: String? = null,
        paragraphIndex: Int = 0,
        startOffset: Int = 0,
    ) = TextAnnotation(
        id = "id-$page-$startOffset",
        kind = kind,
        anchor = TextAnchor(page, paragraphIndex, startOffset, startOffset + quotedText.length, quotedText),
        createdAt = 0L,
        note = note,
    )

    @Test
    fun `按页分组，同页内按出现顺序排`() {
        val markdown = AnnotationExporter.toMarkdown(
            documentName = "纸张的重量.pdf",
            annotations = listOf(
                annotation(AnnotationKind.HIGHLIGHT, page = 47, quotedText = "后面标的", startOffset = 20),
                annotation(AnnotationKind.HIGHLIGHT, page = 12, quotedText = "第 12 页的话"),
                annotation(AnnotationKind.HIGHLIGHT, page = 47, quotedText = "前面标的", startOffset = 5),
            ),
        )

        assertEquals(
            """
            # 纸张的重量.pdf

            ## 第 12 页

            - 【高亮】第 12 页的话

            ## 第 47 页

            - 【高亮】前面标的
            - 【高亮】后面标的
            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun `笔记的正文单独一行引用，跟原话分开`() {
        val markdown = AnnotationExporter.toMarkdown(
            documentName = "书.pdf",
            annotations = listOf(
                annotation(
                    AnnotationKind.NOTE,
                    page = 47,
                    quotedText = "读数更准",
                    note = "跟第 112 页对照着看",
                ),
            ),
        )

        // 原话和自己写的话必须一眼分得开——搬到笔记软件里之后，分不清哪句是书里的、
        // 哪句是自己想的，这份导出就没用了。
        assertEquals(
            """
            # 书.pdf

            ## 第 47 页

            - 【笔记】读数更准
              > 跟第 112 页对照着看
            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun `空笔记不留下空的引用行`() {
        val markdown = AnnotationExporter.toMarkdown(
            documentName = "书.pdf",
            annotations = listOf(annotation(AnnotationKind.NOTE, page = 1, quotedText = "某句", note = "")),
        )

        assertEquals(
            """
            # 书.pdf

            ## 第 1 页

            - 【笔记】某句
            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun `书签只有页码，没有原话`() {
        val markdown = AnnotationExporter.toMarkdown(
            documentName = "书.pdf",
            annotations = listOf(annotation(AnnotationKind.BOOKMARK, page = 88, quotedText = "")),
        )

        assertEquals(
            """
            # 书.pdf

            ## 第 88 页

            - 【书签】
            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun `一条批注都没有时给一句说明，不是空文件`() {
        val markdown = AnnotationExporter.toMarkdown(documentName = "书.pdf", annotations = emptyList())

        assertEquals(
            """
            # 书.pdf

            （还没有批注）
            """.trimIndent(),
            markdown,
        )
    }

    @Test
    fun `原话里的换行压成空格，不破坏列表结构`() {
        // 跨行选中时 quotedText 会带换行，直接塞进 Markdown 列表项会把一条拆成两条。
        val markdown = AnnotationExporter.toMarkdown(
            documentName = "书.pdf",
            annotations = listOf(annotation(AnnotationKind.HIGHLIGHT, page = 1, quotedText = "上半句\n下半句")),
        )

        assertEquals(
            """
            # 书.pdf

            ## 第 1 页

            - 【高亮】上半句 下半句
            """.trimIndent(),
            markdown,
        )
    }
}
