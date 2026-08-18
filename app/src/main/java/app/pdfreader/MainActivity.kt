package app.pdfreader

import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.pdfreader.extract.PdfTextExtractor
import app.pdfreader.reflow.LineWidthEstimator
import app.pdfreader.reflow.reflow
import app.pdfreader.settings.ReaderSettings
import app.pdfreader.settings.ReaderSettingsPreferences
import app.pdfreader.ui.IntentUriResolver
import app.pdfreader.ui.PdfLoadReducer
import app.pdfreader.ui.PdfLoadState
import java.io.File
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/**
 * 唯一的界面：一个"打开 PDF"按钮 + 字号/行距/边距三个 SeekBar + 进度条 + 可滚动文字视图，
 * 串起"选文件 → 抽取（[PdfTextExtractor]）→ 重排（[reflow]）→ 显示"这条链路，并支持
 * 阅读设置连续调节、即时生效、重启后保持。
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
 * ## 字号调节的核心逻辑：拖动字号/边距要重新 reflow，不是重新抽取
 *
 * [PdfTextExtractor.extractParagraphs] 是"从 PDF 文件字节流里解析文字"，很慢（要解析
 * PDF 结构、字体映射），而且需要重新打开原始文件——[copyToCacheFile] 拷贝出来的临时
 * 文件在抽取完成后就删掉了，SAF 返回的 content:// Uri 理论上还能再读一次，但没必要：
 * [reflow] 需要的输入是"阅读顺序正确的段落列表"，这份数据在第一次抽取成功后不会因为
 * 字号变化而改变，改变的只是"每行能放几个字符"。所以 [loadPdf] 成功后把段落缓存在
 * [currentParagraphs] 里，字号/边距变化时只调用 [reflowCurrentParagraphs]——重新算一次
 * [LineWidthEstimator.estimate]，用新行宽重新跑一次 [reflow]，不重新解析 PDF 文件。
 * 这比"重新抽取"更快，也不依赖 content:// Uri 在 App 生命周期内可重复打开这个不一定
 * 稳妥的假设。
 *
 * ## 拖动防抖：拖动中只改字号外观，松手才重排
 *
 * [SeekBar.OnSeekBarChangeListener.onProgressChanged] 在拖动过程中会高频触发（每移动
 * 一点像素就回调一次）。如果每次回调都触发一次完整的 [reflowCurrentParagraphs]
 * （分配一个新线程、遍历全部段落重新贪心换行、切回主线程刷新一个可能几千行的
 * TextView），拖动手感会明显卡顿。所以拖动过程中只做两件轻量的事：调用
 * `contentText.setTextSize`/`setPadding`/`setLineSpacing` 立即看到外观变化（这些都是
 * View 系统内部的度量+重绘，本身有节流，不会卡），以及更新数值 Label 的文字。真正的
 * 重排逻辑挪到 [SeekBar.OnSeekBarChangeListener.onStopTrackingTouch]（松手那一刻，
 * 整个拖动过程只触发一次），这是 Android 官方 SeekBar 就自带的"防抖"信号，不需要自己
 * 写计时器/Handler.postDelayed 这类防抖代码。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var openButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var contentText: TextView

    private lateinit var fontSizeLabel: TextView
    private lateinit var fontSizeSeekBar: SeekBar
    private lateinit var lineSpacingLabel: TextView
    private lateinit var lineSpacingSeekBar: SeekBar
    private lateinit var paddingLabel: TextView
    private lateinit var paddingSeekBar: SeekBar

    /** 当前生效的阅读设置，onCreate 时从 [ReaderSettingsPreferences] 读，改动即时写回。 */
    private var currentSettings: ReaderSettings = ReaderSettings()

    /** 最近一次成功抽取出的段落，供字号/边距变化时重新 [reflow]，见类注释。 */
    private var currentParagraphs: List<String>? = null

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { loadPdf(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        openButton = findViewById(R.id.openButton)
        progressBar = findViewById(R.id.progressBar)
        contentText = findViewById(R.id.contentText)
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

    private fun loadPdf(uri: Uri) {
        render(PdfLoadState.Loading)
        val lineWidthChars = estimateLineWidthChars()

        thread {
            val result = runCatching {
                val file = copyToCacheFile(uri)
                val paragraphs = try {
                    PdfTextExtractor.extractParagraphs(applicationContext, file)
                } finally {
                    file.delete()
                }
                currentParagraphs = paragraphs
                reflow(paragraphs, lineWidthChars)
            }
            val state = PdfLoadReducer.fromResult(result)
            runOnUiThread { render(state) }
        }
    }

    /**
     * 字号/边距变化（松手那一刻）触发：不重新抽取 PDF，只用新的行宽对已缓存的段落
     * 重新跑一次 [reflow]，见类注释"核心逻辑"一节。
     */
    private fun reflowCurrentParagraphs() {
        val paragraphs = currentParagraphs ?: return
        render(PdfLoadState.Loading)
        val lineWidthChars = estimateLineWidthChars()

        thread {
            val state = PdfLoadReducer.fromResult(runCatching { reflow(paragraphs, lineWidthChars) })
            runOnUiThread { render(state) }
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

    /**
     * 用屏幕宽度和 [contentText] 当前的字体/内边距，估算 reflow 用的"每行字符数"。
     * 核心换算逻辑在 [LineWidthEstimator]（纯函数，见其单元测试），这里只负责用
     * Android API 量出两个输入：可用宽度像素、单字符宽度像素。用一个较宽的 CJK 字符
     * 测宽度，是保守估计（英文字符更窄，实际能容纳的英文字符数会略多于估算值，不会
     * 超宽，只会更保守地提前换行，可接受）。
     */
    private fun estimateLineWidthChars(): Int {
        val usableWidthPx = resources.displayMetrics.widthPixels -
            contentText.paddingLeft - contentText.paddingRight
        val charWidthPx = contentText.paint.measureText("宽")
        return LineWidthEstimator.estimate(usableWidthPx, charWidthPx)
    }

    /** 把 [settings] 应用到 [contentText] 的外观（字号/行距/边距）。 */
    private fun applySettingsToView(settings: ReaderSettings) {
        contentText.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp.toFloat())
        contentText.setLineSpacing(0f, settings.lineSpacingMultiplier)
        val paddingPx = dpToPx(settings.paddingDp)
        contentText.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
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

    private fun setupSeekBarListeners() {
        fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                contentText.setTextSize(TypedValue.COMPLEX_UNIT_SP, progress.toFloat())
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
                contentText.setLineSpacing(0f, multiplier)
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
                contentText.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
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

    private fun render(state: PdfLoadState) {
        when (state) {
            PdfLoadState.Idle -> {
                progressBar.visibility = View.GONE
            }

            PdfLoadState.Loading -> {
                progressBar.visibility = View.VISIBLE
                contentText.text = ""
            }

            is PdfLoadState.Success -> {
                progressBar.visibility = View.GONE
                contentText.text = state.lines.joinToString("\n")
            }

            is PdfLoadState.Error -> {
                progressBar.visibility = View.GONE
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
