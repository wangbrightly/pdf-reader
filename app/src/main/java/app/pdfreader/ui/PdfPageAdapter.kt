package app.pdfreader.ui

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.pdfreader.extract.PdfTextExtractor
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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

    /**
     * 2026-08-21 真机复现过一次严重性能问题：快速连续翻页（用户自己在真机上滑了
     * 十几下，从第 1 页很快滑到第 90 多页附近，中途反复滚回滚去）之后，日志里出现
     * `loadPage` 单次耗时 **131 秒**这种离谱数字——不是某一页内容特别复杂，是原来
     * [onBindViewHolder] 每次绑定都用 `kotlin.concurrent.thread {}` 起一条全新的
     * 系统线程，没有任何数量上限：快速翻页时 RecyclerView 会在极短时间内连续绑定/
     * 解绑几十个不同位置，每次绑定都真的起一条线程去跑 PDFBox 解析，短时间内几十
     * 条线程同时抢 CPU/IO，互相拖累，单个任务反而比"老老实实排队"慢了几十倍——
     * 这是真机操作直接测出来的，不是靠读代码猜的。
     *
     * 改成固定大小的线程池：并发解析页数有上限（[LOAD_POOL_SIZE]），多出来的绑定
     * 请求乖乖排队，不会互相抢资源拖垮彼此。配合 [onBindViewHolder] 里"任务真正
     * 开始跑之前，先检查这个 ViewHolder 是不是还绑定着当初提交任务时的那个
     * position"这道新增的前置检查——快速翻页时排在队列里、等到真正轮到执行时早已
     * 经不是当前可见页的任务，直接跳过，不浪费 CPU 去解析一个用户已经划走的页。
     *
     * **2026-08-25 [LOAD_POOL_SIZE] 先从 3 改成 1，隔天（2026-08-26）又改回 3**：
     * 中间这一圈教训值得记录。用户真机反馈"翻页时好几个圈圈同时转，看着乱"——
     * 当时追到的根因是 `documentLock`（见 [PdfTextExtractor.Session.documentLock]
     * KDoc）从 #36 起就是完全互斥的普通锁，3 个线程唯一还剩的效果是"同时抢同一把
     * 锁，谁先抢到由操作系统调度决定"，完成顺序因此跟提交顺序（≈阅读顺序）对不
     * 上；改成 1 确实让顺序变得可预测了，但用户紧接着指出"排队本身还是太长，
     * 多页等待时还是好几个圈圈在转"——根子是documentLock 把图片解码（真机测出
     * 来的大头，一张几百万像素的 CMYK/YCCK 图要几秒）也锁住了，1 个线程池只是让
     * "排很长的队"变得有序，没有让队变短。真正的解法是 NOTES.md #41：
     * [PdfTextExtractor.Session.loadPage] 把 `JpegDecoder.decode`（纯函数，不碰
     * `PDDocument`）挪到锁外面，`loadPage` 之间在解码这一步重新有了真正的并行——
     * 这次改回 3 就是要真正吃到这份并行收益，不会重新引入"抢锁导致完成顺序不
     * 确定"那个问题，因为锁内的部分（Phase A）现在很快，3 个线程抢的是一段
     * 很短的临界区，抢到快慢的差异远小于抢到之后各自解码要花的时间——真机翻页
     * 顺序应该重新接近提交顺序，即使不是 [loadExecutor] 内部队列那种数学上严格
     * 的 FIFO 保证。
     */
    // 见 loadExecutor KDoc"优先加载当前可见页"一节——只在主线程读写（onAttached/
    // onDetached/onBindViewHolder 都是主线程回调），不需要额外同步。
    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    /**
     * 见 [visibleDistance] KDoc——只在主线程调用（[onBindViewHolder] 提交任务前算
     * 一次优先级快照），不在后台线程碰 RecyclerView/LayoutManager 的内部状态。
     */
    private fun visibleDistance(position: Int): Int {
        val layoutManager = attachedRecyclerView?.layoutManager as? LinearLayoutManager ?: return 0
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return 0
        return when {
            position < first -> first - position
            position > last -> position - last
            else -> 0
        }
    }

    /** 单调递增的提交序号，见 [PrioritizedLoadTask] KDoc——优先级相同时按提交顺序决出胜负，避免 [PriorityBlockingQueue] 对"相等"元素的顺序不作保证导致的乱序。 */
    private val submitSequence = AtomicLong(0)

    /**
     * 见 [visibleDistance] KDoc 完整背景——真机反馈"想先看到当前页，不想等预取的
     * 页面"。原来 `loadExecutor` 是普通的 FIFO 线程池，屏幕外预取的页面如果先
     * 提交，会排在刚滑入屏幕、用户正在等的页面前面。包一层按"离当前可见范围的
     * 远近"排序的任务，配合 [PriorityBlockingQueue]：可见页（距离 0）永远排在
     * 预取页（距离 > 0）前面处理。
     *
     * 优先级是**提交那一刻的快照**，不是每次出队都重新查一遍——如果要"实时"
     * 重新排序（比如任务排队等待期间用户又划走了），需要在后台线程读
     * RecyclerView/LayoutManager 的内部状态，这个项目已经因为并发碰 `PDDocument`
     * 吃过真实数据损坏的亏（见 NOTES #33/#36），不确定 RecyclerView 内部状态在
     * 主线程布局的同时被后台线程读取是否安全，没有查到官方文档明确保证，索性
     * 选风险更小的做法：只在提交时（主线程）算一次快照。代价：如果一个预取页
     * 的任务已经在队列里，用户之后滑到让它变成可见，它不会自动"插队"到已经在
     * 排队的可见页前面，要等它自然被取出执行——真机翻页场景里，滑动到停下这段
     * 时间通常够任务被处理完，这个代价接受。
     */
    private inner class PrioritizedLoadTask(position: Int, private val task: () -> Unit) :
        Runnable, Comparable<PrioritizedLoadTask> {
        private val priority = visibleDistance(position)
        private val submitOrder = submitSequence.getAndIncrement()

        override fun run() = task()

        override fun compareTo(other: PrioritizedLoadTask): Int {
            val byPriority = priority.compareTo(other.priority)
            return if (byPriority != 0) byPriority else submitOrder.compareTo(other.submitOrder)
        }
    }

    private val loadExecutor = ThreadPoolExecutor(
        LOAD_POOL_SIZE,
        LOAD_POOL_SIZE,
        0L,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue(),
    )

    /** 见 [loadExecutor] KDoc——Adapter 被换掉时（比如用户又打开了另一份文档）线程池要跟着关掉，不然会一直占着线程不释放。 */
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
        loadExecutor.shutdownNow()
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
        loadExecutor.execute(
            PrioritizedLoadTask(position) {
                // 见 loadExecutor KDoc："排队等到真正执行时，这个 ViewHolder 可能
                // 早就被 RecyclerView 挪去绑定别的位置了"——这种情况直接跳过，不做
                // 无意义的 PDFBox 解析工作。
                if (holder.bindingAdapterPosition != position) return@PrioritizedLoadTask
                val tStart = System.currentTimeMillis()
                // 见 PdfTextExtractor.Session.loadPage KDoc"onTextReady/onImageReady"
                // 一节：文字通常比图片先算出来（NOTES #41 把文字抽取挪到了图片扫描/
                // 解码前面），`onTextReady` 让文字一算完就先展示，不用等图片也解码
                // 完；`onImageReady` 让每张图片刚解出来就先画上去，两个回调都只做
                // `post{}` 调度、不做耗时的事——虽然不再拖长锁的持有时间（图片解码
                // 本身已经不再持有 documentLock，见 NOTES #41），回调本身还是跑在
                // 这一页自己的加载线程上，做耗时的事会拖慢这一页自己的整体返回。
                var previewShown = false
                val content = runCatching {
                    session.loadPage(
                        pageNo,
                        onTextReady = { textBlocks ->
                            holder.itemView.post {
                                if (holder.bindingAdapterPosition == position) {
                                    renderProgressiveBlocks(holder, textBlocks, isFirst = true)
                                }
                            }
                            previewShown = true
                        },
                    ) { bitmap ->
                        holder.itemView.post {
                            if (holder.bindingAdapterPosition == position) {
                                renderProgressiveBlocks(holder, listOf(DisplayBlock.Image(bitmap)), isFirst = !previewShown)
                            }
                        }
                        previewShown = true
                    }
                }.getOrDefault(PdfTextExtractor.PageContent(emptyList()))
                android.util.Log.d(
                    "PdfReaderDebug",
                    "PdfPageAdapter.loadPage(page=$pageNo)=${System.currentTimeMillis() - tStart}ms",
                )
                cache[pageNo] = content
                holder.itemView.post {
                    // 这一步是权威的最终结果——见 onImageReady KDoc，可能跟渐进预览
                    // 展示过的内容不完全一样（图片拼接、表格裁剪替换掉预览等），
                    // 整页重新渲染一次，不依赖/信任渐进阶段已经画出来的东西。
                    if (holder.bindingAdapterPosition == position) renderPage(holder, content)
                }
            },
        )
    }

    /**
     * 见 [onBindViewHolder] 里 `onTextReady`/`onImageReady` 两个回调——文字算完
     * 调一次（[isFirst]，一次性传全部文字段落），之后每解出一张图片再各调一次
     * （追加一个元素的列表），只在主线程调用（调用方已经 `post{}` 过）。[isFirst]
     * 时先清空占位符（转圈），后续内容依次往下摆，跟 [renderPage] 的段间距逻辑
     * 一致。这里画出来的东西是"边算边预览"，不是最终结果——[renderPage] 最终会
     * 整体替换掉这里画的内容。
     */
    private fun renderProgressiveBlocks(holder: PageViewHolder, blocks: List<DisplayBlock>, isFirst: Boolean) {
        val container = holder.itemView as LinearLayout
        if (isFirst) {
            container.removeAllViews()
            resetContainerFromPlaceholderState(container)
        }
        val spacingDp = blockSpacingDpProvider()
        blocks.forEach { block ->
            val view = when (block) {
                is DisplayBlock.Text -> createParagraphView(block.text, block.isHeading)
                is DisplayBlock.Image -> createImageView(block.bitmap)
            }
            if (container.childCount > 0) {
                val params = view.layoutParams as LinearLayout.LayoutParams
                params.topMargin = dpToPx(view, spacingDp)
                view.layoutParams = params
            }
            container.addView(view)
        }
    }

    /**
     * 2026-08-27 用户反馈"加载过程中会显示多个圆圈打转，改为一页的位置中央
     * 只有一个圆圈，这一个屏幕上只能看到一个圆圈"——根因是旧版占位符高度写死
     * 300px，远小于真实屏幕高度，好几页还没加载完的占位条会同时挤在一屏里，
     * 每条各自一个转圈，看起来像"好几个圆圈同时转"；而且 `ProgressBar` 没设
     * `gravity`，在 `MATCH_PARENT` 宽度的容器里默认贴左边缘，不在视觉中心。
     *
     * 两处都改：① 占位符高度改成贴近 [attachedRecyclerView] 当前的可视高度
     * （拿不到时退回旧的 [PLACEHOLDER_HEIGHT_PX] 兜底，比如尚未 attach 的
     * 极端情况），一页在加载完成前占的空间跟"一屏"基本相当，同一时刻自然只有
     * 一页的占位符落在可视区域内，不会好几条挤在一起；② 占位符容器加
     * `gravity = Gravity.CENTER`，让转圈图标在这块留白正中央，不贴边。
     */
    private fun renderLoadingPlaceholder(holder: PageViewHolder) {
        val container = holder.itemView as LinearLayout
        container.removeAllViews()
        container.gravity = Gravity.CENTER
        val placeholderHeight = attachedRecyclerView?.height?.takeIf { it > 0 } ?: PLACEHOLDER_HEIGHT_PX
        container.addView(
            ProgressBar(container.context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER
                }
            },
        )
        val containerParams = container.layoutParams
        containerParams.height = placeholderHeight
        container.layoutParams = containerParams
    }

    /**
     * 见 [renderLoadingPlaceholder] KDoc——占位符临时把容器 `height` 撑到接近
     * 屏幕高、`gravity` 改成居中，展示真正内容前必须撤销这两处，不然：`height`
     * 锁死在占位符那个大数值，内容不够长的页面下面会留一大截空白；`gravity
     * =CENTER` 会让内容整体在容器里垂直居中，而不是从顶部开始往下排——两个
     * `ViewHolder` 被 RecyclerView 回收复用是常态（占位符渲染过的容器随时可能
     * 被绑定去展示另一页的真正内容），[renderPage]/[renderProgressiveBlocks]
     * 展示真正内容前都要调用这个函数复位，不能假设容器还是 [onCreateViewHolder]
     * 里那个从未被占位符碰过的初始状态。
     */
    private fun resetContainerFromPlaceholderState(container: LinearLayout) {
        // `LinearLayout` 自己的默认值就是 TOP|START（见平台源码构造函数），显式
        // 写出来而不是用 `Gravity.NO_GRAVITY`（值为 0，语义是"不设置"，不等于
        // "顶部靠左"），避免这两者细微差异导致复位不彻底。
        container.gravity = Gravity.TOP or Gravity.START
        val containerParams = container.layoutParams
        containerParams.height = RecyclerView.LayoutParams.WRAP_CONTENT
        container.layoutParams = containerParams
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
        resetContainerFromPlaceholderState(container)
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

        /**
         * 见 [loadExecutor] KDoc"2026-08-25 先改 1 隔天又改回 3"一节完整教训——
         * 3 这个数字本身没有精调过（跟真机 131 秒那次教训一样，"要给上限"比
         * "上限具体是几"更重要），2026-08-26 起图片解码已经挪到 documentLock
         * 外面，3 个线程重新有了真实的并发解码收益，不再只是"抢锁排队"。
         */
        const val LOAD_POOL_SIZE = 3
    }
}
