package app.pdfreader.progress

import android.content.Context

/**
 * 阅读进度的持久化：文件标识（[ReadingProgressKey]） -> 页码（1-based）。
 *
 * ## 2026-08-20 从"存滚动比例"改成"存页码"
 *
 * 最初这里存的是滚动比例（`scrollY / (内容总高度 - 视口高度)`）——那时候整份文档
 * 一次性全部渲染进同一个 `ScrollView`，"总高度"随时可测，比例天然抗字号/边距变化
 * 触发的重排。"文字/图片按需加载"（按页加载，见
 * [app.pdfreader.extract.PdfTextExtractor.Session]、`RecyclerView` 化的
 * `MainActivity`）上线后这个前提不再成立：内容不再一次性全部渲染，"总高度"这个
 * 概念本身就没有了（只有翻到过的页才有实际测量出来的高度，没翻到的页高度未知）。
 *
 * 改成直接记"当前读到第几页"（`LinearLayoutManager.findFirstVisibleItemPosition()
 * + 1`）——`RecyclerView` 天然支持"滚动到第 N 个条目"（`scrollToPosition`），不需要
 * 知道总高度，页码本身也是比"一个 0-1 的浮点比例"更符合直觉的进度单位。
 *
 * 代价：不是"页内精确到像素"的还原（比如上次读到第 500 页中间，这次会跳到第 500
 * 页顶部，不是中间那个具体位置），但对"回到大致读到的位置"这个需求来说已经够用，
 * 比额外再存一个"页内滚动偏移量"要简单得多，是这次改动刻意接受的取舍。
 *
 * ## 存储结构 & 已知局限
 * SharedPreferences 是扁平 KV，一个 key（[ReadingProgressKey] 算出的 hash）对应
 * 一份文件的进度。没有做旧记录清理——读过的文件越多，存的条目越多，不会自动过期。
 * 这是已知局限：数据量是"每份文件一个 Int，若干字节"的级别，现实使用中不会构成
 * 问题；真要收紧可以在后续增量按"最近使用"做 LRU 淘汰，这次不做。
 *
 * key 前缀从旧版的 `scroll_ratio_` 换成 `page_`——存储类型从 Float 变成 Int，
 * `SharedPreferences.getInt` 读到一个用 `putFloat` 存的旧值会直接抛
 * `ClassCastException`，换新前缀让旧数据自然变成不再使用的孤儿条目（无害，符合
 * 上面"没有清理机制"这个已知局限本来就接受的代价），不会导致升级后第一次读取崩溃。
 */
object ReadingProgressStore {
    private const val PREFS_NAME = "reading_progress"
    private const val KEY_PREFIX = "page_"

    fun savePage(context: Context, fileKey: String, page: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PREFIX + fileKey, page.coerceAtLeast(1))
            .apply()
    }

    /**
     * 没保存过这个 key 时返回 null，而不是某个默认页码——调用方要能区分"从未打开过
     * 这份文件"和"上次就是读到第 1 页"这两种情况。
     */
    fun loadPage(context: Context, fileKey: String): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedKey = KEY_PREFIX + fileKey
        if (!prefs.contains(storedKey)) return null
        return prefs.getInt(storedKey, 1)
    }
}
