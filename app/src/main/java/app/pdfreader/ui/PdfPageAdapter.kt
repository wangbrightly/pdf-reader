package app.pdfreader.ui

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import app.pdfreader.extract.PdfTextExtractor
import kotlin.concurrent.thread

/**
 * 文字/图片真正按需加载的核心 `RecyclerView.Adapter`——见
 * `/Users/mac/.claude/plans/fizzy-snuggling-cloud.md` 完整背景（按需加载改造第 3
 * 步）。**这个文件目前还没有接入 [app.pdfreader.MainActivity]**，是先写出来、独立
 * 验证过能编译的骨架，接入（替换 `contentScrollView`/`contentContainer`、接目录
 * 跳转/阅读进度）是下一步的工作，别在这里加"为什么 MainActivity 还没用它"之外的
 * 假设。
 *
 * ## 核心设计：条目粒度 = 页，不是段落
 *
 * [getItemCount] 必须在真正加载任何内容之前就能返回——如果条目粒度是"段落"，总
 * 段落数只有抽完全部文字才知道，等于又绕回"要先抽完全部"这个死结（4232 页文档
 * OOM 的根因，见 NOTES.md #21）。条目粒度 = PDF 页：`getItemCount() = session
 * .pageCount`（打开文档时立刻可知），每个条目内部纵向排列这一页的段落/图片
 * （[PdfTextExtractor.PageContent.blocks]，页内顺序已经在 [PdfTextExtractor
 * .Session.loadPage] 里排好，这里只负责按顺序摆 View）。
 *
 * ## 有界缓存：防止"翻过的页仍然全部常驻内存"
 *
 * [cache] 是简单的按访问顺序淘汰的 `LinkedHashMap`，超过 [CACHE_WINDOW_SIZE] 就
 * 淘汰最旧的一条，淘汰时对内容里的 `Bitmap` 主动调 `recycle()`（尽快释放 native
 * 内存，不等 GC）——RecyclerView 自己的 `ViewHolder` 回收机制已经处理"屏幕外 View
 * 不常驻"这一层，这个缓存额外处理"数据层（[PdfTextExtractor.PageContent]）不要
 * 无限增长"，两层缓存分别负责 View 和数据，缺一都不够（只回收 View、数据层仍然
 * 无限增长的话，Adapter 自己持有的引用会让 GC 回收不掉那些 Bitmap）。
 *
 * [createParagraphView]/[createImageView] 由调用方注入，不是这个类自己创建——这两
 * 个工厂函数依赖当前的字号/边距设置、双指缩放手势这些 `Activity` 层面的可变状态，
 * 不适合让 `Adapter` 自己持有一份可能过期的设置快照。
 */
class PdfPageAdapter(
    private val session: PdfTextExtractor.Session,
    private val createParagraphView: (String) -> View,
    private val createImageView: (Bitmap) -> View,
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    /** 见类注释"有界缓存"一节。`accessOrder=true` 让 `LinkedHashMap` 按访问顺序（不是插入顺序）排列，配合 `removeEldestEntry` 实现 LRU。 */
    private val cache = object : LinkedHashMap<Int, PdfTextExtractor.PageContent>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfTextExtractor.PageContent>): Boolean {
            if (size <= CACHE_WINDOW_SIZE) return false
            eldest.value.blocks.filterIsInstance<DisplayBlock.Image>().forEach { it.bitmap.recycle() }
            return true
        }
    }

    override fun getItemCount(): Int = session.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            )
        }
        return PageViewHolder(container)
    }

    /**
     * 有缓存直接渲染；没有就先摆占位符，后台线程调 [PdfTextExtractor.Session
     * .loadPage]，加载完检查 [RecyclerView.ViewHolder.getBindingAdapterPosition]
     * 是不是还等于绑定时的 [position]（不等说明这个 `ViewHolder` 在加载过程中已经
     * 被 RecyclerView 回收挪去绑定别的页了，不该把加载结果画上去，跟
     * `MainActivity` 里 `loadGeneration` 判断"结果是否过期"是同一个防御精神）。
     */
    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val pageNo = position + 1
        val cached = cache[pageNo]
        if (cached != null) {
            renderPage(holder, cached)
            return
        }
        renderLoadingPlaceholder(holder)
        thread {
            val content = runCatching { session.loadPage(pageNo) }
                .getOrDefault(PdfTextExtractor.PageContent(emptyList()))
            cache[pageNo] = content
            holder.itemView.post {
                if (holder.bindingAdapterPosition == position) renderPage(holder, content)
            }
        }
    }

    private fun renderLoadingPlaceholder(holder: PageViewHolder) {
        val container = holder.itemView as LinearLayout
        container.removeAllViews()
        container.addView(
            ProgressBar(container.context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, PLACEHOLDER_HEIGHT_PX)
            },
        )
    }

    private fun renderPage(holder: PageViewHolder, content: PdfTextExtractor.PageContent) {
        val container = holder.itemView as LinearLayout
        container.removeAllViews()
        for (block in content.blocks) {
            val view = when (block) {
                is DisplayBlock.Text -> createParagraphView(block.text)
                is DisplayBlock.Image -> createImageView(block.bitmap)
                // loadPage 只会产出 Text/Image（见 PdfTextExtractor.PageContent KDoc），
                // Placeholder 是旧模型的概念，这里穷尽 when 分支但不会真的走到。
                is DisplayBlock.Placeholder -> continue
            }
            container.addView(view)
        }
    }

    class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private companion object {
        /** 缓存里最多同时保留多少页的 [PdfTextExtractor.PageContent]，见类注释"有界缓存"一节。 */
        const val CACHE_WINDOW_SIZE = 100

        /** 占位符固定高度（像素，不是 dp——Adapter 不方便拿到 Activity 的 density，直接用一个够用的像素值）。 */
        const val PLACEHOLDER_HEIGHT_PX = 300
    }
}
