package app.pdfreader.extract

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.FloatBuffer

/**
 * v0.3.0 路线 B（见 `V0.3-DECISION.md`）可行性探针——**不是长期回归测试**。
 * 只回答两个问题：PP-DocLayout-M 在目标机型（小米 23013RK75C / 骁龙 8+ Gen 1）
 * 上单页推理够不够快、bbox 输出对已知局限场景（表格与正文混排）够不够准。
 * 探针跑完看结论：路线 B 不继续就删掉这个文件+依赖+模型资源；继续就把这里
 * 验证过的预处理/后处理逻辑挪成正式的 `LayoutDetector` 类，重新走 TDD。
 *
 * 模型规格全部来自官方 `inference.yml`
 * （https://huggingface.co/PaddlePaddle/PP-DocLayout-M/resolve/main/inference.yml，
 * 2026-09-04 核实），不是网上找到的社区推理脚本——那份脚本写的是 800×800、
 * 三个输入（多一个 `im_shape`），直接下载这个模型文件用 Python `onnx` 包内省
 * 确认实际是 640×640、两个输入，两者对不上，说明网上的脚本对应的是另一个
 * 变体/版本，不能直接照抄，已改成以这次实际下载的模型文件为准：
 * - 输入 `image`：[1,3,640,640] float32，`Resize(640,640,keep_ratio=false)` 直接
 *   缩放（不保长宽比）→ 除以 255 → 按 ImageNet 均值/方差
 *   `mean=[0.485,0.456,0.406]` `std=[0.229,0.224,0.225]` 归一化 → HWC 转 CHW。
 * - 输入 `scale_factor`：[1,2] float32，`[640/原图高, 640/原图宽]`——网络内部
 *   的 NMS 算子会用这个把输出框坐标换算回原图像素坐标（PaddleDetection 标准
 *   导出约定，**这条是推断，没有拿一张已知精确尺寸的标注图独立验证过换算
 *   结果是否正确**，如果下面打印出来的 bbox 坐标数值明显超出原图像素范围，
 *   说明这个假设错了，需要另外查）。
 * - 输出 `fetch_name_0`：[N,6]，每行 `[cls_id, score, x1,y1,x2,y2]`；
 *   `fetch_name_1`：[1]，**第一版 KDoc 猜这是"有效检测框数"，真机实测发现是错的**——
 *   每次都固定是 100（跟输出名字里的 `TopK_619_o0__d0` 对上了：这是每类固定 TopK
 *   槽位数，不是过了置信度阈值的真实检测数），2026-09-04 真机数据实测纠正。真正
 *   该用的过滤信号是 `score`，官方 `inference.yml` 里 `draw_threshold: 0.5` 就是
 *   推荐阈值，模型自己不做这层过滤，调用方必须自己按这个阈值筛。
 * - 23 个类别标签顺序照抄 `inference.yml` 的 `label_list`，索引即 `cls_id`。
 *
 * 模型文件（`pp-doclayout-m.onnx`，23MB）不入库（见 `.gitignore`），下载命令：
 * `curl -L -o app/src/androidTest/assets/pp-doclayout-m.onnx
 * https://github.com/GreatV/oar-ocr/releases/download/v0.3.0/pp-doclayout-m.onnx`
 * （社区转换自官方 PaddlePaddle 权重，转换仓库 Apache-2.0，2026-09-04 核实
 * 链接有效）。测试用的 PDF 是已有 fixture `sample-with-table.pdf`
 * （`PdfTextExtractorTableTest` 同一份：一段说明文字 → 3列4行表格 → 一段说明
 * 文字），正好命中路线 B 要解决的"表格与正文混排"这条已知局限，不需要额外
 * 找真实问题页。
 */
@RunWith(AndroidJUnit4::class)
class DocLayoutOnnxProbeTest {

    private val labelList = listOf(
        "paragraph_title", "image", "text", "number", "abstract", "content",
        "figure_title", "formula", "table", "table_title", "reference",
        "doc_title", "footnote", "header", "algorithm", "footer", "seal",
        "chart_title", "chart", "formula_number", "header_image",
        "footer_image", "aside_text",
    )

    private fun readAsset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    private fun renderFixturePage(): Bitmap {
        val pdfBytes = readAsset("sample-with-table.pdf")
        val tempFile = File.createTempFile("probe", ".pdf", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        tempFile.writeBytes(pdfBytes)
        return ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderer.openPage(0).use { page ->
                    // 跟正文 TABLE_PAGE_RENDER_DPI 同一个值（PdfTextExtractor.kt），保持
                    // 这个探针喂给模型的图跟正式栅格化路径分辨率一致，结果才有参考意义。
                    val dpi = 220f
                    val scale = dpi / 72f
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }

    /** 官方 `inference.yml` 的 Resize→NormalizeImage→Permute 三步，直接照抄参数。 */
    private fun preprocess(bitmap: Bitmap): FloatBuffer {
        val size = 640
        val resized = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        resized.getPixels(pixels, 0, size, 0, 0, size, size)

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val chw = FloatArray(3 * size * size)
        val plane = size * size
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p) / 255f
            val g = Color.green(p) / 255f
            val b = Color.blue(p) / 255f
            chw[i] = (r - mean[0]) / std[0]
            chw[plane + i] = (g - mean[1]) / std[1]
            chw[2 * plane + i] = (b - mean[2]) / std[2]
        }
        if (resized !== bitmap) resized.recycle()
        return FloatBuffer.wrap(chw)
    }

