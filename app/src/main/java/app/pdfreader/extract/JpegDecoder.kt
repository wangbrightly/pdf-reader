package app.pdfreader.extract

import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * 自己手写的 JPEG 解码器，专门解决 [PdfTextExtractor] 里
 * `decodeCmykJpegOrNull` KDoc 记录的问题：CMYK/YCCK（4 分量）JPEG
 * 在这台设备上无论走 PdfBox-Android 自己的解码还是安卓原生 `BitmapFactory`
 * 都会解出纯黑图片，两条路径都试过，2026-08-23 真机诊断确认过"事后颜色反相"
 * 这条便宜的路子也救不回来——解码这一步本身在更早的阶段就丢失了内容，
 * 唯一剩下的路是自己重新实现一份完整的 JPEG 解码器。
 *
 * ## 范围：只覆盖真机确认过的那种数据形状，不是"通用 JPEG 解码器"
 *
 * 跟 [Jbig2SymbolTextDecoder] 同样的方法论——"某一份真机数据长什么样"不等于
 * "这个格式所有可能的变体"，这次也严格按"观察到的真实数据形状"限定范围，
 * 碰到范围外的情况直接返回 `null`，不猜、不冒险给出可能错误的解码结果：
 *
 * 1. **只处理 4 分量数据**，且必须带 Adobe APP14 标记、`transform=0`（直接存
 *    CMYK）或 `transform=2`（YCCK，先做一次 YCbCr→RGB 再转 CMYK 那种变体，
 *    2026-08-24 补上，见下面"YCCK 支持"一节）。没有 Adobe 标记的 4 分量数据
 *    （既不是 transform=0 也不是 transform=2 这种约定）没有实现——没在真机
 *    数据里见过这种情况，不确定就不处理。
 * 2. **色度采样比例支持 1~4 之间任意组合（2026-08-24 补上，见下面"色度
 *    子采样支持"一节）**——2026-08-24 真机数据（那份年报 97 张 CMYK 图片里
 *    93 张）确认色度子采样在 YCCK 场景下其实很常见（不像最初以为的"CMYK
 *    没有子采样的理由"，那条判断只对 transform=0 直接存 CMYK 成立，YCCK
 *    本质是 YCbCr+K，YCbCr 部分完全可能像普通照片 JPEG 一样做子采样）。
 *    只对不合法的采样比例（不在 1~4 范围，JPEG 标准里 Hi/Vi 的合法范围）
 *    返回 `null`。
 * 3. **只处理 baseline（SOF0）/ extended sequential（SOF1）单扫描**——渐进式
 *    JPEG（SOF2，频谱选择+逐次逼近）编码结构复杂得多（同一张图片分多次扫描，
 *    每次只传一部分频率系数），真机数据没见过（没有反过来的证据能证明这台
 *    设备的 CMYK JPEG 一定不是渐进式，只是目前没见过），遇到直接返回 `null`，
 *    不猜。
 *
 * 支持 8 位精度、任意宽高（不要求是 8 的倍数，最后一行/列的块按实际边界裁剪）、
 * 重启间隔（DRI/RSTn 标记，扫描图片常见）。
 *
 * ## 核心机制：两种存储约定并存，逐图投票决定反不反色（装机抓出的真坑）
 *
 * Adobe 系工具（Photoshop、专业印刷软件）存 CMYK JPEG 时，四个通道的采样值是
 * **反过来存的**：存的值 = 255 − 真实值，这是 Adobe APP14 标记约定的标准做法。
 * 但 2026-08-23 装机验证发现用户的教科书（印刷行业扫描数据）虽然**同样带
 * Adobe APP14 `transform=0` 标记**，存的却是**不反色**的真实 CMYK 值——不符合
 * 约定但真实存在。按反色约定解这种数据整幅纯黑（真机上用户看到的就是这个），
 * 而且所有按 Adobe 约定实现的库（Pillow/libjpeg、Skia）都会犯同样的错，所以
 * 逐像素比对第三方参考**测不出这个 bug**——参考实现自己就解错了。
 *
 * 标记层面没有任何信号能区分两种约定（都长一样），只能看内容：逐图对"默认
 * 朝向"的 C/M/Y/K 采样值投票——偏亮（四通道和 > 700 的采样多于偏暗的）按反色
 * 约定解；偏暗按不反色解。推导过四种组合（两种约定 × 内容偏白/偏暗）这条规则
 * 全部给出正确画面：偏白内容投票必然选对它自己的约定；偏暗内容即使选错约定，
 * 解出来也还是偏暗（错约定下偏暗内容的解码结果恰好不变）。扫描/印刷页面
 * 绝大部分是白底，这条规则在这类数据上等于"永远选对"。
 *
 * 这套投票只覆盖 transform=0——transform=2（YCCK）**不投票，固定整体反色**，
 * 见下面"YCCK 支持"一节最后一段的完整教训（2026-08-25）。
 *
 * 反色之后是 CMYK→RGB 转换：
 * ```
 * R = (255 − C) × (255 − K) / 255
 * G = (255 − M) × (255 − K) / 255
 * B = (255 − Y) × (255 − K) / 255
 * ```
 * **2026-08-24 修正**：原来这里写的是加法公式 `R = 255 − min(255, C + K)`，
 * 当时的注释断言"这是行业惯例"——这个断言没有真正验证过，K 值低的测试
 * fixture 让加法/乘法两条公式的差异小到被现有 10 容差的交叉验证盖过去了。
 * 装机验证 YCCK 支持时用高 K 值的真机数据（K=107~220）重新核对 Pillow
 * （libjpeg-turbo）的输出，才发现 Pillow 用的其实是乘法公式，加法公式在
 * 高 K 值区域最大单通道差超过 60——不是"够用的朴素近似"，是算错了。换成
 * 乘法公式后所有 transform=0 的既有交叉验证 fixture 依然通过（K 值本来就低，
 * 两条公式该有的差异这次真的测出来是"没差异"，不是"没测到差异"）。
 *
 * ## YCCK 支持（transform=2，2026-08-24 补上）
 *
 * YCCK 数据的分量 0/1/2 是 Y/Cb/Cr（不是直接的 C/M/Y），分量 3 是 K。换算
 * 公式是拿真机数据（那份年报的 `cmyk-ycck-book.jpg`）反推出来的，**不是**
 * 教科书 libjpeg 源码里那条公式（`C=255−R`、K 不变）——两处符号都相反：
 * ```
 * R = Y + 1.402 × (Cr−128)              C = round(R)   （不是 255−R）
 * G = Y − 0.344136×(Cb−128) − 0.714136×(Cr−128)   M = round(G)
 * B = Y + 1.772 × (Cb−128)              Yellow = round(B)
 * K_true = 255 − K                      （K 要反色，libjpeg 教科书公式里 K 不变）
 * ```
 * 3 个真机采样点用 Pillow 的 CMYK 模式直接读出的 `(C,M,Y,K)` 真值核对过，
 * 整数级精确匹配。当时的假设是"YCCK 不像 transform=0 CMYK 那样有'反色/不
 * 反色'两种存储约定并存的问题——`transform` 标记本身已经把换算方式钉死了，
 * 不需要投票"，同时预留了一句话："如果以后遇到另一份 YCCK 文件解出来是
 * 负片效果，说明符号约定不是唯一的，到时候再补检测逻辑"。
 *
 * **2026-08-25 这个预留的反例真机撞上了，而且比预想的严重得多——不是"需要
 * 补投票"，是"当初验证用的参考答案从一开始就是错的"**：
 *
 * 1. 同一份年报里另一张背景图（真机反馈"颜色不对"追出来的，K 通道原始采样
 *    值均值 238.7，接近拉满）用上面这套固定符号解出来几乎是纯黑负片。用
 *    Pillow 单独核对这张图，默认解码同样是负片——**两个独立实现在这张图上
 *    犯了同一个方向的错**，不是"参考实现更可信"就能绕开的坑，跟 transform=0
 *    那次"所有按 Adobe 约定实现的库都会犯同样的错"是同一类教训。PDF 里这张
 *    图的 XObject 字典确认没有 `/Decode` 数组（PDF 规范里能显式声明反色的
 *    地方），排除了"PDF 自己说清楚了、只是没读"这种可能；直方图拉伸验证过
 *    负片版本和正常版本是同一份图像数据、只是整体反色，不是解码结构性出错。
 * 2. 顺着这条线索，回头重新核对了当初用来反推这套符号、且被当成"已验证"
 *    的 `cmyk-ycck-book.jpg`——**这份 fixture 当年的参考答案（Pillow 默认
 *    解码）同样是错的**：默认解码是一团模糊的褐绿色污渍，手动把 Pillow 的
 *    四个通道整体反色之后，是一张清晰的蓝紫色"数字光效数据高速路"设计图，
 *    跟这份年报里其它设计页的美术风格完全一致，肉眼判断不存在任何歧义。
 *    `cmyk-ycck-subsampled.jpg` 同样中招——真机把这份年报第 5 页那组 7 张
 *    颁奖照片拉下来逐张核对（用页面级渲染工具 poppler 独立渲染整页作对照，
 *    不再依赖 Pillow 单张解码），这张 fixture 原来就是那 7 张里的一张，7 张
 *    里全部 7 张都需要整体反色，`cmyk-ycck-subsampled.jpg` 也不例外。
 * 3. 结论：**当天新查的 11+ 张真机 YCCK 图片（背景设计图、真人颁奖照混合），
 *    没有一张需要"不反色"**，包括两份原来被误判成"不反色"的旧 fixture。
 *    "YCCK 需要投票才能两边都覆盖"这个 2026-08-25 当天早些时候的设计（3 倍
 *    优势门槛）站不住——它是拿两个错误的参考答案校准出来的阈值，凑巧能让
 *    错误的旧 fixture 通过，而不是真的发现了"YCCK 也有双约定"。回退成固定
 *    整体反色（`invert = true`，不投票），比投票机制更简单，也更符合目前
 *    掌握的全部真实证据——如果以后真的找到一份验证充分（不是简单信一次
 *    Pillow 默认输出）的、确实不需要反色的 YCCK 真机样本，再重新考虑要不要
 *    加回投票，这次没有这样的样本，不预先加没有证据支撑的复杂度。
 * 4. 方法论教训：**"跟成熟第三方库的输出比对"不能替代"肉眼/独立工具核实
 *    这个输出本身对不对"**——这次连续两次被同一类问题绊倒（这一节第 1
 *    点、[JpegDecoderCrossValidationTest] 全篇"参考实现的选择"一节记录的
 *    TwelveMonkeys 那次），根源都是"该库处理这类小众数据没问题"这个假设
 *    没有独立验证过。这次改用 `pdftoppm`/poppler 整页渲染做交叉验证——
 *    poppler 是完全独立于 Pillow/libjpeg-turbo 调用路径的另一套实现，两者
 *    同时出错的概率低得多，配合"这批图片美术风格高度一致、肉眼能判断"这个
 *    额外信号，才敢确定原来的参考答案是错的。
 *
 * ## 色度子采样支持（2026-08-24 补上）
 *
 * 真机那份年报 97 张 CMYK 图片里 93 张（96%）采样比例是
 * `[(0,2,2),(1,1,1),(2,1,1),(3,2,2)]`——分量 0（Y）和分量 3（K）是全分辨率
 * （Hmax=2,Vmax=2 这个基准），分量 1/2（Cb/Cr）是 4:2:0 子采样（标准 JPEG
 * 照片最常见的子采样比例，不是这份文件特有的）。MCU（Minimum Coded Unit）
 * 覆盖 `8×Hmax × 8×Vmax` 像素，采样比例低的分量在一个 MCU 里只贡献
 * `Hi×Vi` 个块（子采样分量块少，全分辨率分量块多），每个分量按自己的
 * 采样比例单独开一份 plane（不再强行按统一尺寸分配，子采样分量的 plane
 * 天然更小，顺带比"先都按满分辨率分配再降采样"更省内存）。
 *
 * 子采样分量在最终图像坐标系下取样时用**最近邻**（整数除法
 * `x*Hi/Hmax`），不是双线性插值——JPEG 解码器"快速"色度上采样的常见做法，
 * 跟本类一贯"够用就行、不追求更精细"的尺度一致。真机 fixture 交叉验证
 * 平均像素差只有 1.44（对比 libjpeg-turbo 的"精细"上采样），只有色彩剧烈
 * 变化的交界处（0.15% 像素）有肉眼可辨的差异，大片纯色/渐变区域结果
 * 几乎一致，见 [JpegDecoderCrossValidationTest] 里带子采样的那条测试。
 *
 * ## 已知局限（如实告知，别在测试之外假装能处理）
 *
 * - 不支持渐进式 JPEG（SOF2）
 * - 不支持没有 Adobe 标记的 4 分量数据
 * - 不支持算术编码（只支持 Huffman 熵编码，JPEG 里的算术编码变体极少见）
 * - 不支持 12 位精度（只支持 8 位，绝大多数 JPEG 是这个精度）
 * - 色度子采样的上采样是最近邻，不是双线性/更精细的滤波器，色彩剧烈变化的
 *   交界处会有小幅可辨差异（见上面"色度子采样支持"一节的真机验证数据）
 *
 * 命中以上任何一条，[decode] 返回 `null`，调用方（[PdfTextExtractor]）应该
 * 降级到诚实的占位图，不展示错误内容。
 */
