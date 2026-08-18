package app.pdfreader

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextPaint
import android.util.TypedValue
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
import app.pdfreader.reflow.DEFAULT_NON_CJK_WIDTH_RATIO
import app.pdfreader.reflow.LineWidthEstimator
import app.pdfreader.reflow.reflow
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
 * 串起"选文件 → 抽取（[PdfTextExtractor]）→ 重排（[reflow]）→ 显示"这条链路，并支持
 * 阅读设置连续调节、即时生效、重启后保持，图片穿插在文字段落之间浮动展示。
 *
 * 两个入口都会走到同一个 [loadPdf]：
 * 1. 点按钮 → [openDocumentLauncher]（Storage Access Framework 文件选择器）。
 * 2. 别的 App"用……打开"分享一个 PDF 过来 → [AndroidManifest.xml] 里的
 *    ACTION_VIEW intent-filter 启动本 Activity，[IntentUriResolver] 从 onCreate 收到的
 *    Intent 里解析出 Uri。
 *
 * 抽取+重排在后台线程跑（可能要几秒），结果统一通过 [PdfLoadReducer] 转成
 * [PdfLoadState] 再回主线程渲染——任何异常（文件不是有效 PDF、密码保护等）都会被
 * [PdfLoadReducer] 兜成 [PdfLoadState.Error]，只弹 Toast，不会让 App 崩溃闪退。
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
 * [app.pdfreader.extract.ImagePlacement]，纯逻辑、有独立单元测试）；每个文字段落
 * 单独调用一次 [reflow]（而不是像改动前那样把全部段落一次性传给 [reflow] 再拼成一个
 * 大 TextView）——[reflow] 本身完全没有改动，单独传一个段落跟"混在一大批段落里传"
 * 产出的换行结果是完全一样的（每个段落的换行只取决于段落自己的文字，不会跨段落
 * 影响），这样改只是为了能把每个段落单独塞进自己的 TextView，不影响重排算法本身。
 *
 * ## 字号调节的核心逻辑：拖动字号/边距要重新 reflow，不是重新抽取
 *
 * [PdfTextExtractor.extractContent] 是"从 PDF 文件字节流里解析文字+图片"，很慢（要
 * 解析 PDF 结构、字体映射、逐张解码图片），而且需要重新打开原始文件——
 * [copyToCacheFile] 拷贝出来的临时文件在抽取完成后就删掉了，SAF 返回的 content:// Uri
 * 理论上还能再读一次，但没必要：[reflow] 需要的输入是"阅读顺序正确的段落列表"，这份
 * 数据（连同图片）在第一次抽取成功后不会因为字号变化而改变，改变的只是"每行能放几个
 * 字符"。所以 [loadPdf] 成功后把抽取结果缓存在 [currentContent] 里，字号/边距变化时
 * 只调用 [reflowCurrentParagraphs]——重新算一次 [LineWidthEstimator.estimate]，用新
 * 行宽重新跑 [buildDisplayBlocks]，不重新解析 PDF 文件、不重新解码图片。这比"重新
 * 抽取"更快，也不依赖 content:// Uri 在 App 生命周期内可重复打开这个不一定稳妥的假设。
 *
 * ## 拖动防抖：拖动中只改外观预览，松手才重排
 *
 * [SeekBar.OnSeekBarChangeListener.onProgressChanged] 在拖动过程中会高频触发（每移动
 * 一点像素就回调一次）。如果每次回调都触发一次完整的 [reflowCurrentParagraphs]
 * （分配一个新线程、遍历全部段落重新贪心换行、切回主线程重建容器里全部子 View），
 * 拖动手感会明显卡顿。所以拖动过程中只做轻量的外观预览：遍历 [contentContainer] 里
 * 现有的 TextView 子 View 直接调 `setTextSize`/`setLineSpacing`（[forEachParagraphTextView]），
 * 或者直接改 [contentContainer] 的 padding——这些都是 View 系统内部的度量+重绘，本身
 * 有节流，不会卡。真正的重排逻辑挪到
 * [SeekBar.OnSeekBarChangeListener.onStopTrackingTouch]（松手那一刻，整个拖动过程只
 * 触发一次），这是 Android 官方 SeekBar 就自带的"防抖"信号，不需要自己写计时器/
 * Handler.postDelayed 这类防抖代码。
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
 * 3. **字号/边距重排后的位置还原**：[reflowCurrentParagraphs] 触发重排前，先把
 *    "当前显示位置对应的滚动比例"存进 [pendingScrollRatio]，重排完成后按同一个比例
 *    恢复——不是精确到像素/行的还原（重排后每行字符数变了，原来第 500 行不一定还是
 *    第 500 行），是"大致还在全文的同一个位置"这个粒度，足够用且实现简单。
 *
 * [computeScrollRatio]/[restoreScrollRatioIfNeeded] 两个方法都是通过
 * `contentScrollView.getChildAt(0)` 泛泛地拿"内容区总高度"，不关心这个子 View 具体是
 * TextView 还是 LinearLayout——contentContainer 取代 contentText 之后这两个方法完全
 * 不需要改，这是本次改动特意确认过的兼容点。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var openButton: Button
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
        val lineMetrics = estimateLineMetrics()

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
                buildDisplayBlocks(content, lineMetrics)
            }
            val state = PdfLoadReducer.fromResult(result)
            runOnUiThread { render(state) }
        }
    }

    /**
     * 字号/边距变化（松手那一刻）触发：不重新抽取 PDF、不重新解码图片，只用新的行宽
     * 对已缓存的段落重新跑一次 [buildDisplayBlocks]，见类注释"核心逻辑"一节。
     */
    private fun reflowCurrentParagraphs() {
        val content = currentContent ?: return
        // 重排会整块换掉 contentContainer 的内容，换之前先把"当前显示位置"换算成比例
        // 存起来，重排完成后按同一个比例还原（不是像素级精确还原，见类注释"阅读进度"
        // 一节）。必须在 render(Loading) 清空内容区之前算。
        pendingScrollRatio = computeScrollRatio()
        render(PdfLoadState.Loading)
        val lineMetrics = estimateLineMetrics()

        thread {
            val state = PdfLoadReducer.fromResult(runCatching { buildDisplayBlocks(content, lineMetrics) })
            runOnUiThread { render(state) }
        }
    }

    /**
     * 把抽取出的文字段落 + 图片，按"图片插在哪个段落之后"（[ExtractedImage.afterParagraphIndex]，
     * 见 [app.pdfreader.extract.ImagePlacement]）合并成有序的 [DisplayBlock] 列表。
     * 每个段落单独调用一次 [reflow]，原因见类注释"图片浮动展示"一节；`nonCjkWidthRatio`
     * 也一并传给 [reflow]，见 [estimateLineMetrics] KDoc"真机实测发现两个问题"一节。
     */
    private fun buildDisplayBlocks(content: PdfContent, lineMetrics: LineMetrics): List<DisplayBlock> {
        val imagesByAfterIndex = content.images.groupBy { it.afterParagraphIndex }
        val blocks = mutableListOf<DisplayBlock>()

        fun appendImagesAfter(paragraphIndex: Int) {
            imagesByAfterIndex[paragraphIndex]?.forEach { blocks.add(DisplayBlock.Image(it.bitmap)) }
        }

        appendImagesAfter(-1) // 插在所有段落之前的图片。
        content.paragraphs.forEachIndexed { index, paragraph ->
            val reflowedText = reflow(
                listOf(paragraph),
                lineMetrics.maxLineWidthChars,
                lineMetrics.nonCjkWidthRatio,
            ).joinToString("\n")
            blocks.add(DisplayBlock.Text(reflowedText))
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

    /** [estimateLineMetrics] 的返回值：reflow 需要的两个数字，见该方法 KDoc。 */
    private data class LineMetrics(val maxLineWidthChars: Int, val nonCjkWidthRatio: Float)

    /**
     * 用屏幕宽度和当前字号/边距设置，估算 [reflow] 需要的两个数字：每行的宽度预算
     * （[LineWidthEstimator]，纯函数，见其单元测试），以及"拉丁字母/数字相对中文
     * 字符的宽度比例"（[reflow] 的 `nonCjkWidthRatio` 参数）。
     *
     * 2026-08-18 真机实测发现两个问题，都在这里改的：
     * 1. 测量字体之前用的是 [Typeface.MONOSPACE]，但显示用的 TextView 已经改成系统
     *    默认字体（见 [createParagraphTextView] 的空格视觉 bug 修复）——测量字体和
     *    显示字体不一致，估算就不准了，这里也同步换成默认字体（不显式设置 typeface），
     *    量出来的宽度才是 TextView 实际会用的宽度。
     * 2. 之前只测了一个较宽的 CJK 字符宽度，把这个宽度直接当"每个字符"的预算——纯
     *    英文 PDF 因此每行只用了不到一半屏幕宽度就换行（拉丁字符实际宽度大约只有
     *    中文全角字符的一半，用中文的宽度当预算单位，等于把英文字符的预算也砍了
     *    一半）。现在额外测一个拉丁字符（用"W"，比常见小写字母宽，同样是保守估计，
     *    不会让预算算多）算出实际比例，传给 [reflow] 的 `nonCjkWidthRatio`。
     */
    private fun estimateLineMetrics(): LineMetrics {
        val paddingPx = dpToPx(currentSettings.paddingDp)
        val usableWidthPx = resources.displayMetrics.widthPixels - 2 * paddingPx
        val measurePaint = TextPaint().apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                currentSettings.fontSizeSp.toFloat(),
                resources.displayMetrics,
            )
        }
        val cjkCharWidthPx = measurePaint.measureText("宽")
        val maxLineWidthChars = LineWidthEstimator.estimate(usableWidthPx, cjkCharWidthPx)

        // 踩过的坑：一开始只测单个"W"字符的宽度当拉丁字符代表——真机实测发现在这台
        // 设备的系统默认字体里，"W"几乎跟全角中文字符一样宽（57px vs 56px），算出来
        // 的比例约等于 1，等于没起作用（一个字符不能代表"拉丁字符普遍多窄"这件事，
        // W/M 这类大写字母本来就是拉丁字母表里最宽的极端值）。改成测一整段大小写字母
        // 加数字，取平均宽度——覆盖了从"i/l"这种窄字符到"W/M"这种宽字符的整个分布，
        // 平均值才真正代表"一段英文文字大概多宽"。
        val latinSample = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val latinCharWidthPx = measurePaint.measureText(latinSample) / latinSample.length
        val nonCjkWidthRatio = if (cjkCharWidthPx > 0f) {
            (latinCharWidthPx / cjkCharWidthPx).coerceIn(0.1f, 1f)
        } else {
            DEFAULT_NON_CJK_WIDTH_RATIO
        }
        return LineMetrics(maxLineWidthChars, nonCjkWidthRatio)
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
     * 2026-08-18 真机实测发现：这里之前跟 [estimateLineWidthChars] 的测量用同一支
     * `Typeface.MONOSPACE`，用户反馈"没有断句的汉字之间有多个空格，看起来像换行"——
     * 排查用真实 PDF 逐字符核实过，插入的确实只有一个 U+0020 空格（[normalizeCjkSpacing]
     * 没有 bug），问题在等宽字体本身：它的空格字形紧挨着全角中文字符时视觉上显得偏宽，
     * 像两个空格连在一起。改用系统默认字体显示——CJK 字符在任何字体里本来就是全角
     * （这是 Unicode 东亚宽度属性决定的，不是等宽字体特有的效果），等宽字体真正影响的
     * 只是拉丁字母/数字的宽度，对 CJK 为主的文本影响不大；[estimateLineWidthChars] 那支
     * 独立的 TextPaint 继续用 MONOSPACE 测量（本来就跟显示用的字体是两回事，互不影响），
     * 行宽估算的"保守"性质（宁可提前换行也不超宽）不受这个改动影响。
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
     * 创建一个铺满可用宽度、支持双指缩放的图片 ImageView。初始按"撑满可用宽度、
     * 高度按比例自适应"显示（等价于 `adjustViewBounds` + `FIT_CENTER` 的视觉效果），
     * 但用 `ScaleType.MATRIX` 手动摆放，配合 [enablePinchZoom] 做双指缩放——用
     * MATRIX 而不是直接改 View 本身的 `scaleX`/`scaleY`，是因为 MATRIX 缩放只在
     * ImageView 自己固定的矩形范围内放大图片内容（超出部分裁掉，效果类似放大镜看
     * 图片的一角），不会让放大后的内容视觉上盖住上下相邻的文字段落；改 View 的
     * `scaleX`/`scaleY` 则会让整个 View 连同它的边界一起变大，在纵向滚动的
     * LinearLayout 里会盖住邻居，观感更差。这是"够用即可、不做完整图片查看器"这个
     * 范围下的简单实现，不支持双指缩放之外的拖拽平移。
     */
    private fun createImageView(bitmap: Bitmap): ImageView {
        val paddingPx = dpToPx(currentSettings.paddingDp)
        val usableWidthPx = (resources.displayMetrics.widthPixels - 2 * paddingPx).coerceAtLeast(1)
        val fitScale = if (bitmap.width > 0) usableWidthPx.toFloat() / bitmap.width else 1f
        val displayHeightPx = (bitmap.height * fitScale).toInt().coerceAtLeast(1)

        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setImageBitmap(bitmap)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, displayHeightPx)
            imageMatrix = Matrix().apply { postScale(fitScale, fitScale) }
            enablePinchZoom(fitScale)
        }
    }

    /**
     * 给一个已经用 `ScaleType.MATRIX` + [baseScale]（撑满宽度的初始缩放）摆好初始
     * 状态的 ImageView 接上双指缩放手势。缩放倍数（相对 [baseScale] 的额外倍数）
     * 限制在 1x-[MAX_ZOOM_MULTIPLIER] 之间，避免越缩越小看不清或越缩越大失真。
     *
     * 只有出现第二根手指（真正在做捏合手势）时才消费触摸事件——单指触摸原样放行
     * （返回 false），让事件继续往上传给 [contentScrollView] 处理正常的上下滚动；
     * 不这样区分的话，手指落在图片上就没法上下滑动看后面的内容了。
     */
    private fun ImageView.enablePinchZoom(baseScale: Float) {
        val matrix = Matrix().apply { postScale(baseScale, baseScale) }
        var zoomMultiplier = 1f
        val detector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val nextMultiplier = (zoomMultiplier * detector.scaleFactor)
                        .coerceIn(1f, MAX_ZOOM_MULTIPLIER)
                    val appliedFactor = nextMultiplier / zoomMultiplier
                    zoomMultiplier = nextMultiplier
                    matrix.postScale(appliedFactor, appliedFactor, detector.focusX, detector.focusY)
                    imageMatrix = matrix
                    return true
                }
            },
        )
        setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            event.pointerCount > 1 || detector.isInProgress
        }
    }

    private companion object {
        /** 展示块（文字段落/图片）之间的纵向间距，还原改动前段落之间的视觉留白。 */
        const val BLOCK_SPACING_DP = 12
        const val MAX_ZOOM_MULTIPLIER = 4f
    }
}
