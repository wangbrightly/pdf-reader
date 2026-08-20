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
 * 步）。2026-08-20 已接入 [app.pdfreader.MainActivity]（替换掉旧版的
 * `contentScrollView`/`contentContainer`），下面类注释里"目前还没有接入"这句是写
 * 骨架阶段留下的，已经过时，别再当真。
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
 * 淘汰最旧的一条——只丢弃引用，不主动 `recycle()` 里面的 `Bitmap`（真机崩溃过一次，
 * 见 [cache] 字段注释完整背景）——RecyclerView 自己的 `ViewHolder` 回收机制已经
 * 处理"屏幕外 View 不常驻"这一层，这个缓存额外处理"数据层
 * （[PdfTextExtractor.PageContent]）不要无限增长"，两层缓存分别负责 View 和数据，
 * 缺一都不够（只回收 View、数据层仍然无限增长的话，Adapter 自己持有的引用会让
 * GC 回收不掉那些 Bitmap）。
 *
 * [createParagraphView]/[createImageView] 由调用方注入，不是这个类自己创建——这两
 * 个工厂函数依赖当前的字号/边距设置、双指缩放手势这些 `Activity` 层面的可变状态，
 * 不适合让 `Adapter` 自己持有一份可能过期的设置快照。[blockSpacingDpProvider] 同理，
 * 段距现在是设置面板里可拖动的值（见 `ReaderSettings.blockSpacingDp`），每次绑定
 * 都要读最新值，不能在构造时存成快照。[createParagraphView] 的第二个参数
 * （2026-08-20 新增）是 [DisplayBlock.Text.isHeading]，是否加粗由调用方决定。
 */
class PdfPageAdapter(
    private val session: PdfTextExtractor.Session,
    private val createParagraphView: (String, Boolean) -> View,
    private val createImageView: (Bitmap) -> View,
    private val blockSpacingDpProvider: () -> Int,
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    /**
     * 见类注释"有界缓存"一节。`accessOrder=true` 让 `LinkedHashMap` 按访问顺序（不是插入顺序）排列，配合 `removeEldestEntry` 实现 LRU。
     *
     * 2026-08-20：真机崩溃复现过一次 native `SIGSEGV`（`Bitmap_recycle` 里空指针，
     * 崩溃线程在 `removeEldestEntry` 里），根因是这里原本淘汰时主动调
     * `Bitmap.recycle()`——但被淘汰的只是"我们这份缓存的引用"，RecyclerView 自己
     * 的 `ViewHolder` 回收池当时可能还持有同一个 Bitmap 在给屏幕画，我们这边抢先
     * 把原生内存销毁掉，hwui 渲染线程再去画就是"使用已释放内存"，直接段错误崩溃
     * 整个进程（真机上表现为：点目录跳转后应用崩溃、白屏，NOTES.md 待补记录）。
     * 改成只丢弃缓存引用、不主动 `recycle()`——Bitmap 的原生内存从很早的 Android
     * 版本开始就计入 Java 堆，会跟着对象一起被 GC 自动回收，不是没人管，只是没那
     * 么"立刻"；用这点内存释放的及时性换掉这个必崩的风险，这笔交换很划算。
     */
    private val cache = object : LinkedHashMap<Int, PdfTextExtractor.PageContent>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfTextExtractor.PageContent>): Boolean {
            return size > CACHE_WINDOW_SIZE
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
     * dp → px，跟 `MainActivity.dpToPx` 是同一个换算公式——这里不方便复用那份私有
     * 方法，`Adapter` 本来就该是自足的，直接用拿到的 `context.resources` 自己算。
     */
    private fun dpToPx(view: View, dp: Int): Int =
        (dp * view.resources.displayMetrics.density).toInt()

    /**
     * 有缓存直接渲染；没有就先摆占位符，后台线程调 [PdfTextExtractor.Session
     * .loadPage]，加载完检查 [RecyclerView.ViewHolder.getBindingAdapterPosition]
     * 是不是还等于绑定时的 [position]（不等说明这个 `ViewHolder` 在加载过程中已经
     * 被 RecyclerView 回收挪去绑定别的页了，不该把加载结果画上去，跟
     * `MainActivity` 里 `loadGeneration` 判断"结果是否过期"是同一个防御精神）。
     */
    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        // 页与页之间也要留同样的间距（跟页内段落间距一致），不然连续两页的内容会
        // 贴在一起分不清页界，第 1 页顶部不用加（RecyclerView/recyclerView 自己的
        // padding 已经留了顶部空白）。
        val container = holder.itemView as LinearLayout
        val containerParams = container.layoutParams as RecyclerView.LayoutParams
        containerParams.topMargin = if (position > 0) dpToPx(container, blockSpacingDpProvider()) else 0
        container.layoutParams = containerParams

        val pageNo = position + 1
        val cached = cache[pageNo]
        if (cached != null) {
            renderPage(holder, cached)
            return
        }
        renderLoadingPlaceholder(holder)
        thread {
            val tStart = System.currentTimeMillis()
            val content = runCatching { session.loadPage(pageNo) }
                .getOrDefault(PdfTextExtractor.PageContent(emptyList()))
            android.util.Log.d(
                "PdfReaderDebug",
                "PdfPageAdapter.loadPage(page=$pageNo)=${System.currentTimeMillis() - tStart}ms",
            )
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

    /**
     * 2026-08-20 真机反馈"段与段之间不明显了、图片与图片之间也没了界限"——
     * RecyclerView 化那次改动漏带了旧版 `MainActivity.BLOCK_SPACING_DP`（12dp）这份
     * 视觉留白，这里补回来：页内每个展示块（除第一个）顶部留 [blockSpacingDpProvider]
     * 读出的当前值，页与页之间的留白在 [onBindViewHolder] 里对 `container` 自己的
     * `topMargin` 处理（跟这里读的是同一个值，两处保持一致，不然页内/页间空白宽度
     * 会不一样看着别扭）。同日再改：这个值从写死常量改成可调（用户要求设置面板里
     * 加一个段距拉杆），见 `ReaderSettings.blockSpacingDp`。
     */
    private fun renderPage(holder: PageViewHolder, content: PdfTextExtractor.PageContent) {
        val container = holder.itemView as LinearLayout
        container.removeAllViews()
        val spacingDp = blockSpacingDpProvider()
        content.blocks.forEachIndexed { index, block ->
            val view = when (block) {
                is DisplayBlock.Text -> createParagraphView(block.text, block.isHeading)
                is DisplayBlock.Image -> createImageView(block.bitmap)
            }
            if (index > 0) {
                val params = view.layoutParams as LinearLayout.LayoutParams
                params.topMargin = dpToPx(view, spacingDp)
                view.layoutParams = params
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
