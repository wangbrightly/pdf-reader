package app.pdfreader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.pdfreader.extract.OutlineEntry
import app.pdfreader.extract.PdfTextExtractor
import app.pdfreader.progress.LastOpenedFileStore
import app.pdfreader.progress.ReadingProgressKey
import app.pdfreader.progress.ReadingProgressStore
import app.pdfreader.settings.ReaderSettings
import app.pdfreader.settings.ReaderSettingsPreferences
import app.pdfreader.ui.IntentUriResolver
import app.pdfreader.ui.PdfLoadReducer
import app.pdfreader.ui.PdfLoadState
import app.pdfreader.ui.PdfPageAdapter
import java.io.File
import java.io.FileNotFoundException
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * 唯一的界面：一个"打开 PDF"按钮 + 字号/行距/边距三个 SeekBar + 进度条 + 可滚动内容区，
 * 串起"选文件 → 打开（[PdfTextExtractor.Session]）→ 按页展示"这条链路，并支持阅读
 * 设置连续调节、即时生效、重启后保持，图片穿插在文字段落之间浮动展示。
 *
 * 两个入口都会走到同一个 [loadPdf]：
 * 1. 点按钮 → [openDocumentLauncher]（Storage Access Framework 文件选择器）。
 * 2. 别的 App"用……打开"分享一个 PDF 过来 → [AndroidManifest.xml] 里的
 *    ACTION_VIEW intent-filter 启动本 Activity，[IntentUriResolver] 从 onCreate 收到的
 *    Intent 里解析出 Uri。
 *
 * 打开在后台线程跑，结果统一通过 [PdfLoadReducer] 转成 [PdfLoadState] 再回主线程
 * 渲染——任何异常（文件不是有效 PDF、密码保护等）都会被 [PdfLoadReducer] 兜成
 * [PdfLoadState.Error]，只弹 Toast，不会让 App 崩溃闪退。
 *
 * ## 2026-08-20：文字/图片真正按需加载（RecyclerView 窗口式重构）
 *
 * 真机反馈一份 4232 页的文档打开会 `OutOfMemoryError`（NOTES.md #21）——根因是
 * 旧架构"一次性抽完全部页面的文字、渲染成全部 TextView 塞进一个普通
 * `LinearLayout`"，遇到几千页量级的文档内存和时间都撑不住。完整方案见
 * `/Users/mac/.claude/plans/fizzy-snuggling-cloud.md`：条目粒度 = PDF 页，
 * `recyclerView`（[R.id.recyclerView]，`LinearLayoutManager` 纵向）配合
 * [PdfPageAdapter]，只有屏幕附近的页会真的调 [PdfTextExtractor.Session.loadPage]
 * 抽取内容、创建 View，翻远的自动回收（[PdfPageAdapter] 有自己的有界缓存）。
 *
 * [loadPdf] 打开 [PdfTextExtractor.Session] 后立刻知道 [PdfTextExtractor.Session
 * .pageCount]（不需要等任何抽取步骤），`render` 据此设置 `recyclerView.adapter`，
 * 具体每一页的内容由 [PdfPageAdapter] 按需加载。[currentSession] 在整个阅读期间
 * 保持打开，换文件/Activity 销毁时才关闭（[onDestroy]）。
 *
 * ## 换行交给 TextView 原生排版，不再自己算（2026-08-18 架构性修正）
 *
 * "重排"曾经是自己写的字符宽度估算算法，几轮真机反馈的显示问题（等宽字体空格视觉
 * 偏宽、单字符测量样本不代表性……）逼着想清楚：这些问题的共同根源是"估算"这件事
 * 本身——TextView 自带的排版引擎（StaticLayout）本来就会用真实字体在真实像素宽度
 * 上精确断行，不需要猜。[PdfTextExtractor.linesToParagraphs] 早就把原始 PDF 里的
 * 断行拼接成了连续文字，"按屏幕宽度换行"这一步完全可以交给 TextView 自己。
 * `reflow`/`LineWidthEstimator` 本身的算法没有错（各自的单元测试还在，继续保留作为
 * "给定固定宽度的纯逻辑断行"这个能力），只是不再是"决定屏幕上文字怎么换行"这件事
 * 的负责人，[createParagraphTextView] 直接把段落原文交给 TextView。
 *
 * ## 字号/边距调节：改 View 属性即可，不需要重新抽取，也不需要"重排/重建"
 *
 * 字号/边距变化不需要重新抽取，也不再需要整体重建（RecyclerView 化之前
 * `reflowCurrentParagraphs` 会重建整个展示块列表，现在不需要了）：
 * [forEachVisibleParagraphTextView] 遍历当前贴在屏幕上的 `ViewHolder`，直接改
 * `setTextSize`/`setLineSpacing` 做即时预览；边距变化直接改 `recyclerView` 自己的
 * padding。两者都会让 Android 自动重新排版（同上一节），不需要我们手动重建。还没
 * 绑定/已经被回收的条目不用管——[PdfPageAdapter.onBindViewHolder] 每次绑定都会用
 * [currentSettings] 当前的值创建新 View（见 [createParagraphTextView]/
 * [createImageView] 读取 [currentSettings] 的方式），下次滚动到时自然是新设置。
 *
 * ## 拖动防抖：拖动中只改外观预览
 *
 * [SeekBar.OnSeekBarChangeListener.onProgressChanged] 在拖动过程中会高频触发。拖动
 * 过程中只做轻量的外观预览：遍历当前可见的 TextView 直接调
 * `setTextSize`/`setLineSpacing`，或者直接改 `recyclerView` 的 padding——这些都是
 * View 系统内部的度量+重绘，本身有节流，不会卡，且 Android 会自动据此重新排版，
 * 拖动过程本身就是实时正确的预览。
 *
 * ## 阅读进度：记页码，存取抽成独立类，onPause 存 / 内容渲染完后恢复
 *
 * "记住每个文件的阅读进度"拆成三件事，纯存取逻辑（[ReadingProgressKey] 算文件标识、
 * [ReadingProgressStore] 存取页码）都不依赖 Activity，可以脱离 UI 单测；这里只是
 * 接线：
 *
 * 1. **存的时机**：[onPause]（不是 `onStop`——`onPause` 保证在 Activity 可能被系统
 *    回收前调用）。另外 [loadPdf] 打开新文件前也会先存一次——"打开另一份 PDF"对
 *    上一份文件来说也是"离开"，不用等到 onPause 才存。
 * 2. **记的是什么**：2026-08-20 从"滚动比例"改成"页码"（`LinearLayoutManager
 *    .findFirstVisibleItemPosition() + 1`）——RecyclerView 化之后"内容总高度"这个
 *    概念不存在了（内容不再一次性全部渲染），页码是天然可用、且比"0-1 浮点比例"
 *    更符合直觉的进度单位，见 [ReadingProgressStore] 类 KDoc。
 * 3. **恢复的时机**：[render] 处理 [PdfLoadState.Success] 时调
 *    `recyclerView.scrollToPosition`——`RecyclerView` 天然支持"滚动到还没绑定过的
 *    位置"（绑定会在滚动到位后自动触发），不需要像旧模型那样"先把中间内容全部
 *    加载一遍"才能跳转。
 *
 * ## 目录（2026-08-18 增量）：读 PDF 自带的大纲结构，没有就禁用按钮，不瞎猜
 *
 * PDF 格式本身有标准的大纲（Outline/书签）结构，抽取逻辑在
 * [app.pdfreader.extract.PdfTextExtractor]（该类 KDoc"大纲/目录抽取"一节有 API 细节）
 * ——这里只是接线：[currentSession] 打开时已经带上了 [PdfTextExtractor.Session.outline]。
 *
 * **没有大纲时禁用 [tocButton]，而不是隐藏或点击后弹提示**：禁用（变灰但位置不变）
 * 比隐藏更好——按钮始终在同一个位置，不会因为换了一份文档就让旁边的按钮跟着跳动
 * 位置；禁用态本身就是标准视觉语言，不需要用户先点一下才知道"没有目录"。
 *
 * **点击目录项怎么定位**：2026-08-20 简化——目录项给的是"第几页"，直接
 * `recyclerView.scrollToPosition(targetPage - 1)`，不再像旧版
 * （`OutlineNavigation`）那样换算"页内精确 Y 坐标对应第几个展示块"。**已知的取舍
 * （如实记录）**：这样跳转的精度从"页内某个具体段落"退化成"页顶"，一页内容多、
 * 目录项指向页面中间靠后的位置时，跳转落点会比旧版更靠上一些——RecyclerView 模型
 * 下要恢复页内精确定位需要在条目绑定完成后再做一次二次滚动微调，这次先用更简单的
 * 页级跳转，微调留给以后有需要再做。
 *
 * **用 `scrollToPosition`，不是 `smoothScrollToPosition`**（2026-08-20 真机踩坑后
 * 改）：`smoothScrollToPosition` 会带动画地"滚过"起点到终点之间的每一页——从第 1
 * 页跳到第 500 页会真的连续滚屏经过中间全部页，不止观感上不是"跳转"更像"翻书"，
 * 滚过的每一页还会触发 [PdfPageAdapter] 的按需加载和缓存淘汰，短时间内密集触发，
 * 真机上复现过一次因此引发的崩溃（见 `PdfPageAdapter.cache` 字段注释）。
 * `scrollToPosition` 是无动画的直接跳转，只绑定目标位置附近真正可见的条目，不会
 * 经过中间页，这才是"点目录立刻跳到那一页"该有的行为。
 *
 * ## 配置变化（转屏等）重建后恢复文档（2026-08-19 增量，code review 发现的缺口）
 *
 * `MainActivity` 没有 `android:configChanges`，配置变化会触发完整的 Activity 销毁
 * 重建——不选择"整份文档内容塞进 `onSaveInstanceState` 的 `Bundle`"这个方案
 * （`Bundle` 走进程内 Binder 传输，有大小限制，图片的 `Bitmap`/[PdfTextExtractor
 * .Session] 内部持有的 `PDDocument` 也都不适合塞进 `Bundle`）。改成只存
 * [currentUri]（`Uri` 本身是 `Parcelable`，几乎不占空间），重建后的 `onCreate`
 * 读到就直接调 [loadPdf] 重新走一遍——效果上等价于"自动帮用户重新打开刚才那份
 * 文件"，滚动位置不需要另外处理（[onPause] 已经存过一次阅读进度，重新 [loadPdf]
 * 会自动读回）。
 *
 * 用 `savedInstanceState != null` 判断"这是重建，不是真正冷启动"，只在重建时才用
 * 恢复出来的 `Uri`；真正冷启动时优先看有没有"用……打开"分享过来的 PDF
 * （[IntentUriResolver]）——两条路径不会冲突，同一次 `onCreate` 最多只会触发一次
 * [loadPdf]。
 *
 * ## 真正冷启动（连任务卡片都没了）也要能恢复文档（2026-08-21 真机诊断增量）
 *
 * 上一节的"配置变化重建"恢复只覆盖"同一个任务被重新调度到前台"这一种情况。真机
 * 反馈"打开的书无声无息消失了"，查 `logcat -b events` 找到
 * `am_kill: ... due to LockScreenClean`——MIUI 锁屏后会主动杀掉后台 App 进程，且
 * 连带清掉 Recents 任务卡片，下次点图标是彻底的冷启动，`savedInstanceState` 必为
 * null，上一节那条路径根本不会触发。这不是 MIUI 独有的边界情况，是"进程随时可能被
 * 系统回收"这个安卓平台级别的常态，只是 MIUI 更激进。
 *
 * 不去对抗系统杀进程（前台 Service 之类的手段在 MIUI 上也不保证有效），而是让
 * "重新打开"本身几乎免费：[LastOpenedFileStore] 记住"最近一次通过系统文件选择器
 * 主动挑的文件"，真正冷启动（没有重建 Uri，也没有分享过来的 PDF）时读出来自动
 * [loadPdf] 一次——[PdfTextExtractor.Session.open] 本来就已经优化到大多数文档
 * 0.1–1.5 秒能打开，效果上跟用户没被打断过一样。完整背景见 [LastOpenedFileStore]
 * 类 KDoc。
 *
 * **只记系统选择器选的文件，不记别的 App 分享过来的**：`content://` Uri 的读权限
 * 默认一次性，只有 `ACTION_OPEN_DOCUMENT`（[openDocumentLauncher]）允许调用
 * `takePersistableUriPermission` 换成跨进程重启依然有效的持久授权；`ACTION_VIEW`
 * 分享过来的 Uri 对它调用会直接抛 `SecurityException`，语义上也不适合冷启动时
 * 不问自取地重新弹出来——[loadPdf] 的 `rememberAsLastOpened` 参数只在系统选择器
 * 这条路径为 `true`。
 *
 * **持久授权数量有上限**：安卓限制每个 App 最多约 512 条持久 Uri 授权，超过会抛
 * `SecurityException`。[openDocumentLauncher] 每次选新文件都会先释放上一次记住的
 * 那条授权，长期最多只占用 1 条，不会随着开过的文件越来越多逼近上限。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var fileNameLabel: TextView
    private lateinit var topButtonRow: View
    private lateinit var openButton: Button
    private lateinit var tocButton: Button
    private lateinit var toggleSettingsButton: Button
    private lateinit var settingsPanel: View
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager

    private lateinit var fontSizeLabel: TextView
    private lateinit var fontSizeSeekBar: SeekBar
    private lateinit var lineSpacingLabel: TextView
    private lateinit var lineSpacingSeekBar: SeekBar
    private lateinit var paddingLabel: TextView
    private lateinit var paddingSeekBar: SeekBar
    private lateinit var blockSpacingLabel: TextView
    private lateinit var blockSpacingSeekBar: SeekBar

    private lateinit var pageScrubberTrack: View
    private lateinit var pageScrubberThumb: View
    private lateinit var pageScrubberLabel: TextView

    /** 见 [setupPageScrubber] KDoc"避免拖拽和自动同步互相打架"一节。 */
    private var isDraggingPageScrubber = false

    /**
     * 见 [setupCenterTapToggleScrubber] KDoc——用户点没点过屏幕中央来切换"沉浸模式
     * 工具栏"（[topButtonRow]/[fileNameLabel]/[pageScrubberThumb]）的显隐，每次
     * [render] 重置成 false（新一轮加载/新文档默认从隐藏开始）。
     *
     * 2026-08-26 用户反馈"文件名、三个菜单也设置成同右侧滑杆一样是隐藏的"——原来
     * 这个字段只管翻页手柄，现在扩大到统一管这三样，语义从"手柄有没有被点开"变成
     * "沉浸模式工具栏有没有被点开"，字段名跟着从 `scrubberRevealedByTap` 改成
     * `chromeRevealedByTap`（`chrome` 是"内容之外的界面框架"这个意思的通用叫法）。
     */
    private var chromeRevealedByTap = false

    /**
     * 用户展没展开过设置面板（跟 [chromeRevealedByTap] 是两个独立的意图状态，
     * 一起决定 [settingsPanel] 最终显不显示，见 [updateChromeVisibility] KDoc
     * "settingsPanel"一节）。默认 `false`，跟 `activity_main.xml` 里
     * `settingsPanel` 的 `android:visibility="gone"` 初始态保持一致。
     */
    private var settingsPanelExpanded = false

    /** 当前生效的阅读设置，onCreate 时从 [ReaderSettingsPreferences] 读，改动即时写回。 */
    private var currentSettings: ReaderSettings = ReaderSettings()

    /**
     * 当前打开的文档——[PdfTextExtractor.Session.loadPage] 按页加载，见类注释
     * "文字/图片真正按需加载"一节。
     */
    private var currentSession: PdfTextExtractor.Session? = null

    /**
     * [currentSession] 对应的临时缓存文件（[copyToCacheFile] 拷贝出来的）。
     * [PdfTextExtractor.Session] 在整个阅读期间都要能继续从这份文件读取页面内容，
     * 不能一读完就删——只有在 [currentSession] 关闭之后（换文件/Activity 销毁）
     * 才能安全删除。
     */
    private var currentCacheFile: File? = null

    /**
     * 每次调用 [loadPdf] 就 +1，后台加载线程用它判断"这份结果是不是还对应当前正在
     * 显示的文档"——用户中途又换了一份文件时，上一份文档还没打开完的后台工作不应该
     * 继续更新画面。
     */
    private var loadGeneration = 0

    /**
     * 当前正在显示/加载的文档来源——[loadPdf] 一开始就设置，不等打开成功。配置变化
     * 重建时靠这个字段恢复文档，见类注释"配置变化重建后恢复文档"一节。
     */
    private var currentUri: Uri? = null

    /** 当前显示这份文件的阅读进度 key（[ReadingProgressKey]），没打开任何文件时为 null。 */
    private var currentFileKey: String? = null

    /**
     * 内容渲染完成后要滚动到的页码（1-based）。[loadPdf] 里从 [ReadingProgressStore]
     * 读出来，见类注释"阅读进度"一节。消费一次（在 [restoreScrollPositionIfNeeded]
     * 里）就清空，避免下一次渲染误用旧值。
     */
    private var pendingScrollPage: Int? = null

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                rememberLastOpenedUriPermission(it)
                loadPdf(it)
            }
        }

    /**
     * 见类注释"真正冷启动也要能恢复文档"一节。只在这条路径（系统文件选择器）调用，
     * `takePersistableUriPermission` 对 `ACTION_VIEW` 分享过来的 Uri 会直接抛
     * `SecurityException`，不适合在那条路径上尝试。
     */
    private fun rememberLastOpenedUriPermission(uri: Uri) {
        val previous = LastOpenedFileStore.load(applicationContext)
        if (previous != null && previous != uri) {
            runCatching {
                contentResolver.releasePersistableUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsetsAsPadding()

        fileNameLabel = findViewById(R.id.fileNameLabel)
        // marquee 跑马灯只在"选中态"才滚动，纯靠 XML 的 ellipsize="marquee" 不会
        // 自己动，见 activity_main.xml fileNameLabel 节点 KDoc。
        fileNameLabel.isSelected = true
        topButtonRow = findViewById(R.id.topButtonRow)
        openButton = findViewById(R.id.openButton)
        tocButton = findViewById(R.id.tocButton)
        toggleSettingsButton = findViewById(R.id.toggleSettingsButton)
        settingsPanel = findViewById(R.id.settingsPanel)
        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.recyclerView)
        layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager
        fontSizeLabel = findViewById(R.id.fontSizeLabel)
        fontSizeSeekBar = findViewById(R.id.fontSizeSeekBar)
        lineSpacingLabel = findViewById(R.id.lineSpacingLabel)
        lineSpacingSeekBar = findViewById(R.id.lineSpacingSeekBar)
        paddingLabel = findViewById(R.id.paddingLabel)
        paddingSeekBar = findViewById(R.id.paddingSeekBar)
        blockSpacingLabel = findViewById(R.id.blockSpacingLabel)
        blockSpacingSeekBar = findViewById(R.id.blockSpacingSeekBar)
        pageScrubberTrack = findViewById(R.id.pageScrubberTrack)
        pageScrubberThumb = findViewById(R.id.pageScrubberThumb)
        pageScrubberLabel = findViewById(R.id.pageScrubberLabel)

        currentSettings = ReaderSettingsPreferences.load(applicationContext)
        applySettingsToView(currentSettings)
        syncSeekBars(currentSettings)
        setupSeekBarListeners()
        setupPageScrubber()
        setupCenterTapToggleScrubber()

        openButton.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/pdf"))
        }
        tocButton.setOnClickListener { showOutlineDialog() }
        toggleSettingsButton.setOnClickListener { toggleSettingsPanel() }
        // 用户反馈"设置菜单默认不展开"——冷启动、还没打开任何 PDF 时也应该是收起状态。
        // 只在 savedInstanceState == null（真正的冷启动，不是配置变化触发的重建）时
        // 收起，配置变化重建时不该打断用户当时正在做的事（比如正展开着调字号）。
        if (savedInstanceState == null) collapseSettingsPanel()

        // 配置变化重建：优先恢复重建前正在看的文档，见类注释"配置变化重建后恢复
        // 文档"一节。恢复不到（真正冷启动）才看有没有"用……打开"分享过来的 PDF——
        // 两条路径互斥，这次 onCreate 最多触发一次 loadPdf。
        val restoredUri = savedInstanceState?.let { BundleCompat.getParcelable(it, KEY_CURRENT_URI, Uri::class.java) }
        if (restoredUri != null) {
            loadPdf(restoredUri)
        } else {
            // 别的 App"用……打开"分享过来的 PDF：启动时就自动开始抽取，不需要用户再点一次按钮。
            // 这条路径的 Uri 权限是临时的，不适合记成"最近打开的文件"，见类注释
            // "真正冷启动也要能恢复文档"一节。
            val sharedUri = IntentUriResolver.resolvePdfUri(intent)
            if (sharedUri != null) {
                loadPdf(sharedUri, rememberAsLastOpened = false)
            } else {
                // 真正冷启动、也没有分享过来的 PDF：看看有没有"最近一次通过系统文件
                // 选择器打开的文件"，有就自动重新打开，见类注释"真正冷启动也要能
                // 恢复文档"一节（MIUI 锁屏清理杀进程导致文档无声消失的修复）。
                LastOpenedFileStore.load(applicationContext)?.let { uri -> loadPdf(uri) }
            }
        }
    }

    /** 见类注释"配置变化重建后恢复文档"一节：只存一个 Uri，重建后靠它重新走一遍 loadPdf。 */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(KEY_CURRENT_URI, currentUri)
    }

    /** 离开当前文件（切后台、被系统回收前）时落盘一次阅读进度，见类注释"阅读进度"一节。 */
    override fun onPause() {
        super.onPause()
        saveCurrentReadingProgress()
    }

    /**
     * [currentSession] 持有一个打开的 `PDDocument`，阅读期间要一直保持可读
     * （[PdfTextExtractor.Session.loadPage] 是按需调用的，不是打开时就读完），
     * Activity 销毁时（不管是真正退出还是配置变化重建）都要主动关掉，不然这个
     * `PDDocument`/临时缓存文件会一直占着不释放。
     */
    override fun onDestroy() {
        super.onDestroy()
        currentSession?.close()
        currentCacheFile?.delete()
    }

    /**
     * 见类注释"文字/图片真正按需加载"一节。打开 [PdfTextExtractor.Session] 只需要
     * 知道 [PdfTextExtractor.Session.pageCount]（几乎不耗时，不需要抽取任何一页的
     * 具体内容），[render] 据此设置 `recyclerView.adapter`，剩下的按页加载由
     * [PdfPageAdapter] 负责。
     */
    private fun loadPdf(uri: Uri, rememberAsLastOpened: Boolean = true) {
        currentUri = uri
        // 用户反馈"三个菜单上面增加文件名"——一拿到 Uri 就立刻设置文本，不等文档
        // 真正解析成功（哪怕最后打开失败，也让用户知道刚才点的是哪个文件）。
        // 只设文本，不在这里直接改 visibility——马上要调的 render(Loading) 末尾会
        // 调 updateChromeVisibility 统一算最终显不显示（要跟"沉浸模式有没有被
        // 点开"配合判断），这里重复调一次没有意义。
        fileNameLabel.text = queryDisplayName(uri)
        // 打开新文件前，先把当前正在显示的文件（如果有）的阅读进度存一次——"打开
        // 另一份 PDF"对上一份文件来说也是"离开"，不用等到 onPause 才存。必须在
        // render(Loading) 清空内容区之前算，不然当前页码已经没法读了。
        saveCurrentReadingProgress()
        render(PdfLoadState.Loading)

        val myGeneration = ++loadGeneration
        val previousSession = currentSession
        val previousCacheFile = currentCacheFile
        currentSession = null
        currentCacheFile = null

        val tStart = System.currentTimeMillis()
        thread {
            // 关掉上一份文档（如果有）——放在后台线程，close()/删除临时文件都可能有
            // I/O，不该占用主线程。
            previousSession?.close()
            previousCacheFile?.delete()

            val openResult = runCatching {
                val file = copyToCacheFile(uri)
                val tAfterCopy = System.currentTimeMillis()
                // onOutlineReady：大纲在后台抽取，抽完之后目录按钮的可用状态可能要
                // 跟着变（原来禁用的按钮这时候才知道其实有目录）——见 Session.outline
                // 字段 KDoc"已知代价"一节。只在还是当前这份文档时才生效
                // （myGeneration 判断跟别处一致），避免用户中途换了文件之后旧文档的
                // 回调还在瞎更新按钮状态。
                val session = PdfTextExtractor.Session.open(applicationContext, file) {
                    if (myGeneration == loadGeneration) runOnUiThread { syncTocButtonEnabled() }
                }
                android.util.Log.d(
                    "PdfReaderDebug",
                    "loadPdf 拷贝文件=${tAfterCopy - tStart}ms " +
                        "Session.open=${System.currentTimeMillis() - tAfterCopy}ms",
                )
                session to file
            }

            if (myGeneration != loadGeneration) {
                // 加载这份文档的过程中用户又换了文件——这份结果已经过时，直接关掉
                // 不用，不渲染。
                openResult.getOrNull()?.let { (session, file) -> session.close(); file.delete() }
                return@thread
            }

            val pageCountResult = openResult.map { (session, file) ->
                currentSession = session
                currentCacheFile = file
                val fileKey = ReadingProgressKey.fromFile(file)
                currentFileKey = fileKey
                val savedPage = ReadingProgressStore.loadPage(applicationContext, fileKey)
                pendingScrollPage = (savedPage ?: 1).coerceIn(1, session.pageCount)
                session.pageCount
            }
            val state = PdfLoadReducer.fromResult(pageCountResult)
            runOnUiThread {
                render(state)
                android.util.Log.d(
                    "PdfReaderDebug",
                    "loadPdf render(Success) 总耗时=${System.currentTimeMillis() - tStart}ms",
                )
                // 只在"打开一份新文件"这条路径自动收起设置面板，给内容腾屏幕。
                if (state is PdfLoadState.Success) collapseSettingsPanel()
                // 见类注释"真正冷启动也要能恢复文档"一节。只在系统选择器这条路径维护
                // "最近打开的文件"这条记录：成功就记住这一份；失败时只有"这次失败的
                // 正是当前记住的那个文件"才清掉记录（说明它已经打不开了，不该继续
                // 每次冷启动都重试一遍、弹一次没人问的错误提示）——避免"用户手动挑
                // 了一份坏文件"误把之前一份好端端的记录冲掉。
                if (rememberAsLastOpened) {
                    if (state is PdfLoadState.Success) {
                        LastOpenedFileStore.save(applicationContext, uri)
                    } else if (state is PdfLoadState.Error && LastOpenedFileStore.load(applicationContext) == uri) {
                        LastOpenedFileStore.clear(applicationContext)
                    }
                }
            }
        }
    }

    /**
     * 让根布局的顶部/底部内边距动态加上状态栏/导航栏高度，避免内容被系统栏盖住。
     *
     * 2026-08-18 真机截图验证过：XML 里写 `android:fitsSystemWindows="true"` 在这台设备
     * 上不生效——现代安卓（尤其 edge-to-edge 强制生效的版本）默认让内容延伸到系统栏后面，
     * `fitsSystemWindows` 这个老属性覆盖不了这种情况，必须主动监听 [WindowInsetsCompat]
     * 读出系统栏高度、手动加进 padding。
     *
     * 2026-08-19 补底部：同样的机制也覆盖底部导航栏（三键导航条比手势条高，会挡住内容
     * 最后一行）——直接照顶部同样的思路把 `insets.bottom` 也加进 `paddingBottom`，不需要
     * 自己判断当前是哪种导航模式。
     */
    private fun applySystemBarInsetsAsPadding() {
        val root = findViewById<View>(R.id.rootLayout)
        val basePaddingLeft = root.paddingLeft
        val basePaddingTop = root.paddingTop
        val basePaddingRight = root.paddingRight
        val basePaddingBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val statusBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navigationBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                basePaddingLeft,
                basePaddingTop + statusBarInset.top,
                basePaddingRight,
                basePaddingBottom + navigationBarInset.bottom,
            )
            windowInsets
        }
    }

    /**
     * 从 Uri 里取"给人看的文件名"——`content://` scheme（系统选择器/别的 App
     * 分享过来的都是这种）本身不含文件名，要用 [ContentResolver] 查
     * [OpenableColumns.DISPLAY_NAME]；查不到（理论上不该发生，防御性兜底）就退回
     * Uri 最后一段路径。
     *
     * **`query()` 本身必须包一层 `runCatching`**——真机崩溃复现过：冷启动自动
     * 恢复"上次打开的文件"时，如果那份文件是从系统相册选的（Uri 来自
     * `MediaDocumentsProvider`），即使 [rememberLastOpenedUriPermission] 早就
     * 持久化过读权限、[copyToCacheFile] 的 `openInputStream` 能正常读到内容，
     * 单独对同一个 Uri 调 `query()` 仍然可能被拒（`SecurityException:
     * requires that you obtain access using ACTION_OPEN_DOCUMENT`）——这是
     * 部分 Provider（尤其 MediaProvider 包了一层 DocumentsProvider）对
     * "持久授权覆盖 openFile 但不覆盖 query" 的已知不一致行为，不是这份代码
     * 哪里没做对。查不到就跟"列名找不到"走同一条兜底路径，不能让整个 App
     * 崩在这一行。
     */
    private fun queryDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            }.getOrNull()?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(nameIndex) ?: uri.lastPathSegment.orEmpty()
                }
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    /** SAF 返回的 content:// Uri 不一定能直接当 File 打开，先拷贝到本 App 的缓存目录。 */
    private fun copyToCacheFile(uri: Uri): File {
        val input = contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException(getString(R.string.pick_file_error))
        val tempFile = File.createTempFile("opened-", ".pdf", cacheDir)
        input.use { inputStream ->
            tempFile.outputStream().use { output -> inputStream.copyTo(output) }
        }
        return tempFile
    }

    /** 把 [settings] 应用到 [recyclerView] 的外观（目前只有 padding；字号/行距在每个段落 TextView 创建时应用）。 */
    private fun applySettingsToView(settings: ReaderSettings) {
        val paddingPx = dpToPx(settings.paddingDp)
        recyclerView.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
    }

    /** 把 [settings] 的数值同步到三个 SeekBar 的 progress 和 Label 文字（不触发重排）。 */
    private fun syncSeekBars(settings: ReaderSettings) {
        fontSizeSeekBar.progress = settings.fontSizeSp
        fontSizeLabel.text = getString(R.string.font_size_label, settings.fontSizeSp)

        lineSpacingSeekBar.progress = multiplierToProgress(settings.lineSpacingMultiplier)
        lineSpacingLabel.text = getString(R.string.line_spacing_label, settings.lineSpacingMultiplier)

        paddingSeekBar.progress = settings.paddingDp
        paddingLabel.text = getString(R.string.padding_label, settings.paddingDp)

        blockSpacingSeekBar.progress = settings.blockSpacingDp
        blockSpacingLabel.text = getString(R.string.block_spacing_label, settings.blockSpacingDp)
    }

    /**
     * 遍历 [recyclerView] 里当前贴在屏幕上的 `ViewHolder`（每个是一个纵向
     * `LinearLayout`，见 [PdfPageAdapter]），对其中的文字段落 TextView（跳过图片
     * ImageView）执行 [action]——见类注释"字号/边距调节"一节，为什么这样就够了
     * （不需要整体重建）。
     */
    private fun forEachVisibleParagraphTextView(action: (TextView) -> Unit) {
        for (i in 0 until recyclerView.childCount) {
            val pageContainer = recyclerView.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until pageContainer.childCount) {
                val child = pageContainer.getChildAt(j)
                if (child is TextView) action(child)
            }
        }
    }

    /**
     * 用户点"收起设置"/"展开设置"按钮：切换 [settingsPanelExpanded]，同步按钮
     * 文案，实际显不显示交给 [updateChromeVisibility] 统一算（要跟"沉浸模式有
     * 没有被收起"配合判断，见该函数 KDoc "settingsPanel" 一节）。
     */
    private fun toggleSettingsPanel() {
        if (settingsPanelExpanded) collapseSettingsPanel() else expandSettingsPanel()
    }

    private fun collapseSettingsPanel() {
        if (!settingsPanelExpanded) return
        settingsPanelExpanded = false
        toggleSettingsButton.text = getString(R.string.settings_toggle_show)
        updateChromeVisibility()
    }

    private fun expandSettingsPanel() {
        settingsPanelExpanded = true
        toggleSettingsButton.text = getString(R.string.settings_toggle_hide)
        updateChromeVisibility()
    }

    private fun setupSeekBarListeners() {
        fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                forEachVisibleParagraphTextView { it.setTextSize(TypedValue.COMPLEX_UNIT_SP, progress.toFloat()) }
                fontSizeLabel.text = getString(R.string.font_size_label, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                currentSettings = currentSettings.copy(fontSizeSp = seekBar.progress).coerced()
                ReaderSettingsPreferences.save(applicationContext, currentSettings)
            }
        })

        lineSpacingSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val multiplier = progressToMultiplier(progress)
                forEachVisibleParagraphTextView { it.setLineSpacing(0f, multiplier) }
                lineSpacingLabel.text = getString(R.string.line_spacing_label, multiplier)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                currentSettings = currentSettings.copy(
                    lineSpacingMultiplier = progressToMultiplier(seekBar.progress),
                ).coerced()
                ReaderSettingsPreferences.save(applicationContext, currentSettings)
            }
        })

        paddingSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val paddingPx = dpToPx(progress)
                recyclerView.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                paddingLabel.text = getString(R.string.padding_label, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                currentSettings = currentSettings.copy(paddingDp = seekBar.progress).coerced()
                ReaderSettingsPreferences.save(applicationContext, currentSettings)
            }
        })

        blockSpacingSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                applyBlockSpacingToVisibleViews(progress)
                blockSpacingLabel.text = getString(R.string.block_spacing_label, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                currentSettings = currentSettings.copy(blockSpacingDp = seekBar.progress).coerced()
                ReaderSettingsPreferences.save(applicationContext, currentSettings)
            }
        })
    }

    /**
     * 段距拖动过程中的即时预览——跟 [forEachVisibleParagraphTextView] 是同一个"只改
     * 当前贴屏幕的 ViewHolder，不用整体重建"的思路，但段距要改两层：页内每个展示块
     * （除第一个）的 `topMargin`，以及每个页容器自己的 `topMargin`（页与页之间的
     * 留白），两层跟 [PdfPageAdapter] 里 `onBindViewHolder`/`renderPage` 用的是同一套
     * 规则，拖动预览和松手后重新绑定要看起来一致。
     */
    private fun applyBlockSpacingToVisibleViews(spacingDp: Int) {
        val spacingPx = dpToPx(spacingDp)
        for (i in 0 until recyclerView.childCount) {
            val pageContainer = recyclerView.getChildAt(i) as? LinearLayout ?: continue
            val position = recyclerView.getChildAdapterPosition(pageContainer)
            val containerParams = pageContainer.layoutParams as? RecyclerView.LayoutParams ?: continue
            containerParams.topMargin = if (position > 0) spacingPx else 0
            pageContainer.layoutParams = containerParams

            for (j in 1 until pageContainer.childCount) {
                val blockView = pageContainer.getChildAt(j)
                val params = blockView.layoutParams as? LinearLayout.LayoutParams ?: continue
                params.topMargin = spacingPx
                blockView.layoutParams = params
            }
        }
    }

    /** lineSpacingSeekBar 的 progress（0-10，整数）与行距倍数（1.0-2.0）的线性映射。 */
    private fun progressToMultiplier(progress: Int): Float = 1.0f + progress * 0.1f

    private fun multiplierToProgress(multiplier: Float): Int =
        ((multiplier - 1.0f) / 0.1f).toInt().coerceIn(0, 10)

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** 当前没有打开任何文件（[currentFileKey] 为 null）时什么都不做。 */
    private fun saveCurrentReadingProgress() {
        val key = currentFileKey ?: return
        val pageCount = currentSession?.pageCount ?: return
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return
        val page = (position + 1).coerceIn(1, pageCount)
        ReadingProgressStore.savePage(applicationContext, key, page)
    }

    /**
     * 消费一次 [pendingScrollPage]（读完就清空，避免下一次渲染误用）。
     * `recyclerView.scrollToPosition` 可以直接跳到还没绑定过的位置，不需要像旧模型
     * 那样等布局完成后再读高度算比例。
     */
    private fun restoreScrollPositionIfNeeded() {
        val page = pendingScrollPage ?: return
        pendingScrollPage = null
        recyclerView.scrollToPosition((page - 1).coerceAtLeast(0))
    }

    /**
     * 屏幕右侧的快速翻页手柄（用户要求，参照其它阅读器 App 常见的"拖拽滑条快速
     * 翻页"）——[pageScrubberTrack] 是贴右边缘的透明触摸靶区（比视觉上的
     * [pageScrubberThumb] 宽得多，手指不需要精确点在那根细条上），按下/拖动时
     * 按手指的纵向位置换算成目标页码，直接调 `recyclerView.scrollToPosition`
     * 跳过去（无动画直接跳转，原因跟 [scrollToOutlineEntry] 里"为什么不用
     * `smoothScrollToPosition`"是同一条：拖拽横跨很多页时带动画滚过中间每一页，
     * 密集触发按需加载/缓存淘汰，真机验证过这类场景会崩溃，见 `PdfPageAdapter
     * .cache` 字段注释完整背景）。
     *
     * **只在目标页码真正变化时才调用 `scrollToPosition`**（[lastScrubberTargetPage]
     * 记上一次）——`ACTION_MOVE` 触发频率很高，不加这层节流会在同一页附近来回抖动
     * 时重复触发无意义的 `RecyclerView` 重新布局。
     *
     * **避免拖拽和自动同步互相打架**：[isDraggingPageScrubber] 为真时，正常滚动
     * 触发的 [syncPageScrubberThumb]（[RecyclerView.OnScrollListener]）要跳过——
     * 拖拽本身调用 `scrollToPosition` 也会触发滚动回调，这里如果不加区分，手柄
     * 位置会被"拖拽引起的滚动回调"和"手指当前位置"来回覆盖，产生抖动。
     */
    private fun setupPageScrubber() {
        var lastScrubberTargetPage = -1
        pageScrubberTrack.setOnTouchListener { view, event ->
            // 见 updateChromeVisibility KDoc——手柄隐藏时这条触摸靶区也跟着禁用
            // （android:enabled="false"），一个普通 View 光靠 XML 的 enabled 属性
            // 不会自动挡住已经注册的 setOnTouchListener 回调，要自己在回调里判断。
            if (!view.isEnabled) return@setOnTouchListener false
            val pageCount = currentSession?.pageCount ?: return@setOnTouchListener false
            if (pageCount <= 1) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    isDraggingPageScrubber = true
                    val fraction = (event.y / view.height.toFloat()).coerceIn(0f, 1f)
                    val targetPage = (fraction * (pageCount - 1)).roundToInt() + 1
                    moveScrubberThumbTo(fraction)
                    pageScrubberLabel.text = getString(R.string.page_scrubber_label, targetPage, pageCount)
                    pageScrubberLabel.visibility = View.VISIBLE
                    if (targetPage != lastScrubberTargetPage) {
                        lastScrubberTargetPage = targetPage
                        recyclerView.scrollToPosition(targetPage - 1)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingPageScrubber = false
                    lastScrubberTargetPage = -1
                    pageScrubberLabel.visibility = View.GONE
                    saveCurrentReadingProgress()
                    true
                }

                else -> false
            }
        }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!isDraggingPageScrubber) syncPageScrubberThumb()
            }
        })
    }

    /**
     * 用户反馈"右侧滑杆改为隐藏式，点击中央才显示"——原来 [pageScrubberThumb]
     * 只要是多页文档就一直显示，改成默认隐藏（[chromeRevealedByTap] 初始
     * `false`），点击屏幕中央区域（[CENTER_TAP_ZONE_START]~[CENTER_TAP_ZONE_END]
     * 那个矩形，不是"点哪都算"）切换显隐。
     *
     * 2026-08-26 再改：用户反馈"文件名、三个菜单设置成同右侧滑杆一样是隐藏的"——
     * 同一个点击手势现在统一控制 [topButtonRow]/[fileNameLabel]/
     * [pageScrubberThumb] 三样东西的显隐（"沉浸模式"，参照大多数阅读器 App
     * "点屏幕中间呼出/收起工具栏"的常见交互），不再只管翻页手柄一个，具体的
     * 显隐判断都收在 [updateChromeVisibility] 里。
     *
     * 用 [RecyclerView.addOnItemTouchListener] 而不是直接在 [recyclerView] 上设
     * `setOnClickListener`——`RecyclerView` 本身的点击事件模型是"点在某个子
     * item 上"（正文段落/图片各自是独立子 View），没有"点在空白区域"这种天然
     * 语义。`OnItemTouchListener.onInterceptTouchEvent` 能在事件真正分发给任何
     * 子 View 之前先"旁听"一遍原始坐标，配合 [GestureDetector] 识别"这是一次
     * 单击、不是拖动/滚动/双指缩放"——`onInterceptTouchEvent` 全程返回 `false`
     * （不拦截），正常的滚动手势、图片双指缩放（[ImageView.enablePinchZoom]）
     * 都不受影响，`GestureDetector` 只是观察这些事件，不会消费掉它们。
     */
    private fun setupCenterTapToggleScrubber() {
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val centerXRange = recyclerView.width * CENTER_TAP_ZONE_START..recyclerView.width * CENTER_TAP_ZONE_END
                    val centerYRange = recyclerView.height * CENTER_TAP_ZONE_START..recyclerView.height * CENTER_TAP_ZONE_END
                    if (e.x in centerXRange && e.y in centerYRange) {
                        chromeRevealedByTap = !chromeRevealedByTap
                        updateChromeVisibility()
                    }
                    return false
                }
            },
        )
        recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(e)
                return false
            }
        })
    }

    /**
     * 统一算一次"沉浸模式的工具栏该不该显示"：[topButtonRow]（目录/打开 PDF/
     * 设置三个按钮）、[fileNameLabel]、[settingsPanel]、[pageScrubberThumb] 四样
     * 东西的显隐都在这里一次算完，[render] 每次状态变化都会调用（包括重置
     * [chromeRevealedByTap] 为 `false`，新一轮加载/新文档默认从隐藏开始，不沿用
     * 上一份文档"用户点开过工具栏"这个状态）。
     *
     * **[topButtonRow] 有一条例外**：还没有正在展示的文档时（`currentSession ==
     * null`，冷启动/文档加载失败），无条件常驻显示，不受 [chromeRevealedByTap]
     * 支配——不然用户连"打开 PDF"这个入口都点不到，没有办法开始使用这个 App。
     * 只有确实有文档在看时，才真正进入"点击呼出"的沉浸模式。
     *
     * [fileNameLabel] 除了看 [chromeRevealedByTap]，还要看"这份文本是不是真的
     * 有内容"（[TextView.getText] 非空）——[loadPdf] 里只设置文本，不直接改
     * `visibility`（避免"设置面板收起但文件名还没查到"这种中间态被误显示出来）。
     *
     * **[settingsPanel]**：2026-08-26 用户反馈"点击屏幕中央，四个设置滑杆没有
     * 隐藏"——原来 [collapseSettingsPanel]/[expandSettingsPanel] 直接读写
     * `settingsPanel.visibility` 当"用户展没展开过设置面板"的唯一状态，这次改成
     * 引入独立字段 [settingsPanelExpanded] 记这个意图，`settingsPanel` 最终显
     * 不显示是"用户展开过"和"沉浸模式没收起"两个条件的交集——用户点屏幕中央
     * 收起沉浸模式时，设置面板（即使当时是展开的）也跟着一起隐藏；再点一次
     * 呼出时，如果 `settingsPanelExpanded` 还是 `true`，设置面板会跟着一起
     * 恢复展开，不需要用户重新点一次"设置"按钮——这跟顶栏/文件名"呼出时原样
     * 恢复"是同一个逻辑。
     *
     * [pageScrubberThumb] 隐藏时 [pageScrubberTrack] 的拖拽触摸靶区跟着一起禁用
     * （`isEnabled=false`），不留"看不见但摸得到"的死角。
     */
    private fun updateChromeVisibility() {
        val session = currentSession
        val showChrome = chromeRevealedByTap || session == null
        topButtonRow.visibility = if (showChrome) View.VISIBLE else View.GONE
        fileNameLabel.visibility = if (showChrome && fileNameLabel.text.isNotEmpty()) View.VISIBLE else View.GONE
        settingsPanel.visibility = if (showChrome && settingsPanelExpanded) View.VISIBLE else View.GONE

        val pageCount = session?.pageCount ?: 0
        val showScrubberThumb = chromeRevealedByTap && pageCount > 1
        pageScrubberThumb.visibility = if (showScrubberThumb) View.VISIBLE else View.GONE
        pageScrubberTrack.isEnabled = showScrubberThumb
    }

    /** 把 [pageScrubberThumb] 挪到轨道纵向 [fraction]（0f-1f）对应的位置。 */
    private fun moveScrubberThumbTo(fraction: Float) {
        val trackHeight = pageScrubberTrack.height
        val thumbHeight = pageScrubberThumb.height
        if (trackHeight <= 0 || thumbHeight <= 0) return
        pageScrubberThumb.translationY = fraction.coerceIn(0f, 1f) * (trackHeight - thumbHeight)
    }

    /** 按当前第一个可见页码算出手柄该在的纵向位置——正常翻页（不是拖拽手柄）时同步用。 */
    private fun syncPageScrubberThumb() {
        val pageCount = currentSession?.pageCount ?: return
        if (pageCount <= 1) return
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return
        val fraction = position.toFloat() / (pageCount - 1).toFloat()
        moveScrubberThumbTo(fraction)
    }

    private fun render(state: PdfLoadState) {
        // 见 updateChromeVisibility KDoc——每次状态变化都重置成"没点过"，新一轮
        // 加载/新文档默认从隐藏开始，不沿用上一份文档"用户点开过工具栏"这个状态。
        chromeRevealedByTap = false
        when (state) {
            PdfLoadState.Idle -> {
                progressBar.visibility = View.GONE
            }

            PdfLoadState.Loading -> {
                progressBar.visibility = View.VISIBLE
                recyclerView.adapter = null
            }

            is PdfLoadState.Success -> {
                progressBar.visibility = View.GONE
                val session = currentSession
                if (session != null) {
                    recyclerView.adapter = PdfPageAdapter(
                        session,
                        ::createParagraphTextView,
                        ::createImageView,
                    ) { currentSettings.blockSpacingDp }
                    restoreScrollPositionIfNeeded()
                    pageScrubberThumb.post { syncPageScrubberThumb() }
                }
            }

            is PdfLoadState.Error -> {
                progressBar.visibility = View.GONE
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
        }
        updateChromeVisibility()
        syncTocButtonEnabled()
    }

    /** [tocButton] 只在当前文档确实有大纲时可点，见类注释"目录"一节。 */
    private fun syncTocButtonEnabled() {
        tocButton.isEnabled = currentSession?.outline?.isNotEmpty() == true
    }

    /**
     * 弹出目录列表（`AlertDialog.setItems`——系统自带、自动可滚动，不需要引入新的
     * UI 库/自定义 Adapter）。层级用缩进表示：每深一级前面加一个全角空格，比半角
     * 空格视觉上更明显。点某一条就调 [scrollToOutlineEntry]。
     *
     * [tocButton] 已经在没有大纲时禁用，理论上点不到这里——这里再判空只是防御性
     * 兜底，不依赖它。
     */
    private fun showOutlineDialog() {
        val outline = currentSession?.outline
        if (outline.isNullOrEmpty()) return
        val items = outline.map { entry -> "　".repeat(entry.depth) + entry.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.toc_dialog_title)
            .setItems(items) { _, which -> scrollToOutlineEntry(outline[which]) }
            .show()
    }

    /**
     * 见类注释"目录"一节"点击目录项怎么定位"——2026-08-20 简化成页级跳转，同日改用
     * `scrollToPosition`（无动画直接跳转）而不是 `smoothScrollToPosition`（会带动画
     * 滚过中间每一页，真机上密集触发按需加载/缓存淘汰引发过一次崩溃，见类注释里
     * 完整背景）。
     */
    private fun scrollToOutlineEntry(entry: OutlineEntry) {
        val session = currentSession ?: return
        val targetPosition = (entry.pageNumber - 1).coerceIn(0, (session.pageCount - 1).coerceAtLeast(0))
        recyclerView.scrollToPosition(targetPosition)
    }

    /**
     * 创建一个铺满可用宽度、支持双指缩放 + 缩放后拖动平移的图片 ImageView。初始按
     * "撑满可用宽度、高度按比例自适应"显示（等价于 `adjustViewBounds` + `FIT_CENTER`
     * 的视觉效果），但用 `ScaleType.MATRIX` 手动摆放，配合 [enablePinchZoom] 做手势——
     * 用 MATRIX 而不是直接改 View 本身的 `scaleX`/`scaleY`，是因为 MATRIX 缩放只在
     * ImageView 自己固定的矩形范围内放大图片内容（超出部分裁掉，效果类似放大镜看
     * 图片的一角），不会让放大后的内容视觉上盖住上下相邻的文字段落；改 View 的
     * `scaleX`/`scaleY` 则会让整个 View 连同它的边界一起变大，在纵向滚动的容器里
     * 会盖住邻居，观感更差。
     */
    private fun createImageView(bitmap: Bitmap): ImageView {
        // 2026-08-20：可用宽度从"contentContainer 自己量出来的真实宽度"改成
        // "recyclerView 量出来的真实宽度"——RecyclerView 化之后每个条目内部的
        // LinearLayout（PdfPageAdapter 创建）是 MATCH_PARENT 宽度，直接继承
        // recyclerView 减去自己 padding 后的可用宽度，跟改动前 contentContainer
        // 承担的"内容区域宽度"角色是同一件事，只是换了个 View 持有这份 padding。
        val measuredWidthPx = recyclerView.width - recyclerView.paddingLeft - recyclerView.paddingRight
        val usableWidthPx = if (measuredWidthPx > 0) {
            measuredWidthPx
        } else {
            val paddingPx = dpToPx(currentSettings.paddingDp)
            resources.displayMetrics.widthPixels - 2 * paddingPx
        }.coerceAtLeast(1)
        val fitScale = if (bitmap.width > 0) usableWidthPx.toFloat() / bitmap.width else 1f
        val displayHeightPx = (bitmap.height * fitScale).toInt().coerceAtLeast(1)

        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setImageBitmap(bitmap)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, displayHeightPx)
            imageMatrix = Matrix().apply { postScale(fitScale, fitScale) }
            enablePinchZoom(fitScale, bitmap.width, bitmap.height)
        }
    }

    /**
     * 给一个已经用 `ScaleType.MATRIX` + [baseScale]（撑满宽度的初始缩放）摆好初始
     * 状态的 ImageView 接上"双指缩放 + 缩放后单指拖动平移"手势。缩放倍数（相对
     * [baseScale] 的额外倍数）限制在 1x-[MAX_ZOOM_MULTIPLIER] 之间，避免越缩越小
     * 看不清或越缩越大失真。
     *
     * 2026-08-18 真机反馈两轮：第一轮"放大之后卡住、看不到超出屏幕的部分"，加了
     * 平移支持；第二轮加完平移后反馈"图片和表格完全没有缩放了"——排查是平移那版的
     * 触摸分发逻辑有一个经典坑：手指刚按下（`ACTION_DOWN`）那一刻，因为还不知道
     * 会不会变成双指捏合，旧写法在这一刻直接"放行"（`onTouch` 返回 false，让事件
     * 继续往上传给外层容器）——但 Android 的规则是：一个 View 如果在 `ACTION_DOWN`
     * 这一刻就放行了，后续同一串手势的事件（包括第二根手指按下的
     * `ACTION_POINTER_DOWN`）根本不会再送到这个 View，双指捏合从源头上就检测不到。
     *
     * 改用 Android 官方推荐处理"可缩放视图嵌在可滚动容器里"这种场景的标准写法——
     * [android.view.ViewParent.requestDisallowInterceptTouchEvent]：按下时先"拦下"
     * 整个手势不让外层 [recyclerView] 抢（保证后续的第二根手指、拖动都能收到），
     * 判断出"没放大、单指、不是捏合"这种该交给外层滚动的情况，再主动把拦截权限还
     * 回去（传 false），外层 [recyclerView] 才会接手继续走正常的上下滚动。
     *
     * - **没放大（倍数=1）+ 单指拖动**：判定为"想滚动整页"，交还拦截权限给
     *   [recyclerView]。
     * - **已放大（倍数>1）+ 单指拖动**：平移图片内容（[Matrix.postTranslate]），
     *   继续拦着不放。平移范围用 [clampTranslation] 限制，不让图片边缘被拖到
     *   ImageView 可视区域内侧、露出图片外的空白。
     * - **出现第二根手指**：不管当前有没有放大，都判定为捏合，继续拦着，交给
     *   [ScaleGestureDetector] 处理。
     *
     * 缩小回 1x 后自动恢复成"单指滚动整页"的行为，不需要额外的按钮或手势去
     * "退出缩放模式"。
     */
    private fun ImageView.enablePinchZoom(baseScale: Float, bitmapWidth: Int, bitmapHeight: Int) {
        var zoomMultiplier = 1f
        var translateX = 0f
        var translateY = 0f
        var lastTouchX = 0f
        var lastTouchY = 0f

        fun clampTranslation() {
            val scale = baseScale * zoomMultiplier
            val maxTranslateX = ((bitmapWidth * scale - width) / 2f).coerceAtLeast(0f)
            val maxTranslateY = ((bitmapHeight * scale - height) / 2f).coerceAtLeast(0f)
            translateX = translateX.coerceIn(-maxTranslateX, maxTranslateX)
            translateY = translateY.coerceIn(-maxTranslateY, maxTranslateY)
        }

        fun applyMatrix() {
            val scale = baseScale * zoomMultiplier
            // `postScale(scale, scale)` 不带锚点参数时，缩放锚点是 (0,0)——图片自己
            // 像素坐标系里的原点（左上角），意味着"没有额外平移"（translateX=0）时
            // 图片左上角死死钉在 View 左上角，放大多出来的宽高全部朝右下方溢出。
            // [clampTranslation] 算平移范围时假设的是"没有额外平移时图片对称溢出、
            // 整体居中"的模型（±(scaledWidth-width)/2）——两边对"没有额外平移"这个
            // 状态的理解要一致，所以不依赖 postScale 自带的锚点参数（那个参数是在
            // 图片自己的原始像素坐标系里取值，不是 View 屏幕像素坐标，用错过一次，
            // 见 NOTES.md #20），自己算好"让缩放后的图片居中摆放"所需的基准偏移量，
            // 再叠加用户实际拖动的偏移量，两步都在同一套坐标系（View 屏幕像素）里算。
            val scaledWidth = bitmapWidth * scale
            val scaledHeight = bitmapHeight * scale
            val centeringOffsetX = (width - scaledWidth) / 2f
            val centeringOffsetY = (height - scaledHeight) / 2f
            imageMatrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(centeringOffsetX + translateX, centeringOffsetY + translateY)
            }
        }

        val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    zoomMultiplier = (zoomMultiplier * detector.scaleFactor).coerceIn(1f, MAX_ZOOM_MULTIPLIER)
                    clampTranslation()
                    applyMatrix()
                    return true
                }
            },
        )

        setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    // 先拦下整个手势，保证后续第二根手指按下、拖动这些事件都能收到——
                    // 见类 KDoc"真机反馈两轮"一节，这是修"完全没有缩放"这个回归的关键。
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 出现第二根手指，确定是捏合，继续拦着不放。
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    // 捏合本身也会报 MOVE，交给 scaleDetector 处理，这里只处理单指的
                    // 情况，不重复处理。
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        if (zoomMultiplier > 1f) {
                            translateX += event.x - lastTouchX
                            translateY += event.y - lastTouchY
                            clampTranslation()
                            applyMatrix()
                            lastTouchX = event.x
                            lastTouchY = event.y
                        } else {
                            // 没放大、单指、也不是捏合：判定为想滚动整页，把拦截权限
                            // 还给外层 recyclerView。
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                else -> Unit
            }
            true
        }
    }

    /**
     * 2026-08-18 真机实测发现：这里之前用 `Typeface.MONOSPACE` 显示，用户反馈"没有
     * 断句的汉字之间有多个空格，看起来像换行"——排查用真实 PDF 逐字符核实过，插入的
     * 确实只有一个 U+0020 空格，问题在等宽字体本身：它的空格字形紧挨着全角中文字符
     * 时视觉上显得偏宽，像两个空格连在一起。改用系统默认字体显示——CJK 字符在任何
     * 字体里本来就是全角，等宽字体真正影响的只是拉丁字母/数字的宽度，对 CJK 为主
     * 的文本影响不大。
     *
     * 2026-08-20 真机反馈"中文标点符号本身显得太挤"：中文标点（，。！？）的字形本身
     * 不像英文标点那样自带前后空白（英文习惯逗号后面跟一个空格，中文没有这个书写
     * 习惯），紧挨着相邻汉字看容易显得拥挤。加一点 [letterSpacing]（0.05em，經验值，
     * 不是算出来的）——这个属性对 `TextView` 里的每个字符（汉字、标点、字母、数字）
     * 一视同仁地在右侧加空隙，不是只加给标点，所以能顺带满足"标点间距跟字母/数字
     * 间距一样"这条——用户没有要求这个值本身可调，先给固定值，如果以后觉得不够/
     * 太多再考虑加一个字距拉杆。
     *
     * 2026-08-20 新增 [isHeading]：用户要求"标题要加粗"——是不是标题由抽取层判断
     * （见 [PdfTextExtractor.classifyHeadings] KDoc，字号明显偏大或字体本身加粗，
     * 两个信号任一命中），这里只负责按判断结果切换字重，不重复判断逻辑。只加粗，
     * 不放大字号——用户第一次提了"粗体且大一号"，后来自己订正成只要粗体，照最后
     * 一次的说法做。
     */
    private fun createParagraphTextView(text: String, isHeading: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSettings.fontSizeSp.toFloat())
            setLineSpacing(0f, currentSettings.lineSpacingMultiplier)
            letterSpacing = PARAGRAPH_LETTER_SPACING_EM
            setTypeface(typeface, if (isHeading) Typeface.BOLD else Typeface.NORMAL)
        }

    private companion object {
        const val MAX_ZOOM_MULTIPLIER = 4f

        /** 见 [createParagraphTextView] 上方注释"中文标点符号本身显得太挤"一节。 */
        const val PARAGRAPH_LETTER_SPACING_EM = 0.05f

        /** 见类注释"配置变化重建后恢复文档"一节。 */
        const val KEY_CURRENT_URI = "currentUri"

        /** 见 [setupCenterTapToggleScrubber] KDoc——点击屏幕中央这块矩形区域（宽高各自 25%~75%）才切换翻页手柄显隐，不是点哪都算。 */
        const val CENTER_TAP_ZONE_START = 0.25f
        const val CENTER_TAP_ZONE_END = 0.75f
    }
}
