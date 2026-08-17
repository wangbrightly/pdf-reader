package app.pdfreader

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.pdfreader.extract.PdfTextExtractor
import app.pdfreader.reflow.reflow
import app.pdfreader.ui.IntentUriResolver
import app.pdfreader.ui.PdfLoadReducer
import app.pdfreader.ui.PdfLoadState
import java.io.File
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/**
 * 唯一的界面：一个"打开 PDF"按钮 + 进度条 + 可滚动文字视图，串起
 * "选文件 → 抽取（[PdfTextExtractor]）→ 重排（[reflow]）→ 显示"这条链路。
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
 */
class MainActivity : AppCompatActivity() {

    private lateinit var openButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var contentText: TextView

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
                try {
                    val paragraphs = PdfTextExtractor.extractParagraphs(applicationContext, file)
                    reflow(paragraphs, lineWidthChars)
                } finally {
                    file.delete()
                }
            }
            val state = PdfLoadReducer.fromResult(result)
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
     * 用屏幕宽度和 [contentText] 的字体，估算 reflow 用的"每行字符数"。
     * [reflow] 用字符数模拟屏幕宽度（见 ReflowTest 的注释），这里用一个较宽的 CJK
     * 字符测宽度，是保守估计（英文字符更窄，实际能容纳的英文字符数会略多于估算值，
     * 不会超宽，只会更保守地提前换行，可接受）。
     */
    private fun estimateLineWidthChars(): Int {
        val usableWidthPx = resources.displayMetrics.widthPixels -
            contentText.paddingLeft - contentText.paddingRight
        val charWidthPx = contentText.paint.measureText("宽")
        if (charWidthPx <= 0f) return DEFAULT_LINE_WIDTH_CHARS
        return (usableWidthPx / charWidthPx).toInt().coerceAtLeast(MIN_LINE_WIDTH_CHARS)
    }

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

    private companion object {
        const val MIN_LINE_WIDTH_CHARS = 10
        const val DEFAULT_LINE_WIDTH_CHARS = 30
    }
}
