package app.pdfreader.extract

import kotlin.math.cos
import kotlin.math.min

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
 * 1. **只处理 4 分量数据**，且必须带 Adobe APP14 标记、`transform=0`——这是
 *    真机字节级验证过的（见 [PdfTextExtractor] 里的排查记录）。`transform=2`
 *    （YCCK，先做一次 YCbCr→RGB 再转 CMYK 那种变体）本地造不出测试数据
 *    （Python Pillow 的 `Image.convert('CMYK')` 只会产出 `transform=0` 这一种，
 *    没有可信的交叉验证手段），**没有实现，遇到直接返回 `null`**。没有 Adobe
 *    标记的 4 分量数据（"纯 CMYK 不反色"这种约定）同样没有实现——没在真机
 *    数据里见过这种情况，不确定就不处理。
 * 2. **只处理 4 个分量的色度采样比例都是 1×1（不做色度子采样）**——真机拿到的
 *    fixture（`cmyk-quadrant.jpg`）和本地用 Pillow 生成的测试数据，色度采样
 *    比例全部是 1×1（这也是 Adobe 系工具处理 CMYK JPEG 的标准做法：CMYK 四个
 *    通道本来就没有"亮度更重要，色度可以降采样"这种 YCbCr 才有的心理视觉假设，
 *    没有理由做子采样）。这个限定顺带把最复杂的一块（MCU 内多分量按不同分辨率
 *    交织、色度上采样滤波器选择）整个避开了，遇到任何分量采样比例不是 1×1
 *    直接返回 `null`。
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
 * 标记层面没有任何信号能区分两种约定（都长一样），只能看内容：逐图对原始
 * 采样值投票——存的值偏亮（四通道和 > 700 的采样多于偏暗的）按反色约定解；
 * 存的值偏暗按不反色解。推导过四种组合（两种约定 × 内容偏白/偏暗）这条规则
 * 全部给出正确画面：偏白内容投票必然选对它自己的约定；偏暗内容即使选错约定，
 * 解出来也还是偏暗（错约定下偏暗内容的解码结果恰好不变）。扫描/印刷页面
 * 绝大部分是白底，这条规则在这类数据上等于"永远选对"。
 *
 * 反色之后是标准的 CMYK→RGB 转换（没有实现色彩管理/ICC 曲线那一套，是
 * "够用"的朴素公式，扫描/印刷场景下这条公式是行业惯例，不是本项目发明的）：
 * ```
 * R = 255 − min(255, C + K)
 * G = 255 − min(255, M + K)
 * B = 255 − min(255, Y + K)
 * ```
 *
 * ## 已知局限（如实告知，别在测试之外假装能处理）
 *
 * - 不支持渐进式 JPEG（SOF2）
 * - 不支持色度子采样（任何分量采样比例不是 1×1）
 * - 不支持 YCCK（`transform=2`）
 * - 不支持没有 Adobe 标记的 4 分量数据
 * - 不支持算术编码（只支持 Huffman 熵编码，JPEG 里的算术编码变体极少见）
 * - 不支持 12 位精度（只支持 8 位，绝大多数 JPEG 是这个精度）
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
     * 像素）反复解码多次都正常，4469×3871（约 1730 万像素）稳定 OOM——取
     * 400 万像素（约等于 2000×2000），是"经验安全值"的将近 2 倍、"经验失败值"
     * 的不到四分之一，按 20 字节/像素峰值内存估算约 80MB，在 256MB 堆里留了
     * 足够余量给其它同时占用的内存（PDF 文档缓冲、RecyclerView 页面缓存等）。
     * 超过这个阈值直接拒绝解码、退回占位图——跟本类"范围外数据一律返回
     * null"的一贯降级精神一致，比冒 OOM 崩溃风险划算。真正的解决办法是把
     * `planes` 从 `IntArray` 换成占内存更小的存储、或者实现按需降采样解码
     * （工作量高一个级别，这次没做，见 NOTES.md #34）。
     */
    internal const val MAX_CMYK_JPEG_PIXELS = 4_000_000

    fun decode(bytes: ByteArray): DecodedImage? = runCatching { decodeInternal(bytes) }.getOrNull()

    private fun decodeInternal(bytes: ByteArray): DecodedImage? {
        val frame = MarkerParser(bytes).parse() ?: return null
        if (frame.precision != 8) return null
        if (frame.components.size != 4) return null
        if (frame.components.any { it.samplingH != 1 || it.samplingV != 1 }) return null
        if (frame.sofMarker != 0xC0 && frame.sofMarker != 0xC1) return null
        if (frame.adobeTransform != 0) return null
        if (frame.scanComponents.size != 4) return null
        if (frame.width.toLong() * frame.height.toLong() > MAX_CMYK_JPEG_PIXELS) return null

        val blocksPerLine = (frame.width + 7) / 8
        val blocksPerColumn = (frame.height + 7) / 8
        val planeWidth = blocksPerLine * 8
        val planeHeight = blocksPerColumn * 8
        val planes = Array(4) { IntArray(planeWidth * planeHeight) }

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

        for (blockRow in 0 until blocksPerColumn) {
            for (blockCol in 0 until blocksPerLine) {
                for (ci in frame.scanComponents.indices) {
                    val sc = frame.scanComponents[ci]
                    val (dcTable, acTable, quant) = decodeTables[ci]
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
                    val plane = planes[sc.componentIndex]
                    val baseX = blockCol * 8
                    val baseY = blockRow * 8
                    for (y in 0 until 8) {
                        val rowOffset = (baseY + y) * planeWidth + baseX
                        val srcOffset = y * 8
                        for (x in 0 until 8) {
                            plane[rowOffset + x] = spatial[srcOffset + x]
                        }
                    }
                }

                mcuCount++
                if (frame.restartInterval > 0 && mcuCount % frame.restartInterval == 0) {
                    val isLastBlock = blockRow == blocksPerColumn - 1 && blockCol == blocksPerLine - 1
                    if (!isLastBlock) {
                        bitReader.alignAndConsumeRestart()
                        java.util.Arrays.fill(dcPredictors, 0)
                    }
                }
            }
        }

        // 见类 KDoc"核心机制"一节：对原始采样值投票决定这张图按哪种存储约定解。
        // 每 8 个采样取一票（全图投票没必要，白底页面几千票足够稳定，也省时间）。
        var hiVotes = 0
        var loVotes = 0
        var voteIndex = 0
        while (voteIndex < planes[0].size) {
            val s = planes[0][voteIndex] + planes[1][voteIndex] + planes[2][voteIndex] + planes[3][voteIndex]
            if (s > 700) hiVotes++ else if (s < 300) loVotes++
            voteIndex += 8
        }
        val invert = hiVotes >= loVotes

        val width = frame.width
        val height = frame.height
        val argb = IntArray(width * height)
        var idx = 0
        for (y in 0 until height) {
            val rowBase = y * planeWidth
            for (x in 0 until width) {
                val p = rowBase + x
                // 反色约定：存的值先 255-值 还原真实 CMYK；不反色约定：存的值即真实值。
                val realC = if (invert) 255 - planes[0][p].coerceIn(0, 255) else planes[0][p].coerceIn(0, 255)
                val realM = if (invert) 255 - planes[1][p].coerceIn(0, 255) else planes[1][p].coerceIn(0, 255)
                val realY = if (invert) 255 - planes[2][p].coerceIn(0, 255) else planes[2][p].coerceIn(0, 255)
                val realK = if (invert) 255 - planes[3][p].coerceIn(0, 255) else planes[3][p].coerceIn(0, 255)
                val r = 255 - min(255, realC + realK)
                val g = 255 - min(255, realM + realK)
                val b = 255 - min(255, realY + realK)
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
