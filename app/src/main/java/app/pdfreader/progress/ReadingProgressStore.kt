package app.pdfreader.progress

import android.content.Context

/**
 * 阅读进度的持久化：文件标识（[ReadingProgressKey]） -> 滚动比例（0.0-1.0）。
 *
 * ## 为什么记比例，不记像素
 * 现在的显示方式是 ScrollView 包一整块 TextView（见 activity_main.xml），没有分页、
 * 没有按行的列表控件。记 scrollY 像素值在"字号/行距/边距变化触发重排"后会失真——
 * 重排后每行能放几个字符、总行数都变了，同样的像素位置对应的可能是完全不同的内容
 * 位置。记"滚动比例"（scrollY / (内容总高度 - 视口高度)）则天然抗重排：不管内容
 * 总高度怎么变，同一个比例总是对应"读到全文大约同样的位置"。
 *
 * 代价：不是像素级精确还原（重排前"读到第 500 行"，重排后可能落在第 495 或 505 行），
 * 但对"回到大致读到的位置"这个需求来说已经够用，比强行按内容位置对齐像素要简单得多，
 * 也不容易出 bug——这是本增量刻意接受的设计取舍。
 *
 * ## 存储结构 & 已知局限
 * SharedPreferences 是扁平 KV，一个 key（[ReadingProgressKey] 算出的 hash）对应
 * 一份文件的进度。没有做旧记录清理——读过的文件越多，存的条目越多，不会自动过期。
 * 这是已知局限：数据量是"每份文件一个 Float，若干字节"的级别，现实使用中不会构成
 * 问题；真要收紧可以在后续增量按"最近使用"做 LRU 淘汰，这次不做。
 */
object ReadingProgressStore {
    private const val PREFS_NAME = "reading_progress"
    private const val KEY_PREFIX = "scroll_ratio_"

    fun saveProgress(context: Context, fileKey: String, scrollRatio: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PREFIX + fileKey, scrollRatio.coerceIn(0f, 1f))
            .apply()
    }

    /**
     * 没保存过这个 key 时返回 null，而不是 0f——0f 是"读到过开头"这个有意义的进度值，
     * 不能拿来当"从未打开过"的默认值用，调用方要能区分这两种情况。
     */
    fun loadProgress(context: Context, fileKey: String): Float? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedKey = KEY_PREFIX + fileKey
        if (!prefs.contains(storedKey)) return null
        return prefs.getFloat(storedKey, 0f)
    }
}