    @Test
    fun 单页推理耗时与bbox输出人眼核对() {
        val pageBitmap = renderFixturePage()
        val origWidth = pageBitmap.width
        val origHeight = pageBitmap.height
        Log.i("DocLayoutProbe", "原图尺寸: ${origWidth}x$origHeight")

        val env = OrtEnvironment.getEnvironment()
        val modelBytes = readAsset("pp-doclayout-m.onnx")

        val tCreateStart = System.currentTimeMillis()
        val session = env.createSession(modelBytes)
        val tCreateEnd = System.currentTimeMillis()
        Log.i("DocLayoutProbe", "session 创建耗时: ${tCreateEnd - tCreateStart}ms")

        val imageData = preprocess(pageBitmap)
        val scaleFactorData = floatArrayOf(640f / origHeight, 640f / origWidth)

        fun runOnce(): Pair<Array<FloatArray>, Int> {
            imageData.rewind()
            val imageTensor = OnnxTensor.createTensor(env, imageData, longArrayOf(1, 3, 640, 640))
            val scaleTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(scaleFactorData), longArrayOf(1, 2))
            imageTensor.use { img ->
                scaleTensor.use { sf ->
                    session.run(mapOf("image" to img, "scale_factor" to sf)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val boxes = result.get("fetch_name_0").get().value as Array<FloatArray>
                        val countRaw = result.get("fetch_name_1").get().value
                        val validCount = when (countRaw) {
                            is IntArray -> countRaw[0]
                            is Int -> countRaw
                            else -> boxes.size
                        }
                        return boxes to validCount
                    }
                }
            }
        }

        // 第一次跑包含图执行计划优化，第二次是稳态耗时——两个都记录，因为"打开一本
        // 新书第一页"和"后续翻页"分别对应这两种场景，不能只看一个数字。
        val (_, _) = runOnce()
        val tRunStart = System.currentTimeMillis()
        val (boxes, validCount) = runOnce()
        val tRunEnd = System.currentTimeMillis()
        Log.i("DocLayoutProbe", "稳态单页推理耗时: ${tRunEnd - tRunStart}ms, 有效检测框数: $validCount")

        val drawThreshold = 0.5f // inference.yml 官方推荐阈值，见类 KDoc
        val allDetections = (0 until validCount).map { i ->
            val row = boxes[i]
            val clsId = row[0].toInt()
            val label = labelList.getOrElse(clsId) { "未知($clsId)" }
            Triple(label, row[1], row)
        }
        allDetections.forEach { (label, score, row) ->
            Log.i("DocLayoutProbe", "label=$label score=${"%.3f".format(score)} bbox=[${row[2].toInt()},${row[3].toInt()},${row[4].toInt()},${row[5].toInt()}]")
        }
        val confident = allDetections.filter { it.second >= drawThreshold }
        Log.i("DocLayoutProbe", "===过 draw_threshold=$drawThreshold 阈值的检测（共${confident.size}个）===")
        confident.forEach { (label, score, row) ->
            Log.i("DocLayoutProbe", "confident label=$label score=${"%.3f".format(score)} bbox=[${row[2].toInt()},${row[3].toInt()},${row[4].toInt()},${row[5].toInt()}]")
        }

        session.close()
        pageBitmap.recycle()

        assertTrue("过阈值后应该至少有一个可信检测（本页含表格，至少该测出 table 或 text）", confident.isNotEmpty())
        assertTrue(
            "过阈值的 bbox 坐标不应超出原图像素范围（原图 ${origWidth}x$origHeight），" +
                "超出说明 scale_factor 换算回原图坐标的假设是错的",
            confident.all { (_, _, row) ->
                row[2] >= -50 && row[4] <= origWidth + 50 && row[3] >= -50 && row[5] <= origHeight + 50
            },
        )

        // 这是这次探针真正要验证的能力：路线 B 的核心卖点是"表格能被单独框出来，不用
        // 靠整页栅格化"——2026-09-04 真机实测这个 fixture 确实测出 table score=0.943，
        // 锁进断言里，以后换模型版本/预处理参数时如果这个能力退化能立刻发现。
        val tableDetections = confident.filter { it.first == "table" }
        assertTrue(
            "表格+正文混排页应该检测出至少一个高置信度 table 区域，" +
                "这是路线 B 相对现有几何规则的核心优势",
            tableDetections.isNotEmpty(),
        )
    }
}
