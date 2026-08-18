package app.pdfreader

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.pdfreader.extract.ExtractedImage
import app.pdfreader.extract.PdfContent
import app.pdfreader.extract.PdfTextExtractor
import app.pdfreader.progress.ReadingProgressKey
import app.pdfreader.progress.ReadingProgressStore
import app.pdfreader.settings.ReaderSettings
import app.pdfreader.settings.ReaderSettingsPreferences
import app.pdfreader.ui.DisplayBlock
import app.pdfreader.ui.IntentUriResolver
import app.pdfreader.ui.PdfLoadReducer
import app.pdfreader.ui.PdfLoadState
import java.io.File
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/**
 * 唯一的界面：一个"打开 PDF"按钮 + 字号/行距/边距三个 SeekBar + 进度条 + 可滚动内容区，
 * 串起"选文件 → 抽取（[PdfTextExtractor]）→ 显示"这条链路，并支持阅读设置连续调节、
 * 即时生效、重启后保持，图片穿插在文字段落之间浮动展示。
 *
 * 两个入口都会走到同一个 [loadPdf]：
 * 1. 点按钮 → [openDocumentLauncher]（Storage Access Framework 文件选择器）。
 * 2. 别的 App"用……打开"分享一个 PDF 过来 → [AndroidManifest.xml] 里的
 *    ACTION_VIEW intent-filter 启动本 Activity，[IntentUriResolver] 从 onCreate 收到的
 *    Intent 里解析出 Uri。
 *
 * 抽取在后台线程跑（可能要几秒），结果统一通过 [PdfLoadReducer] 转成 [PdfLoadState]
 * 再回主线程渲染——任何异常（文件不是有效 PDF、密码保护等）都会被 [PdfLoadReducer]
 * 兜成 [PdfLoadState.Error]，只弹 Toast，不会让 App 崩溃闪退。
 *
 * ## 换行交给 TextView 原生排版，不再自己算（2026-08-18 架构性修正）
 *
 * 这个项目从最早的 TDD 增量起，"重排"一直是自己写的 `app.pdfreader.reflow.reflow`
 * 算法：拿屏幕宽度、字体宽度估算出"每行能放几个字符"，手动插 `\n`。一路修了好几轮
 * 真机反馈的显示问题（等宽字体空格视觉偏宽、英文按中文宽度算预算腰斩、单字符测量
 * 样本不代表性、空格该用更窄的权重……），每一轮都是在把"自己的估算"往真实值上硬
 * 凑，直到真机反馈"右侧仍有空隙、还出现单个字母独占一行"，才想清楚：这些问题的
 * 共同根源是"估算"这件事本身——TextView 自带的排版引擎（StaticLayout）本来就会用
 * 真实字体在真实像素宽度上精确断行，不需要我们猜。[PdfTextExtractor.linesToParagraphs]
 * 早就把原始 PDF 里的断行拼接成了连续文字，"重排"真正要做的事到抽取这一步已经做完，
 * 剩下"按屏幕宽度换行"这一步完全可以交给 TextView 自己——不管中文（Android 对 CJK
 * 的断行规则本来就支持逐字断行，比我们自己的 tokenizer 更懂"标点不能开头"这类规则）
 * 还是英文（按单词边界断行是 TextView 的默认行为），都不需要我们插手。
 *
 * `reflow`/`LineWidthEstimator` 本身的算法没有错（各自的单元测试还在，继续保留作为
 * "给定固定宽度的纯逻辑断行"这个能力，以后如果有需要精确控制断行位置的场景还用得
 * 上），只是不再是"决定屏幕上文字怎么换行"这件事的负责人，[buildDisplayBlocks] 现在
 * 直接把抽取出的段落原文交给 TextView。
 *
 * ## 图片浮动展示（2026-08-18 增量）：展示层从单个 TextView 换成 LinearLayout 容器
 *
 * SELECTION.md 第 4 节兜底方案要求"图片降级为独立浮动展示……按大致所处的段落位置，
 * 插入到对应段落之间"。单个 TextView 塞一整段纯文字做不到"文字里穿插图片"，所以
 * [contentContainer]（`activity_main.xml` 里的纵向 LinearLayout）取代了原来的单个
 * `contentText` TextView：[renderBlocks] 按 [PdfLoadState.Success.blocks] 顺序，
 * 给每个 [DisplayBlock.Text] 建一个 TextView、每个 [DisplayBlock.Image] 建一个支持
 * 双指缩放的 ImageView（[enablePinchZoom]），依次 addView 进容器。
 *
 * [buildDisplayBlocks] 是"文字段落 + 图片"合并成有序展示块列表的地方：图片该插在哪个
 * 段落之后，由 [ExtractedImage.afterParagraphIndex] 决定（计算逻辑在
 * [app.pdfreader.extract.ImagePlacement]，纯逻辑、有独立单元测试）。
 *
 * ## 字号/边距调节：改 View 属性即可，不需要重新抽取，也不用手动重排
 *
 * [PdfTextExtractor.extractContent] 是"从 PDF 文件字节流里解析文字+图片"，很慢（要
 * 解析 PDF 结构、字体映射、逐张解码图片），而且需要重新打开原始文件——
 * [copyToCacheFile] 拷贝出来的临时文件在抽取完成后就删掉了。字号/边距变化不需要重新
 * 抽取：[loadPdf] 成功后把抽取结果缓存在 [currentContent] 里，字号变化只是改
 * TextView 的 `setTextSize`、边距变化只是改 [contentContainer] 的 padding——两者都会
 * 让 Android 自动重新排版（见上一节），不需要我们手动重建。[reflowCurrentParagraphs]
 * 目前仍保留用于边距变化时重建 [DisplayBlock] 列表 + 按比例还原滚动位置这条已测试过
 * 的路径，但它本身已经不再依赖任何宽度估算。
 *
 * ## 拖动防抖：拖动中只改外观预览
 *
 * [SeekBar.OnSeekBarChangeListener.onProgressChanged] 在拖动过程中会高频触发（每移动
 * 一点像素就回调一次）。拖动过程中只做轻量的外观预览：遍历 [contentContainer] 里
 * 现有的 TextView 子 View 直接调 `setTextSize`/`setLineSpacing`（[forEachParagraphTextView]），
 * 或者直接改 [contentContainer] 的 padding——这些都是 View 系统内部的度量+重绘，本身
 * 有节流，不会卡，且 Android 会自动据此重新排版，拖动过程本身就是实时正确的预览，
 * 不是"假装改了、松手才是真的"。
 *
 * ## 阅读进度：记比例、存取抽成独立类、onPause 存 / 内容渲染完后恢复
 *
 * "记住每个文件的阅读进度"拆成三件事，纯存取逻辑（[ReadingProgressKey] 算文件标识、
 * [ReadingProgressStore] 存取滚动比例）都不依赖 Activity，可以脱离 UI 单测；这里只是
 * 接线：
 *
 * 1. **存的时机**：[onPause]（不是 [onStop]——`onPause` 保证在 Activity 可能被系统
 *    回收前调用，是 Android 官方推荐的"落盘不可丢数据"的时机点，`onStop` 在极端场景
 *    下可能被跳过）。另外 [loadPdf] 打开新文件前也会先存一次——"打开另一份 PDF"
 *    对上一份文件来说也是"离开"，不等到整个 Activity 暂停才存。
 * 2. **恢复的时机**：[render] 处理 [PdfLoadState.Success] 时，内容已经渲染进
 *    [contentContainer]，但布局（测量出容器的实际高度）是异步的，滚动目标要
 *    等布局完成才算得出来，所以用 `contentScrollView.post { ... }` 把恢复动作排到
 *    下一次布局之后执行，而不是设完内容立刻滚动（那时候高度还是上一次的旧值）。
 * 3. **边距变化重建后的位置还原**：[reflowCurrentParagraphs] 触发重建前，先把
 *    "当前显示位置对应的滚动比例"存进 [pendingScrollRatio]，重建完成后按同一个比例
 *    恢复——不是精确到像素/行的还原，是"大致还在全文的同一个位置"这个粒度，足够用
 *    且实现简单。
 *
 * [computeScrollRatio]/[restoreScrollRatioIfNeeded] 两个方法都是通过
 * `contentScrollView.getChildAt(0)` 泛泛地拿"内容区总高度"，不关心这个子 View 具体是
 * TextView 还是 LinearLayout。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var openButton: Button
    private lateinit var toggleSettingsButton: Button
    private lateinit var settingsPanel: View
    private lateinit var progressBar: ProgressBar
    private lateinit var contentContainer: LinearLayout
    private lateinit var contentScrollView: ScrollView

    private lateinit var fontSizeLabel: TextView
    private lateinit var fontSizeSeekBar: SeekBar
    private lateinit var lineSpacingLabel: TextView
    private lateinit var lineSpacingSeekBar: SeekBar
    private lateinit var paddingLabel: TextView
    private lateinit var paddingSeekBar: SeekBar

    /** 当前生效的阅读设置，onCreate 时从 [ReaderSettingsPreferences] 读，改动即时写回。 */
    private var currentSettings: ReaderSettings = ReaderSettings()

    /** 最近一次成功抽取出的文字段落+图片，供字号/边距变化时重新排版，见类注释。 */
    private var currentContent: PdfContent? = null

    /** 当前显示这份文件的阅读进度 key（[ReadingProgressKey]），没打开任何文件时为 null。 */
    private var currentFileKey: String? = null

    /**
     * 内容渲染完成后要恢复到的滚动比例。[loadPdf] 里从 [ReadingProgressStore] 读出来，
     * [reflowCurrentParagraphs] 里则是重排前临时记下的"当前位置"，见类注释"阅读进度"一节。
     * 消费一次（在 [restoreScrollRatioIfNeeded] 里）就清空，避免下一次渲染误用旧值。
     */
    private var pendingScrollRatio: Float? = null

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { loadPdf(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyStatusBarInsetAsPadding()

        openButton = findViewById(R.id.openButton)
        toggleSettingsButton = findViewById(R.id.toggleSettingsButton)
        settingsPanel = findViewById(R.id.settingsPanel)
        progressBar = findViewById(R.id.progressBar)
        contentContainer = findViewById(R.id.contentContainer)
        contentScrollView = findViewById(R.id.contentScrollView)
        fontSizeLabel = findViewById(R.id.fontSizeLabel)
        fontSizeSeekBar = findViewById(R.id.fontSizeSeekBar)
        lineSpacingLabel = findViewById(R.id.lineSpacingLabel)
        lineSpacingSeekBar = findViewById(R.id.lineSpacingSeekBar)
        paddingLabel = findViewById(R.id.paddingLabel)
        paddingSeekBar = findViewById(R.id.paddingSeekBar)

        currentSettings = ReaderSettingsPreferences.load(applicationContext)
        applySettingsToView(currentSettings)
        syncSeekBars(currentSettings)
        setupSeekBarListeners()

        openButton.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/pdf"))
        }
        toggleSettingsButton.setOnClickListener { toggleSettingsPanel() }

        // 别的 App"用……打开"分享过来的 PDF：启动时就自动开始抽取，不需要用户再点一次按钮。
        IntentUriResolver.resolvePdfUri(intent)?.let { uri -> loadPdf(uri) }
    }

    /** 离开当前文件（切后台、被系统回收前）时落盘一次阅读进度，见类注释"阅读进度"一节。 */
    override fun onPause() {
        super.onPause()
        saveCurrentReadingProgress()
    }

    private fun loadPdf(uri: Uri) {
        // 打开新文件前，先把当前正在显示的文件（如果有）的阅读进度存一次——"打开
        // 另一份 PDF"对上一份文件来说也是"离开"，不用等到 onPause 才存。必须在
        // render(Loading) 清空内容区之前算，不然滚动位置已经被重置成 0。
        saveCurrentReadingProgress()
        render(PdfLoadState.Loading)

        thread {
            val result = runCatching {
                val file = copyToCacheFile(uri)
                val content = try {
                    PdfTextExtractor.extractContent(applicationContext, file)
                } finally {
                    file.delete()
                }
                currentContent = content
                val fileKey = ReadingProgressKey.fromParagraphs(content.paragraphs)
                currentFileKey = fileKey
                // 读过这份文件就恢复到上次的位置；没读过就是 0f（从头开始），不用
                // 特殊分支——ReadingProgressStore.loadProgress 没记录时返回 null，
                // 这里兜底成 0f 是唯一需要区分两种语义的地方。
                pendingScrollRatio = ReadingProgressStore.loadProgress(applicationContext, fileKey) ?: 0f
                buildDisplayBlocks(content)
            }
            val state = PdfLoadReducer.fromResult(result)
            runOnUiThread {
                render(state)
                // 只在"打开一份新文件"这条路径自动收起设置面板，给内容腾屏幕——
                // reflowCurrentParagraphs()（用户正在调字号/边距时触发的重排）不走
                // 这里，不然用户刚碰一下滑条面板就被收起，体验上会打架。
                if (state is PdfLoadState.Success) collapseSettingsPanel()
            }
        }
    }

    /**
     * 边距变化（松手那一刻）触发：不重新抽取 PDF、不重新解码图片，用已缓存的段落重建
     * 一遍 [buildDisplayBlocks]，见类注释"核心逻辑"一节。
     *
     * 2026-08-18 补：字号变化不再调用这个方法——[buildDisplayBlocks] 不再手动算换行
     * 之后，TextView 的 `setTextSize` 本身就会触发系统重新排版（见 [buildDisplayBlocks]
     * 类注释"架构性修正"一节），不需要我们再手动重建一遍。边距变化会改变
     * [contentContainer] 的可用宽度，`setPadding` 同样会触发子 View 自动重新排版，
     * 严格来说这个方法本身也不再必要——保留它只是为了兼容"重排完成后按比例还原滚动
     * 位置"这条已经测试过的阅读体验（视图不整体重建的话，滚动位置本来就不会跑，但
     * 保留这一层不算错，只是不再是必需品，暂不动它，等以后有明确理由再简化）。
     */
    private fun reflowCurrentParagraphs() {
        val content = currentContent ?: return
        // 重排会整块换掉 contentContainer 的内容，换之前先把"当前显示位置"换算成比例
        // 存起来，重排完成后按同一个比例还原（不是像素级精确还原，见类注释"阅读进度"
        // 一节）。必须在 render(Loading) 清空内容区之前算。
        pendingScrollRatio = computeScrollRatio()
        render(PdfLoadState.Loading)

        thread {
            val state = PdfLoadReducer.fromResult(runCatching { buildDisplayBlocks(content) })
            runOnUiThread { render(state) }
        }
    }

    /**
     * 把抽取出的文字段落 + 图片，按"图片插在哪个段落之后"（[ExtractedImage.afterParagraphIndex]，
     * 见 [app.pdfreader.extract.ImagePlacement]）合并成有序的 [DisplayBlock] 列表。
     *
     * ## 2026-08-18 架构性修正：不再用 [reflow] 手动算换行，直接交给 TextView 原生排版
     *
     * 这个项目从最早的 TDD 增量起，"重排"就是自己写的 [reflow] 算法：拿屏幕宽度、字体
     * 宽度估算出"每行能放几个字符"，手动在文字里插 `\n`。一路修了好几轮真机反馈的
     * 显示问题（等宽字体空格视觉偏宽、英文按中文宽度算预算腰斩、单字符测量样本不
     * 代表性、空格该用更窄的权重……），每一轮都是在把"我们自己的估算"往真实值上
     * 硬凑。这一次真机反馈"右侧仍有空隙、还出现单个字母'A'独占一行"，逼着往回想了
     * 一层：**这些问题的共同根源是"估算"这件事本身**——TextView 自带的排版引擎
     * （StaticLayout）本来就会用真实字体在真实像素宽度上精确断行，不需要猜。
     * [PdfTextExtractor.linesToParagraphs] 早就把原始 PDF 里的断行拼接成了连续文字
     * （见其 KDoc），"重排"真正要做的事到这一步已经做完了，剩下"按屏幕宽度换行"这一步
     * 完全可以交给 TextView 自己——不管是中文（Android 对 CJK 的断行规则本来就支持
     * 逐字断行，且比我们自己的 tokenizer 更懂标点不能开头这类规则）还是英文
     * （按单词边界断行是 TextView 的默认行为），都不需要我们插手。
     *
     * [reflow]/[LineWidthEstimator] 本身的算法没有错（各自的单元测试还在，继续保留
     * 作为"给定固定宽度的纯逻辑断行"这个能力，以后如果有需要精确控制断行位置的场景
     * 还用得上），只是不该再是"决定屏幕上文字怎么换行"这件事的负责人。
     */
    private fun buildDisplayBlocks(content: PdfContent): List<DisplayBlock> {
        val imagesByAfterIndex = content.images.groupBy { it.afterParagraphIndex }
        val blocks = mutableListOf<DisplayBlock>()

        fun appendImagesAfter(paragraphIndex: Int) {
            imagesByAfterIndex[paragraphIndex]?.forEach { blocks.add(DisplayBlock.Image(it.bitmap)) }
        }

        appendImagesAfter(-1) // 插在所有段落之前的图片。
        content.paragraphs.forEachIndexed { index, paragraph ->
            blocks.add(DisplayBlock.Text(paragraph))
            appendImagesAfter(index)
        }
        return blocks
    }

    /**
     * 让根布局的顶部内边距动态加上状态栏高度，避免第一个控件（打开 PDF 按钮）被状态栏
     * 文字/图标盖住。
     *
     * 2026-08-18 真机截图验证过：XML 里写 `android:fitsSystemWindows="true"` 在这台设备
     * 上不生效——现代安卓（尤其 edge-to-edge 强制生效的版本）默认让内容延伸到系统栏后面，
     * `fitsSystemWindows` 这个老属性覆盖不了这种情况，必须主动监听 [WindowInsetsCompat]
     * 读出状态栏高度、手动加进 padding。只处理顶部（状态栏）：底部导航栏这台设备是手势
     * 导航条，不挡到 `contentScrollView`，暂不处理，以后如果发现虚拟按键导航条挡住底部
     * 内容，再照同样的思路把 `insets.bottom` 也加进 `paddingBottom`。
     */
    private fun applyStatusBarInsetAsPadding() {
        val root = findViewById<View>(R.id.rootLayout)
        val basePaddingLeft = root.paddingLeft
        val basePaddingTop = root.paddingTop
        val basePaddingRight = root.paddingRight
        val basePaddingBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val statusBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(
                basePaddingLeft,
                basePaddingTop + statusBarInset.top,
                basePaddingRight,
                basePaddingBottom,
            )
            windowInsets
        }
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

    /** 把 [settings] 应用到 [contentContainer] 的外观（目前只有 padding；字号/行距在每个段落 TextView 创建时应用）。 */
    private fun applySettingsToView(settings: ReaderSettings) {
        val paddingPx = dpToPx(settings.paddingDp)
        contentContainer.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
    }

    /** 把 [settings] 的数值同步到三个 SeekBar 的 progress 和 Label 文字（不触发重排）。 */
    private fun syncSeekBars(settings: ReaderSettings) {
        fontSizeSeekBar.progress = settings.fontSizeSp
        fontSizeLabel.text = getString(R.string.font_size_label, settings.fontSizeSp)

        lineSpacingSeekBar.progress = multiplierToProgress(settings.lineSpacingMultiplier)
        lineSpacingLabel.text = getString(R.string.line_spacing_label, settings.lineSpacingMultiplier)

        paddingSeekBar.progress = settings.paddingDp
        paddingLabel.text = getString(R.string.padding_label, settings.paddingDp)
    }

    /** 遍历 [contentContainer] 里当前已经渲染出来的文字段落 TextView（跳过图片 ImageView）。 */
    private fun forEachParagraphTextView(action: (TextView) -> Unit) {
        for (i in 0 until contentContainer.childCount) {
            val child = contentContainer.getChildAt(i)
            if (child is TextView) action(child)
        }
    }

    /** 用户点"收起设置"/"展开设置"按钮：切换 [settingsPanel] 可见性，同步按钮文案。 */
    private fun toggleSettingsPanel() {
        if (settingsPanel.visibility == View.VISIBLE) collapseSettingsPanel() else expandSettingsPanel()
    }

    private fun collapseSettingsPanel() {
        if (settingsPanel.visibility == View.GONE) return
        settingsPanel.visibility = View.GONE
        toggleSettingsButton.text = getString(R.string.settings_toggle_show)
    }

    private fun expandSettingsPanel() {
        settingsPanel.visibility = View.VISIBLE
        toggleSettingsButton.text = getString(R.string.settings_toggle_hide)
    }

    private fun setupSeekBarListeners() {
        fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                forEachParagraphTextView { it.setTextSize(TypedValue.COMPLEX_UNIT_SP, progress.toFloat()) }
                fontSizeLabel.text = getString(R.string.font_size_label, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                currentSettings = currentSettings.copy(fontSizeSp = seekBar.progress).coerced()
                ReaderSettingsPreferences.save(applicationContext, currentSettings)
                reflowCurrentParagraphs()
            }
        })

        lineSpacingSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val multiplier = progressToMultiplier(progress)
                forEachParagraphTextView { it.setLineSpacing(0f, multiplier) }
                lineSpacingLabel.text = getString(R.string.line_spacing_label, multiplier)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // 行距不影响每行能放几个字符（只影响行与行之间的垂直间距），不需要重排。
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
                contentContainer.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                paddingLabel.text = getString(R.string.padding_label, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // 边距改变了可用宽度像素，会影响每行能放几个字符，需要重排（同字号）。
                currentSettings = currentSettings.copy(paddingDp = seekBar.progress).coerced()
                ReaderSettingsPreferences.save(applicationContext, currentSettings)
                reflowCurrentParagraphs()
            }
        })
    }

    /** lineSpacingSeekBar 的 progress（0-10，整数）与行距倍数（1.0-2.0）的线性映射。 */
    private fun progressToMultiplier(progress: Int): Float = 1.0f + progress * 0.1f

    private fun multiplierToProgress(multiplier: Float): Int =
        ((multiplier - 1.0f) / 0.1f).toInt().coerceIn(0, 10)

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** 当前没有打开任何文件（[currentFileKey] 为 null）时什么都不做。 */
    private fun saveCurrentReadingProgress() {
        val key = currentFileKey ?: return
        ReadingProgressStore.saveProgress(applicationContext, key, computeScrollRatio())
    }

    /**
     * 把 [contentScrollView] 当前的滚动位置换算成 0.0-1.0 的比例。内容还没撑满屏幕
     * （不可滚动）或还没完成布局时，[View.getHeight] 量不出可滚动范围，此时按"在最
     * 顶部"处理，返回 0f——这也是空文档/刚开始加载时的合理默认值。
     */
    private fun computeScrollRatio(): Float {
        val child = contentScrollView.getChildAt(0) ?: return 0f
        val scrollableRange = child.height - contentScrollView.height
        if (scrollableRange <= 0) return 0f
        return (contentScrollView.scrollY.toFloat() / scrollableRange).coerceIn(0f, 1f)
    }

    /**
     * 消费一次 [pendingScrollRatio]（读完就清空，避免下一次渲染误用）。用
     * `contentScrollView.post { ... }` 把滚动动作排到下一次布局完成之后执行——刚
     * 渲染完 [contentContainer] 的子 View，它的高度还是上一次布局的旧值，这一刻直接
     * 读高度算出来的滚动目标是错的。
     */
    private fun restoreScrollRatioIfNeeded() {
        val ratio = pendingScrollRatio ?: return
        pendingScrollRatio = null
        contentScrollView.post {
            val child = contentScrollView.getChildAt(0) ?: return@post
            val scrollableRange = child.height - contentScrollView.height
            val targetScrollY = if (scrollableRange > 0) (ratio * scrollableRange).toInt() else 0
            contentScrollView.scrollTo(0, targetScrollY)
        }
    }

    private fun render(state: PdfLoadState) {
        when (state) {
            PdfLoadState.Idle -> {
                progressBar.visibility = View.GONE
            }

            PdfLoadState.Loading -> {
                progressBar.visibility = View.VISIBLE
                contentContainer.removeAllViews()
            }

            is PdfLoadState.Success -> {
                progressBar.visibility = View.GONE
                renderBlocks(state.blocks)
                restoreScrollRatioIfNeeded()
            }

            is PdfLoadState.Error -> {
                progressBar.visibility = View.GONE
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 把 [blocks] 依次渲染成 [contentContainer] 里的子 View：文字段落→TextView，图片→ImageView。 */
    private fun renderBlocks(blocks: List<DisplayBlock>) {
        contentContainer.removeAllViews()
        blocks.forEachIndexed { index, block ->
            val view = when (block) {
                is DisplayBlock.Text -> createParagraphTextView(block.text)
                is DisplayBlock.Image -> createImageView(block.bitmap)
            }
            if (index > 0) {
                val params = view.layoutParams as LinearLayout.LayoutParams
                params.topMargin = dpToPx(BLOCK_SPACING_DP)
                view.layoutParams = params
            }
            contentContainer.addView(view)
        }
    }

    /**
     * 2026-08-18 真机实测发现：这里之前用 `Typeface.MONOSPACE` 显示，用户反馈"没有
     * 断句的汉字之间有多个空格，看起来像换行"——排查用真实 PDF 逐字符核实过，插入的
     * 确实只有一个 U+0020 空格（[normalizeCjkSpacing] 没有 bug），问题在等宽字体本身：
     * 它的空格字形紧挨着全角中文字符时视觉上显得偏宽，像两个空格连在一起。改用系统
     * 默认字体显示——CJK 字符在任何字体里本来就是全角（这是 Unicode 东亚宽度属性
     * 决定的，不是等宽字体特有的效果），等宽字体真正影响的只是拉丁字母/数字的宽度，
     * 对 CJK 为主的文本影响不大。
     */
    private fun createParagraphTextView(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSettings.fontSizeSp.toFloat())
            setLineSpacing(0f, currentSettings.lineSpacingMultiplier)
        }

    /**
     * 创建一个铺满可用宽度、支持双指缩放 + 缩放后拖动平移的图片 ImageView。初始按
     * "撑满可用宽度、高度按比例自适应"显示（等价于 `adjustViewBounds` + `FIT_CENTER`
     * 的视觉效果），但用 `ScaleType.MATRIX` 手动摆放，配合 [enablePinchZoom] 做手势——
     * 用 MATRIX 而不是直接改 View 本身的 `scaleX`/`scaleY`，是因为 MATRIX 缩放只在
     * ImageView 自己固定的矩形范围内放大图片内容（超出部分裁掉，效果类似放大镜看
     * 图片的一角），不会让放大后的内容视觉上盖住上下相邻的文字段落；改 View 的
     * `scaleX`/`scaleY` 则会让整个 View 连同它的边界一起变大，在纵向滚动的
     * LinearLayout 里会盖住邻居，观感更差。
     */
    private fun createImageView(bitmap: Bitmap): ImageView {
        // 踩过的坑（2026-08-18 真机反馈"图片右侧显示不全"）：这里原来用
        // `屏幕宽度 - 2×contentContainer 自己的 padding` 算可用宽度，漏算了外层
        // rootLayout 自己还有一圈 16dp 的水平 padding（见 activity_main.xml）——
        // 算出来的可用宽度比 ImageView 实际能拿到的真实宽度宽了那一截，图片被按偏大
        // 的宽度整体放大，超出 ImageView 真实边界的部分（右侧）就被裁掉了。改成直接
        // 读 contentContainer 自己量出来的真实宽度（它是 ImageView 的直接父容器，
        // ImageView 是 MATCH_PARENT，contentContainer 的"宽度减自己的 padding"就是
        // ImageView 真实能用的宽度，不需要再手动拼凑各层 padding）；contentContainer
        // 还没走过布局（宽度是 0）这种极端情况才退回旧的估算公式兜底。
        val measuredWidthPx = contentContainer.width - contentContainer.paddingLeft - contentContainer.paddingRight
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
     * 继续往上传给 [contentScrollView]）——但 Android 的规则是：一个 View 如果在
     * `ACTION_DOWN` 这一刻就放行了，后续同一串手势的事件（包括第二根手指按下的
     * `ACTION_POINTER_DOWN`）根本不会再送到这个 View，双指捏合从源头上就检测不到，
     * 不是"检测到了但没反应"，是"压根没收到手指事件"。
     *
     * 改用 Android 官方推荐处理"可缩放视图嵌在可滚动容器里"这种场景的标准写法——
     * [android.view.ViewParent.requestDisallowInterceptTouchEvent]：按下时先"拦下"
     * 整个手势不让外层 [contentScrollView] 抢（保证后续的第二根手指、拖动都能收到），
     * 判断出"没放大、单指、不是捏合"这种该交给外层滚动的情况，再主动把拦截权限还
     * 回去（传 false），外层 [contentScrollView] 才会接手继续走正常的上下滚动。
     *
     * - **没放大（倍数=1）+ 单指拖动**：判定为"想滚动整页"，交还拦截权限给
     *   [contentScrollView]。
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
            imageMatrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(translateX, translateY)
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
                            // 还给外层 contentScrollView。
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

    private companion object {
        /**
         * 展示块（文字段落/图片）之间的纵向间距。2026-08-18 从 12dp 调到 24dp——用户
         * 反馈"想让段与段之间的间隔更明显"，字号/行距还能再调，这个值先给个明显但不
         * 夸张的量，以后想再调可以考虑做成第四个可调设置项。
         */
        const val BLOCK_SPACING_DP = 24
        const val MAX_ZOOM_MULTIPLIER = 4f
    }
}
