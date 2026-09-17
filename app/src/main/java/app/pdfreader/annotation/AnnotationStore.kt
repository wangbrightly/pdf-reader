package app.pdfreader.annotation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 批注的持久化：一份文档一个 JSON 文件，存在 `filesDir/annotations/<文件标识>.json`。
 *
 * ## 为什么不沿用 SharedPreferences
 * [app.pdfreader.progress.ReadingProgressStore] 用 SharedPreferences 是因为它存的是
 * "一份文档一个 Int"，扁平键值对刚好够用。批注是"一份文档一串带结构的记录"，塞进
 * 扁平 KV 只能自己拼字符串，不如直接落 JSON。附带好处：以后要做导出/备份，
 * 文件本身就是现成的产物。
 *
 * ## 文件标识
 * 用的是跟阅读进度同一个 key（[app.pdfreader.progress.ReadingProgressKey] 算的内容
 * SHA-256），所以同一份书改了文件名、挪了目录，批注照样认得出来。
 *
 * ## 损坏容忍
 * 读取时任何异常都吞掉、当作"这份文档没有批注"（见 [load]）。批注是锦上添花的数据，
 * 文件坏了最多丢批注，绝不能让用户连书都打不开——阅读本身不依赖它。
 *
 * ## 已知局限
 * 跟 [app.pdfreader.progress.ReadingProgressStore] 一样没有清理机制，批注文件随
 * 标注过的文档数线性增长。每份文档一个几 KB 的小文件，现实使用量级不构成问题。
 */
object AnnotationStore {

    private const val DIR_NAME = "annotations"

    /** 暴露出来主要是为了测试能直接构造损坏文件，顺带让调用方能做导出/删除。 */
    fun fileFor(context: Context, fileKey: String): File =
        File(File(context.filesDir, DIR_NAME).apply { mkdirs() }, "$fileKey.json")

    fun load(context: Context, fileKey: String): List<TextAnnotation> {
        val file = fileFor(context, fileKey)
        if (!file.exists()) return emptyList()
        return runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    fun save(context: Context, fileKey: String, annotations: List<TextAnnotation>) {
        fileFor(context, fileKey).writeText(serialize(annotations))
    }

    private fun serialize(annotations: List<TextAnnotation>): String {
        val array = JSONArray()
        annotations.forEach { annotation ->
            array.put(
                JSONObject().apply {
                    put("id", annotation.id)
                    put("kind", annotation.kind.name)
                    put("createdAt", annotation.createdAt)
                    put("page", annotation.anchor.page)
                    put("paragraphIndex", annotation.anchor.paragraphIndex)
                    put("startOffset", annotation.anchor.startOffset)
                    put("endOffset", annotation.anchor.endOffset)
                    put("quotedText", annotation.anchor.quotedText)
                    annotation.note?.let { put("note", it) }
                },
            )
        }
        return array.toString()
    }

    private fun parse(text: String): List<TextAnnotation> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.getJSONObject(index)
            // 认不出的种类整条跳过（比如用旧版本打开新版本存的下划线批注）——
            // 跳过好过崩溃，也好过把它当成高亮显示成别的样子。
            val kind = AnnotationKind.entries.firstOrNull { it.name == item.getString("kind") }
                ?: return@mapNotNull null
            TextAnnotation(
                id = item.getString("id"),
                kind = kind,
                anchor = TextAnchor(
                    page = item.getInt("page"),
                    paragraphIndex = item.getInt("paragraphIndex"),
                    startOffset = item.getInt("startOffset"),
                    endOffset = item.getInt("endOffset"),
                    quotedText = item.getString("quotedText"),
                ),
                createdAt = item.getLong("createdAt"),
                // 老数据没有这个键（见 TextAnnotation.note KDoc），缺了就是 null。
                note = if (item.has("note")) item.getString("note") else null,
            )
        }
    }
}