internal object JpegDecoder {

    /** 解码结果：ARGB 像素数组，行主序，跟 [android.graphics.Bitmap.createBitmap] 的 IntArray 构造函数直接兼容。 */
    data class DecodedImage(val width: Int, val height: Int, val argb: IntArray)

    private val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10,
        17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34,
        27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36,
        29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46,
        53, 60, 61, 54, 47, 55, 62, 63,
    )

    /**
     * 2026-08-24 真机撞出的 OOM 修复：这个解码器同时把 4 个分量各存一份跟原图
     * 等分辨率的 `IntArray`（[planes]，每分量 4 字节/像素）+ 最终 [DecodedImage.argb]
     * 又是一份等分辨率 `IntArray`——巅峰同时存活的内存约等于
     * `20 字节/像素 × 像素数`（4 个分量 plane 共 16 字节/像素 + argb 4 字节/像素，
     * `Bitmap` 自己的原生缓冲区另算）。真机装机验证时一本教科书封面的
     * 4469×3871（约 1730 万像素）CMYK 图片直接把这台设备 256MB 堆占爆，
     * `Throwing OutOfMemoryError "Failed to allocate a 95744012 byte
     * allocation..."`——不是"格式不支持"，是真的内存不够，只是异常被
     * [decode] 的 `runCatching` 接住后误导性地显示成占位图"格式不支持"文案。
     *
     * 这里按同一次真机 session 里的两个实测数据点定阈值：1795×1181（约 212 万
     * 像素）反复解码多次都正常，4469×3871（约 1730 万像素）稳定 OOM——最初取
     * 400 万像素（约等于 2000×2000）。
     *
     * **2026-08-25 真机复测发现这个阈值定低了，调到 600 万**：那份年报里 19 张
     * 图片（人物合影等）全部落在 2536×1812～2547×1818 这个窄区间（约 460-463
     * 万像素）——都是完全可以安全解码的真实内容，却被 400 万的阈值一刀切成了
     * 占位图，是这次"图片显示不正常"反馈的真正根因（不是解码器本身有 bug，
     * 是安全阀比实际能承受的范围收得太紧）。460 万像素按 20 字节/像素峰值内存
     * 估算约 92MB，离两个原始数据点中"确认安全"的 212 万像素（约 42MB）不远，
     * 离"确认 OOM"的 1730 万像素（约 346MB）差了近 4 倍，之前把阈值卡在两者
     * 中间偏保守的一侧，缺的是"真实文档常见图片能有多大"这个数据点，这次真机
     * 数据补上了。调到 600 万像素（约 120MB 估算峰值），覆盖这批 460 万像素的
     * 真实图片、留出约 30% 余量，仍然只是"确认 OOM"阈值的三分之一多一点，
     * 装机复测过这 19 张全部正常解码、没有触发 OOM。
     *
     * 超过这个阈值直接拒绝解码、退回占位图——跟本类"范围外数据一律返回
     * null"的一贯降级精神一致，比冒 OOM 崩溃风险划算。真正的解决办法是把
     * `planes` 从 `IntArray` 换成占内存更小的存储、或者实现按需降采样解码
     * （工作量高一个级别，这次没做，见 NOTES.md #34）。这次调阈值不是"猜一个
     * 更大的数字糊弄过去"——如果以后再遇到"图片过大"占位图变多的真机反馈，
     * 先查真实图片像素数落在哪个区间，别直接再调大这个常量。
     */
    internal const val MAX_CMYK_JPEG_PIXELS = 6_000_000

    fun decode(bytes: ByteArray): DecodedImage? = runCatching { decodeInternal(bytes) }.getOrNull()

    private fun decodeInternal(bytes: ByteArray): DecodedImage? {
        val frame = MarkerParser(bytes).parse() ?: return null
        if (frame.precision != 8) return null
        if (frame.components.size != 4) return null
        // 见类 KDoc"色度子采样支持"一节——采样比例限定在 1~4（JPEG 标准里
        // Hi/Vi 的合法范围本来就是 1~4），避免畸形/伪造数据让 MCU 尺寸失控。
        if (frame.components.any { it.samplingH !in 1..4 || it.samplingV !in 1..4 }) return null
        if (frame.sofMarker != 0xC0 && frame.sofMarker != 0xC1) return null
        // 见类 KDoc"YCCK（transform=2）支持"一节——transform=0（直接存 CMYK）
        // 和 transform=2（YCCK，先做 YCbCr 变换再存）两种约定都收，色彩换算
        // 路径在下面按 adobeTransform 分叉。
        val isYcck = frame.adobeTransform == 2
        if (frame.adobeTransform != 0 && !isYcck) return null
        if (frame.scanComponents.size != 4) return null
        if (frame.width.toLong() * frame.height.toLong() > MAX_CMYK_JPEG_PIXELS) return null

        // 见类 KDoc"色度子采样支持"一节：每个分量的 blocksPerMCU = samplingH×
        // samplingV，MCU 覆盖的像素范围是 8×hMax × 8×vMax（hMax/vMax = 全部
        // 分量里最大的采样比例）。没有子采样（全部 1×1，YCCK 之外的既有场景）
        // 时 hMax=vMax=1，这套通用逻辑退化成原来的"一个块=一个 MCU"，不是
        // 单独分支，同一套代码两种场景都走。
        val hMax = frame.components.maxOf { it.samplingH }
        val vMax = frame.components.maxOf { it.samplingV }
        val mcuWidthPx = 8 * hMax
        val mcuHeightPx = 8 * vMax
        val mcusPerLine = (frame.width + mcuWidthPx - 1) / mcuWidthPx
        val mcusPerColumn = (frame.height + mcuHeightPx - 1) / mcuHeightPx

        // 每个分量按自己的采样比例单独开一份 plane（不再是 4 个分量共用同一个
        // 尺寸）——采样比例低的分量（比如 4:2:0 里的 Cb/Cr）plane 天然更小，
        // 总内存比"强行按满分辨率分配 4 份"更省，不需要因为加了子采样支持就
        // 调高 [MAX_CMYK_JPEG_PIXELS]。
        val compPlaneWidth = IntArray(4) { mcusPerLine * frame.components[it].samplingH * 8 }
        val compPlaneHeight = IntArray(4) { mcusPerColumn * frame.components[it].samplingV * 8 }
        val planes = Array(4) { IntArray(compPlaneWidth[it] * compPlaneHeight[it]) }

        val decodeTables = frame.scanComponents.map { sc ->
            val dc = frame.huffmanTables[HuffKey(0, sc.dcTableId)] ?: return null
            val ac = frame.huffmanTables[HuffKey(1, sc.acTableId)] ?: return null
            val quant = frame.quantTables[frame.components[sc.componentIndex].quantTableId] ?: return null
            Triple(dc, ac, quant)
        }

        val bitReader = EntropyBitReader(bytes, frame.entropyDataStart, frame.entropyDataEnd)
        val dcPredictors = IntArray(4)
        var mcuCount = 0
        val block = IntArray(64)
        val spatial = IntArray(64)

        for (mcuRow in 0 until mcusPerColumn) {
            for (mcuCol in 0 until mcusPerLine) {
                for (ci in frame.scanComponents.indices) {
                    val sc = frame.scanComponents[ci]
                    val comp = frame.components[sc.componentIndex]
                    val (dcTable, acTable, quant) = decodeTables[ci]
                    val plane = planes[sc.componentIndex]
                    val planeW = compPlaneWidth[sc.componentIndex]
                    // 子采样分量在一个 MCU 里贡献 samplingH×samplingV 个块（不是
                    // 1 个）——比如 4:2:0 的 Y/K 在 2×2 采样下每个 MCU 4 个块，
                    // Cb/Cr 1×1 采样下每个 MCU 1 个块，这就是"同一个 MCU 内不同
                    // 分量分辨率不同"的具体体现。
                    for (blockYInMcu in 0 until comp.samplingV) {
                        for (blockXInMcu in 0 until comp.samplingH) {
                            java.util.Arrays.fill(block, 0)
                            val s = dcTable.decode(bitReader) ?: return null
                            val diff = if (s == 0) 0 else extend(bitReader.receive(s), s)
                            val dc = dcPredictors[sc.componentIndex] + diff
                            dcPredictors[sc.componentIndex] = dc
                            block[0] = dc * quant[0]

                            var k = 1
                            while (k < 64) {
                                val rs = acTable.decode(bitReader) ?: return null
                                val run = rs ushr 4
                                val size = rs and 0x0F
                                if (size == 0) {
                                    if (run == 15) {
                                        k += 16 // ZRL：16 个连续 0 系数
                                        continue
                                    }
                                    break // EOB：剩余系数全 0
                                }
                                k += run
                                if (k >= 64) return null
                                val coeff = extend(bitReader.receive(size), size)
                                block[ZIGZAG[k]] = coeff * quant[k]
                                k++
                            }

                            idct8x8(block, spatial)
                            val baseX = (mcuCol * comp.samplingH + blockXInMcu) * 8
                            val baseY = (mcuRow * comp.samplingV + blockYInMcu) * 8
                            for (y in 0 until 8) {
                                val rowOffset = (baseY + y) * planeW + baseX
                                val srcOffset = y * 8
                                for (x in 0 until 8) {
                                    plane[rowOffset + x] = spatial[srcOffset + x]
                                }
                            }
                        }
                    }
                }

                mcuCount++
                if (frame.restartInterval > 0 && mcuCount % frame.restartInterval == 0) {
                    val isLastMcu = mcuRow == mcusPerColumn - 1 && mcuCol == mcusPerLine - 1
                    if (!isLastMcu) {
                        bitReader.alignAndConsumeRestart()
                        java.util.Arrays.fill(dcPredictors, 0)
                    }
                }
            }
        }

        // 子采样分量（比例低于 hMax/vMax 的）在整幅图坐标系下要按比例缩小取样
        // 位置——最近邻取样（整数除法），不是双线性插值：JPEG 解码器里"快速"
        // 色度上采样的常见做法，跟本类其它地方"够用就行、不追求更精细"的一贯
        // 尺度一致，真机 YCCK 数据交叉验证也是照这个假设通过的（见下方测试）。
        fun sample(ci: Int, x: Int, y: Int): Int {
            val comp = frame.components[ci]
            val sx = x * comp.samplingH / hMax
            val sy = y * comp.samplingV / vMax
            return planes[ci][sy * compPlaneWidth[ci] + sx].coerceIn(0, 255)
        }

        // "默认朝向"下的 C/M/Y/K：transform=0 就是原始采样值本身；transform=2
        // （YCCK）是 YCbCr→RGB 那套线性变换的结果当 C/M/Y、K 不反色——见下面
        // "核心机制"一节，这一步只是给投票和最终反色判断提供一个统一的起点，
        // 不代表这就是最终真值。
        fun baseC(x: Int, y: Int): Int = if (isYcck) {
            val yy = sample(0, x, y)
            val cr = sample(2, x, y) - 128
            (yy + 1.402 * cr).roundToInt().coerceIn(0, 255)
        } else {
            sample(0, x, y)
        }
        fun baseM(x: Int, y: Int): Int = if (isYcck) {
            val yy = sample(0, x, y)
            val cb = sample(1, x, y) - 128
            val cr = sample(2, x, y) - 128
            (yy - 0.344136 * cb - 0.714136 * cr).roundToInt().coerceIn(0, 255)
        } else {
            sample(1, x, y)
        }
        fun baseY(x: Int, y: Int): Int = if (isYcck) {
            val yy = sample(0, x, y)
            val cb = sample(1, x, y) - 128
            (yy + 1.772 * cb).roundToInt().coerceIn(0, 255)
        } else {
            sample(2, x, y)
        }
        // transform=0 的 K 就是原始采样值；transform=2（YCCK）K 默认朝向是
        // 255-采样值——原来（反色投票只覆盖 transform=0 之前）YCCK 的 K 固定
        // 用 255-采样值，这里保留同一个默认朝向，让 invert=false 时跟当初只
        // 用一份真机样本反推出来、已经验证过的公式完全一致。
        fun baseK(x: Int, y: Int): Int = if (isYcck) 255 - sample(3, x, y) else sample(3, x, y)

        // 见类 KDoc"核心机制"一节：对原始采样值投票决定这张图按哪种存储约定
        // 解——只对 transform=0（直接存 CMYK）有意义。transform=2（YCCK）
        // 2026-08-25 真机数据证明固定要整体反色（见类 KDoc"YCCK 支持"一节
        // 完整背景），不再投票。按最终图像坐标每隔 8 像素取一票（全图投票
        // 没必要，白底页面几千票就足够稳定，也省时间；按最终坐标取样后
        // 子采样分量也能正确参与投票）。
        var hiVotes = 0
        var loVotes = 0
        if (!isYcck) {
            var vy = 0
            while (vy < frame.height) {
                var vx = 0
                while (vx < frame.width) {
                    val s = baseC(vx, vy) + baseM(vx, vy) + baseY(vx, vy) + baseK(vx, vy)
                    if (s > 700) hiVotes++ else if (s < 300) loVotes++
                    vx += 8
                }
                vy += 8
            }
        }
        val invert = if (isYcck) true else hiVotes >= loVotes

        val width = frame.width
        val height = frame.height
        val argb = IntArray(width * height)
        var idx = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                // 反色约定：默认朝向的值先 255-值 还原真实 C/M/Y/K；不反色约定：
                // 默认朝向的值即真实值。transform=0 和 transform=2 共用同一套
                // 判断，见上面"核心机制"投票一节——不管走哪条 baseX 计算路径，
                // 反色决策统一在这里做，两种 transform 不再分叉处理。
                val realC = if (invert) 255 - baseC(x, y) else baseC(x, y)
                val realM = if (invert) 255 - baseM(x, y) else baseM(x, y)
                val realY = if (invert) 255 - baseY(x, y) else baseY(x, y)
                val realK = if (invert) 255 - baseK(x, y) else baseK(x, y)
                // 2026-08-24 真机数据核实：Pillow（libjpeg-turbo）CMYK→RGB 用的是
                // 乘法公式 (255-C)×(255-K)/255，不是原来这里用的加法公式
                // 255-min(255,C+K)——两者在 K 值低时接近，K 值高时差异明显（真机
                // YCCK 数据有 K=107-220 的像素，加法公式算出的结果比参考解码暗
                // 得多，最大单通道差过 60）。改成乘法公式后跟 Pillow 逐像素比对。
                val r = ((255 - realC) * (255 - realK) / 255)
                val g = ((255 - realM) * (255 - realK) / 255)
                val b = ((255 - realY) * (255 - realK) / 255)
                argb[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                idx++
            }
        }
        return DecodedImage(width, height, argb)
    }

    /** JPEG Annex F.2.2.1 的 EXTEND 过程：把"幅值类别 s + 收到的 s 位原始比特"还原成有符号系数。 */
    private fun extend(v: Int, s: Int): Int {
        if (s == 0) return 0
        val vt = 1 shl (s - 1)
        return if (v < vt) v - (1 shl s) + 1 else v
    }

    // 8x8 IDCT 用的余弦基底表，只算一次。C(0)=1/√2，其余=1；两个轴各分摊一次
    // 1/2 的整体缩放（合起来是标准反变换公式里的 1/4）。这是 ITU-T T.81 Annex
    // A.3.3 里直接给出的反变换公式的直接实现，追求"容易验证对不对"，没有用
    // libjpeg 那种做了代数化简的快速算法（AAN 之类）——正确性优先于性能，这次
    // 处理的是印刷/扫描书籍插图，不是要求实时的场景，见类 KDoc。
    private val IDCT_BASIS: Array<DoubleArray> = Array(8) { x ->
        DoubleArray(8) { u ->
            val cu = if (u == 0) 1.0 / Math.sqrt(2.0) else 1.0
            0.5 * cu * cos((2 * x + 1) * u * Math.PI / 16.0)
        }
    }

    /** [coeffs] 是反量化后的 8x8 系数（行主序，自然顺序不是 zigzag），[out] 收到色阶还原+限幅后的 0-255 样本。 */
    private fun idct8x8(coeffs: IntArray, out: IntArray) {
        val temp = DoubleArray(64)
        // 第一遍：沿列方向（横向频率 u → 横向空间 x），保持行方向（纵向频率）不变。
        for (row in 0 until 8) {
            val rowOffset = row * 8
            for (x in 0 until 8) {
                var sum = 0.0
                for (u in 0 until 8) {
                    val c = coeffs[rowOffset + u]
                    if (c != 0) sum += IDCT_BASIS[x][u] * c
                }
                temp[rowOffset + x] = sum
            }
        }
        // 第二遍：沿行方向（纵向频率 v → 纵向空间 y）。
        for (x in 0 until 8) {
            for (y in 0 until 8) {
                var sum = 0.0
                for (v in 0 until 8) {
                    sum += IDCT_BASIS[y][v] * temp[v * 8 + x]
                }
                val level = Math.round(sum).toInt() + 128
                out[y * 8 + x] = level.coerceIn(0, 255)
            }
        }
    }

    private data class HuffKey(val tableClass: Int, val id: Int)

    private class HuffmanTable(bits: IntArray, private val values: IntArray) {
        // Annex C.2 标准算法：给每个码长构造 MINCODE/MAXCODE/VALPTR，解码时逐比特
        // 增长候选码字，直到落在某个码长的 [MINCODE,MAXCODE] 区间内。
        private val maxCode = IntArray(17) { -1 }
        private val minCode = IntArray(17)
        private val valPtr = IntArray(17)

        init {
            var code = 0
            var k = 0
            for (l in 1..16) {
                val count = bits[l - 1]
                if (count == 0) {
                    maxCode[l] = -1
                } else {
                    valPtr[l] = k
                    minCode[l] = code
                    code += count
                    k += count
                    maxCode[l] = code - 1
                }
                code = code shl 1
            }
        }

        fun decode(reader: EntropyBitReader): Int? {
            var code = reader.nextBit()
            var l = 1
            while (l <= 16 && (maxCode[l] == -1 || code > maxCode[l])) {
                code = (code shl 1) or reader.nextBit()
                l++
            }
            if (l > 16) return null
            val index = valPtr[l] + (code - minCode[l])
            if (index !in values.indices) return null
            return values[index]
        }
    }

    /**
     * 熵编码数据的比特读取器——处理字节填充（`FF 00` 是被转义的字面 `FF`）和
     * 重启标记（`FF D0`-`FF D7`）。数据耗尽时按 0 兜底而不是抛异常/越界，跟本类
     * 其它地方"宁可解码结果轻微跑偏也不崩溃"的一贯风格一致（真机上遇到截断/
     * 损坏数据时不应该让整个解码流程崩掉，外层 [decode] 的 `runCatching` 兜底
     * 是最后一道防线，不是第一道）。
     */
    private class EntropyBitReader(private val data: ByteArray, start: Int, private val end: Int) {
        private var pos = start
        private var currentByte = 0
        private var bitCount = 0

        fun nextBit(): Int {
            if (bitCount == 0) fillByte()
            bitCount--
            return (currentByte ushr bitCount) and 1
        }

        fun receive(s: Int): Int {
            var v = 0
            repeat(s) { v = (v shl 1) or nextBit() }
            return v
        }

        private fun fillByte() {
            if (pos >= end) {
                currentByte = 0
                bitCount = 8
                return
            }
            val b = data[pos].toInt() and 0xFF
            pos++
            if (b == 0xFF && pos < end && (data[pos].toInt() and 0xFF) == 0x00) {
                pos++ // 跳过被转义的填充字节，当前字节仍然是字面的 0xFF。
            }
            currentByte = b
            bitCount = 8
        }

        /** 重启间隔边界：丢弃当前字节里剩余的比特（字节对齐），跳过并消费一个 RSTn 标记。 */
        fun alignAndConsumeRestart() {
            bitCount = 0
            if (pos + 1 < end && (data[pos].toInt() and 0xFF) == 0xFF) {
                val marker = data[pos + 1].toInt() and 0xFF
                if (marker in 0xD0..0xD7) {
                    pos += 2
                }
            }
        }
    }

    private class Component(val id: Int, val samplingH: Int, val samplingV: Int, val quantTableId: Int)
    private class ScanComponent(val componentIndex: Int, val dcTableId: Int, val acTableId: Int)

    private class FrameInfo(
        val width: Int,
        val height: Int,
        val precision: Int,
        val sofMarker: Int,
        val components: List<Component>,
        val quantTables: Map<Int, IntArray>,
        val huffmanTables: Map<HuffKey, HuffmanTable>,
        val restartInterval: Int,
        val scanComponents: List<ScanComponent>,
        val entropyDataStart: Int,
        val entropyDataEnd: Int,
        val adobeTransform: Int?,
    )

    /**
     * 逐段解析 JPEG 标记结构，抽出解码需要的一切（量化表、哈夫曼表、帧信息、
     * 扫描信息、Adobe APP14、熵编码数据的字节范围）。不在这里做任何"是否
     * 支持"的判断——那些判断在 [decodeInternal] 顶部集中做，这里只负责如实
     * 解析出数据本身。
     */
    private class MarkerParser(private val data: ByteArray) {
        private var pos = 0
        private val quantTables = HashMap<Int, IntArray>()
        private val huffmanTables = HashMap<HuffKey, HuffmanTable>()
        private var restartInterval = 0
        private var adobeTransform: Int? = null

        fun parse(): FrameInfo? {
            if (data.size < 4 || readU8() != 0xFF || readU8() != 0xD8) return null // SOI
            var width = 0
            var height = 0
            var precision = 0
            var sofMarker = 0
            var components: List<Component> = emptyList()
            var scanComponents: List<ScanComponent>? = null
            var entropyStart = -1

            while (pos < data.size) {
                if (readU8() != 0xFF) return null
                var marker = readU8()
                // 允许标记之间出现填充字节 0xFF。
                while (marker == 0xFF) marker = readU8()
                when {
                    marker == 0xD9 -> break // EOI
                    marker == 0x01 || marker in 0xD0..0xD7 -> continue // 无长度字段的标记
                    marker == 0xDB -> parseDqt()
                    marker == 0xC4 -> parseDht()
                    marker == 0xDD -> parseDri()
                    marker == 0xEE -> parseApp14()
                    marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                        val frame = parseSof() ?: return null
                        sofMarker = marker
                        precision = frame.first
                        height = frame.second
                        width = frame.third
                        components = frame.fourth
                    }
                    marker == 0xDA -> {
                        scanComponents = parseSos(components) ?: return null
                        entropyStart = pos
                        // SOS 之后紧跟熵编码数据，不是常规"长度前缀"段，扫描结束
                        // 位置由熵数据自己的结构（遇到非 RST 的下一个标记）决定，
                        // 交给外层调用方（[decodeInternal]）通过 entropyDataEnd
                        // 处理，这里解析完 SOS 头之后直接跳出循环。
                        break
                    }
                    else -> skipSegment()
                }
            }

            if (entropyStart < 0 || scanComponents == null || components.isEmpty()) return null
            val entropyEnd = findEntropyDataEnd(entropyStart)
            return FrameInfo(
                width = width,
                height = height,
                precision = precision,
                sofMarker = sofMarker,
                components = components,
                quantTables = quantTables,
                huffmanTables = huffmanTables,
                restartInterval = restartInterval,
                scanComponents = scanComponents,
                entropyDataStart = entropyStart,
                entropyDataEnd = entropyEnd,
                adobeTransform = adobeTransform,
            )
        }

        /** 从熵数据起点开始找结束位置：跳过字节填充 `FF 00` 和重启标记 `FF D0`-`FF D7`，遇到别的标记就是结束。 */
        private fun findEntropyDataEnd(start: Int): Int {
            var i = start
            while (i + 1 < data.size) {
                if ((data[i].toInt() and 0xFF) == 0xFF) {
                    val next = data[i + 1].toInt() and 0xFF
                    if (next == 0x00 || next in 0xD0..0xD7) {
                        i += 2
                        continue
                    }
                    return i
                }
                i++
            }
            return data.size
        }

        private fun readU8(): Int {
            if (pos >= data.size) throw IllegalStateException("JPEG 数据在偏移 $pos 处意外结束")
            val v = data[pos].toInt() and 0xFF
            pos++
            return v
        }

        private fun readU16(): Int {
            val hi = readU8()
            val lo = readU8()
            return (hi shl 8) or lo
        }

        private fun skipSegment() {
            val length = readU16()
            pos += length - 2
        }

        private fun parseDqt() {
            val length = readU16()
            val segmentEnd = pos + length - 2
            while (pos < segmentEnd) {
                val pq_tq = readU8()
                val precision16 = pq_tq ushr 4 // 0=8位, 1=16位
                val id = pq_tq and 0x0F
                val table = IntArray(64)
                for (i in 0 until 64) {
                    table[i] = if (precision16 == 0) readU8() else readU16()
                }
                quantTables[id] = table
            }
        }

        private fun parseDht() {
            val length = readU16()
            val segmentEnd = pos + length - 2
            while (pos < segmentEnd) {
                val tc_th = readU8()
                val tableClass = tc_th ushr 4
                val id = tc_th and 0x0F
                val bits = IntArray(16) { readU8() }
                val total = bits.sum()
                val values = IntArray(total) { readU8() }
                huffmanTables[HuffKey(tableClass, id)] = HuffmanTable(bits, values)
            }
        }

        private fun parseDri() {
            readU16() // length，固定是 4，不用
            restartInterval = readU16()
        }

        private fun parseApp14() {
            val length = readU16()
            val segmentEnd = pos + length - 2
            if (segmentEnd - pos >= 12) {
                val tag = ByteArray(5) { data[pos + it] }
                val tagText = String(tag, Charsets.US_ASCII)
                if (tagText == "Adobe") {
                    adobeTransform = data[pos + 11].toInt() and 0xFF
                }
            }
            pos = segmentEnd
        }

        /** @return (precision, height, width, components) 的四元组，用 kotlin.Quadruple 没有内建类型，手写一个本地够用的载体。 */
        private fun parseSof(): Quad<Int, Int, Int, List<Component>>? {
            readU16() // length
            val precision = readU8()
            val height = readU16()
            val width = readU16()
            val nf = readU8()
            val components = ArrayList<Component>(nf)
            for (i in 0 until nf) {
                val id = readU8()
                val hv = readU8()
                val qId = readU8()
                components.add(Component(id, hv ushr 4, hv and 0x0F, qId))
            }
            return Quad(precision, height, width, components)
        }

        private fun parseSos(frameComponents: List<Component>): List<ScanComponent>? {
            readU16() // length
            val ns = readU8()
            val result = ArrayList<ScanComponent>(ns)
            for (i in 0 until ns) {
                val cs = readU8()
                val tdta = readU8()
                val componentIndex = frameComponents.indexOfFirst { it.id == cs }
                if (componentIndex < 0) return null
                result.add(ScanComponent(componentIndex, tdta ushr 4, tdta and 0x0F))
            }
            readU8() // Ss，baseline 固定 0，不用
            readU8() // Se，baseline 固定 63，不用
            readU8() // Ah/Al，baseline 固定 0，不用
            return result
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
