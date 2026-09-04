package app.pdfreader.extract

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.nio.FloatBuffer

/**
 * 一个 CV 版面检测出来的区域，坐标是**喂给模型的那张 [Bitmap] 自己的像素坐标系**
 * （左上角原点，Y 向下）——不是 PDF 坐标系（左下角原点，Y 向上，见 [TableRegion]）。
 * 换算成 PDF 坐标是调用方的事（模型不知道页面渲染用的是多少 DPI），这一层只管
 * "模型说这块像素区域是什么"这件纯几何事实，跟 [TableGridDetector] 的
 * [TableRegion] 同一个设计取舍——方便脱离真机/ONNX 单独做 TDD。
 */
data class LayoutRegion(val label: String, val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * v0.3.0 路线 B（见 `V0.3-DECISION.md`）：CV 版面检测，PP-DocLayout-M（百度
 * PaddleOCR/PaddleX 生态，Apache-2.0，`inference.yml` 2026-09-04 核实，社区转换的
 * ONNX 版本，转换仓库 `GreatV/oar-ocr` 同样 Apache-2.0）——用来补现有几何规则
 * （[TableGridDetector]）漏检的表格：无内部网格线、纯空白对齐这类表格，几何规则
 * 测不出来，会连带正文一起整页栅格化（丢失重排/调字号能力，见项目 CLAUDE.md
 * "已知局限"一节）。
 *
 * 模型规格全部来自官方 `inference.yml`
 * （https://huggingface.co/PaddlePaddle/PP-DocLayout-M/resolve/main/inference.yml，
 * 2026-09-04 核实），不是网上流传的社区推理脚本——那份脚本写的是 800×800、三个
 * 输入（多一个 `im_shape`），拿 Python `onnx` 包内省这次实际下载的模型文件后确认
 * 对不上（实际是 640×640、两个输入），已改为以真实模型文件为准，两者不一致的
 * 完整排查过程见 `DocLayoutOnnxProbeTest` 2026-09-04 那次真机探针：
 * - 输入 `image`：[1,3,640,640] float32，`Resize(640,640,keep_ratio=false)` 直接
 *   缩放（不保长宽比）→ 除以 255 → ImageNet 均值/方差
 *   `mean=[0.485,0.456,0.406]` `std=[0.229,0.224,0.225]` 归一化 → HWC 转 CHW。
 * - 输入 `scale_factor`：[1,2] float32，`[640/原图高, 640/原图宽]`，网络内部的
 *   NMS 算子用它把输出框坐标换算回原图（喂给模型的那张 bitmap）像素坐标——
 *   2026-09-04 真机探针实测验证过这个换算是对的（bbox 数值落在原图像素范围内，
 *   没有需要调用方再次换算的残留偏移）。
 * - 输出 `fetch_name_0`：[100,6]，每行 `[cls_id, score, x1,y1,x2,y2]`，**固定
 *   100 行**（第一版探针 KDoc 曾经猜第二个输出是"有效检测框数"，真机实测证实
 *   是错的——那其实是每类固定 TopK 槽位数，不是过了阈值的真实检测数，已经在
 *   探针那次改正，这里直接采用正确结论：不用第二个输出，[parseDetections] 自己
 *   按 `score` 过滤）。
 * - 23 个类别标签顺序照抄 `inference.yml` 的 `label_list`，索引即 `cls_id`。
 * - `draw_threshold: 0.5` 是官方推荐阈值，模型自己不做这层过滤，调用方必须
 *   自己按这个阈值筛——[DRAW_THRESHOLD]。
 *
 * 2026-09-04 真机探针（骁龙 8+ Gen 1）：session 创建约 100ms（一次性），单页
 * 推理稳态约 305ms——**不便宜**，不能对每一页都无条件跑一次，调用方需要设计
 * "什么时候才触发"的策略（只在现有几何规则本身已经要整页栅格化兜底的页面上
 * 触发，见 `V0.3-DECISION.md` 第 8 节），这一层本身不做任何节流决策。
 *
 * ONNX 推理依赖真实 Android native 库（`onnxruntime-android`），[detect] 本身
 * 不能用 Robolectric 测——跟 [Jpeg2000Decoder] 同一个约束，测试拆两层：
 * [parseDetections]/[scaleFactorFor]/[preprocessToChw] 是纯 Kotlin/纯 [Bitmap]
 * 像素操作（2026-09-04 实测确认 Robolectric 4.16.1 能真的做像素级 [Bitmap]
 * 操作，不需要像 JPX 那样整段推到 instrumented test），用 `LayoutDetectorTest`
 * 覆盖；真正的 [detect] 端到端行为用 `LayoutDetectorInstrumentedTest`（真机）。
 */
class LayoutDetector private constructor(
    private val session: OrtSession,
    private val env: OrtEnvironment,
) : AutoCloseable {

    /**
     * 对 [bitmap] 跑一次版面检测，失败（native 库异常、内存不足等）时返回空列表，
     * 不抛异常——调用方据此判断"CV 没帮上忙"，退回现有的整页栅格化兜底，这条
     * 路径永远不应该比现有行为更差，见类 KDoc"节流"一节。
     */
    fun detect(bitmap: Bitmap, threshold: Float = DRAW_THRESHOLD): List<LayoutRegion> = runCatching {
        val chw = preprocessToChw(bitmap)
        val scaleFactor = scaleFactorFor(bitmap.width, bitmap.height)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong())).use { imageTensor ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(scaleFactor), longArrayOf(1, 2)).use { scaleTensor ->
                session.run(mapOf("image" to imageTensor, "scale_factor" to scaleTensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val rawBoxes = result.get("fetch_name_0").get().value as Array<FloatArray>
                    parseDetections(rawBoxes, threshold)
                }
            }
        }
    }.getOrDefault(emptyList())

    override fun close() = session.close()

    companion object {
        const val MODEL_INPUT_SIZE = 640
        const val DRAW_THRESHOLD = 0.5f
        private const val MODEL_ASSET_NAME = "pp-doclayout-m.onnx"
        private val NORMALIZE_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val NORMALIZE_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /** 顺序照抄 `inference.yml` 的 `label_list`，索引即模型输出的 `cls_id`。 */
        val LABEL_LIST = listOf(
            "paragraph_title", "image", "text", "number", "abstract", "content",
            "figure_title", "formula", "table", "table_title", "reference",
            "doc_title", "footnote", "header", "algorithm", "footer", "seal",
            "chart_title", "chart", "formula_number", "header_image",
            "footer_image", "aside_text",
        )

        /**
         * 加载模型、建 [OrtSession]，失败（比如这台设备的 ABI 不支持 native 库）
         * 就返回 `null`——调用方把这当成"这台设备用不了 CV 检测"，退回现有的
         * 纯几何规则，不崩溃、不阻塞阅读。session 建一次可以反复调用 [detect]
         * （见类 KDoc"耗时"一节，session 创建本身要 100ms 左右，不要每次检测
         * 都重新 open 一次）。
         */
        fun open(context: Context): LayoutDetector? = runCatching {
            val env = OrtEnvironment.getEnvironment()
            val appContext = context.applicationContext ?: context
            val modelBytes = appContext.assets.open(MODEL_ASSET_NAME).use { it.readBytes() }
            val session = env.createSession(modelBytes)
            LayoutDetector(session, env)
        }.getOrNull()

        /**
         * `fetch_name_0` 原始 [100,6] 输出（每行 `[cls_id, score, x1,y1,x2,y2]`）
         * 按 [threshold] 过滤、`cls_id` 映射成标签。**不依赖第二个输出**（见类
         * KDoc"输出"一节，那不是有效检测数）。`cls_id` 超出 [LABEL_LIST] 范围
         * （理论上不会发生，模型固定 23 类，防御性处理）的行直接丢弃，不猜标签。
         */
        internal fun parseDetections(rawOutput: Array<FloatArray>, threshold: Float): List<LayoutRegion> =
            rawOutput.mapNotNull { row ->
                val clsId = row[0].toInt()
                val score = row[1]
                val label = LABEL_LIST.getOrNull(clsId) ?: return@mapNotNull null
                if (score < threshold) return@mapNotNull null
                LayoutRegion(label, score, row[2], row[3], row[4], row[5])
            }

        /**
         * `[目标边/原图高, 目标边/原图宽]`——官方 `inference.yml` `Resize` 步骤
         * `keep_ratio: false`（直接缩放，不保长宽比），两个维度各自独立换算，
         * 不是同一个整体缩放比例。顺序（先高后宽）跟 `inference.yml` 里
         * `target_size: [640, 640]` 的 `[h, w]` 惯例一致，2026-09-04 真机探针
         * 已经验证过这个顺序换算出的坐标落在原图像素范围内。
         */
        internal fun scaleFactorFor(bitmapWidth: Int, bitmapHeight: Int): FloatArray =
            floatArrayOf(MODEL_INPUT_SIZE.toFloat() / bitmapHeight, MODEL_INPUT_SIZE.toFloat() / bitmapWidth)

        /**
         * `inference.yml` 的 `Resize→NormalizeImage→Permute` 三步：直接缩放到
         * [MODEL_INPUT_SIZE]×[MODEL_INPUT_SIZE]（不保长宽比）→ 每个通道
         * `(像素/255 - mean) / std` → HWC 转 CHW，返回长度
         * `3×[MODEL_INPUT_SIZE]×[MODEL_INPUT_SIZE]` 的一维数组（R 平面在前，
         * 依次 G、B）。
         */
        internal fun preprocessToChw(bitmap: Bitmap): FloatArray {
            val size = MODEL_INPUT_SIZE
            val resized = Bitmap.createScaledBitmap(bitmap, size, size, true)
            val pixels = IntArray(size * size)
            resized.getPixels(pixels, 0, size, 0, 0, size, size)

            val chw = FloatArray(3 * size * size)
            val plane = size * size
            for (i in pixels.indices) {
                val p = pixels[i]
                chw[i] = (Color.red(p) / 255f - NORMALIZE_MEAN[0]) / NORMALIZE_STD[0]
                chw[plane + i] = (Color.green(p) / 255f - NORMALIZE_MEAN[1]) / NORMALIZE_STD[1]
                chw[2 * plane + i] = (Color.blue(p) / 255f - NORMALIZE_MEAN[2]) / NORMALIZE_STD[2]
            }
            if (resized !== bitmap) resized.recycle()
            return chw
        }
    }
}
