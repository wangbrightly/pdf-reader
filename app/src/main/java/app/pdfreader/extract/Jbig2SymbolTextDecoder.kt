package app.pdfreader.extract

/**
 * JBIG2 "符号词典 + 文字区域"编码的解码器——这是 [Jbig2GenericRegionDecoder]
 * KDoc"已知局限"一节记录的、当初没做的那部分，2026-08-23 用户明确要求"解决
 * JBIG2 图片显示的问题"后动手实现。见该类 KDoc 完整背景（真机确认这是另一本书
 * 真实使用的编码方式）。
 *
 * ## 摸清真实数据形状再动手（不是照抄标准的全部能力）
 *
 * 装机加诊断日志确认过这本书的真实编码参数（不是猜的）：
 * - 符号词典（本地词典 + 一份 PDF `/DecodeParms << /JBIG2Globals >>` 声明的
 *   共享全局词典，见 [PdfTextExtractor.decodeJbig2OrNull] KDoc"JBIG2Globals"
 *   一节）：全部算术编码（不是 Huffman），不用"精细化聚合"（这是 JBIG2 里专门
 *   处理"新符号是已有符号的小幅修改"这种情形的编码路径，比直接编码复杂得多），
 *   `sdTemplate=0`，AT 像素是标准默认值，符号词典本身不引用其它符号词典（不需要
 *   处理"词典 A 导入词典 B 的符号"这种链式导入）。
 * - 文字区域：算术编码，不用精细化（每个符号实例都是词典里现成符号的原样放置，
 *   不需要"这个实例在原符号基础上做了小幅修改"这条更复杂的路径），标志位全部
 *   是最简单的默认值（`sbStrips=1`、不转置、参考角是左下角、合成操作符是 OR）。
 *
 * 只实现覆盖这个真实形状的这条路径，遇到任何字段不是预期值（Huffman 编码、
 * 精细化聚合/精细化、词典间导入）都返回 `null`，调用方按现有的占位图机制降级，
 * 跟 [Jbig2GenericRegionDecoder] 一贯的"诚实识别覆盖不了、不猜、不展示错误
 * 画面"原则一致。
 *
 * ## 算法来源
 *
 * 整数算术解码（`ArithmeticIntegerDecoder`，Annex A）、符号词典解码流程
 * （6.5 节）、文字区域解码流程（6.4 节）都是照 Apache PDFBox 项目
 * `jbig2-imageio` 的对应实现（`ArithmeticIntegerDecoder`/`SymbolDictionary`/
 * `TextRegion`，Apache 2.0 许可证）重新用 Kotlin 写的，跟
 * [Jbig2GenericRegionDecoder] 是同一套"照抄算法本身、不依赖参考实现的类库
 * 基础设施"的做法——[ArithmeticIntegerDecoder] 是直接、忠实的移植（参考实现
 * 本身就是一套简单的状态机，没有性能优化写法需要"翻译"）；文字区域的符号
 * 放置逻辑（`blit`）参考实现用的是按字节整体位移的写法，这次改成逐像素读写
 * （见 [Jbig2GenericRegionDecoder.PackedBitmap] 新增的 `getPixel`/`setPixel`
 * KDoc），用可验证性换一点性能，这次场景下 blit 次数是"每页符号实例数"量级，
 * 不是热路径，这笔交换划算。
 *
 * ## 验证状态（2026-08-23 已补上交叉验证）
 *
 * 刚写完时只做到"编译通过、没破坏现有测试"——本机没有 JBIG2 编码器
 * （`jbig2enc` 两次装不上），没法像 [Jbig2GenericRegionDecoder] 那样用合成
 * 数据验证，只能等真机导出真实数据。同一天用户配合装机，诊断代码导出了
 * 这本书最小的一张符号词典图片（主数据流 5073 字节 + 共享 Globals 流 4624
 * 字节，存成 `jbig2-symbol-*.jb2` 测试 fixture），[Jbig2SymbolTextDecoder
 * CrossValidationTest] 把这份真实数据喂给自己和 Apache jbig2-imageio
 * （完整规范实现）逐像素比对，**完全一致**——验证课补上了。
 * 装机后用户肉眼确认过这本书之前显示不出的图片现在内容正常。
 *
 * 范围边界仍然如"摸清真实数据形状"一节所述：fixture 只是这本书的一张图，
 * 其它书若用 Huffman 编码/精细化/词典间导入等没覆盖到的变体，返回 `null`
 * 走占位图，不硬解。
 */
internal object Jbig2SymbolTextDecoder {

    /**
     * @param mainBytes 这张图片自己的 JBIG2 段数据（`/Filter /JBIG2Decode` 流
     * 本身，PDF 内嵌组织形式，无文件头）。
     * @param globalsBytes 如果 PDF 的 `/DecodeParms` 声明了 `/JBIG2Globals`，
     * 这是那份共享流解出来的段数据；没有就传 `null`。
     * @return 解码成功返回整页的打包位图；命中任何已知局限或数据有问题都返回
     * `null`，不抛异常。
     */
    fun decode(mainBytes: ByteArray, globalsBytes: ByteArray?): Jbig2GenericRegionDecoder.DecodedBitmap? =
        runCatching { decodeInternal(mainBytes, globalsBytes) }.getOrNull()

    private data class Segment(val number: Long, val type: Int, val refs: List<Long>, val dataStart: Int, val dataLength: Int)

    /**
     * 完整解析一份 JBIG2 数据里的全部段（编号/类型/引用了哪些段/数据区间）——
     * 跟 [PdfTextExtractor.summarizeJbig2Segments]（诊断代码，已删）是同一套
     * 解析逻辑，这次是正式实现，需要结构化的返回值（不是打日志用的字符串）。
     * 段头格式见 ITU-T T.88 §7.2。
     */
    private fun parseSegments(bytes: ByteArray): List<Segment> {
        val result = mutableListOf<Segment>()
        var pos = 0
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun u32(i: Int): Long {
            if (i + 4 > bytes.size) throw IndexOutOfBoundsException()
            return ((u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or
                (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong())
        }
        while (pos + 11 <= bytes.size) {
            val segmentNumber = u32(pos)
            pos += 4
            val flags = u8(pos)
            pos += 1
            val type = flags and 0x3F
            val pageAssocIs4Bytes = (flags and 0x40) != 0
            val refFlagsByte = u8(pos)
            val refCount: Int
            if ((refFlagsByte ushr 5) == 7) {
                val countField = u32(pos) and 0x1FFFFFFF
                refCount = countField.toInt()
                pos += 4
                pos += (refCount + 1 + 7) / 8
            } else {
                refCount = refFlagsByte ushr 5
                pos += 1
            }
            val refSegSize = when {
                segmentNumber <= 256 -> 1
                segmentNumber <= 65536 -> 2
                else -> 4
            }
            val refs = mutableListOf<Long>()
            for (i in 0 until refCount) {
                val v = when (refSegSize) {
                    1 -> u8(pos).toLong()
                    2 -> ((u8(pos).toLong() shl 8) or u8(pos + 1).toLong())
                    else -> u32(pos)
                }
                refs.add(v)
                pos += refSegSize
            }
            pos += if (pageAssocIs4Bytes) 4 else 1
            val dataLength = u32(pos)
            pos += 4
            if (dataLength == 0xFFFFFFFFL || dataLength < 0 || pos + dataLength > bytes.size) break
            result.add(Segment(segmentNumber, type, refs, pos, dataLength.toInt()))
            pos += dataLength.toInt()
        }
        return result
    }

    private fun u32(bytes: ByteArray, pos: Int): Int {
        val b0 = bytes[pos].toInt() and 0xFF
        val b1 = bytes[pos + 1].toInt() and 0xFF
        val b2 = bytes[pos + 2].toInt() and 0xFF
        val b3 = bytes[pos + 3].toInt() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun decodeInternal(mainBytes: ByteArray, globalsBytes: ByteArray?): Jbig2GenericRegionDecoder.DecodedBitmap? {
        val mainSegments = parseSegments(mainBytes)
        val globalsSegments = globalsBytes?.let { parseSegments(it) } ?: emptyList()

        val pageInfo = mainSegments.firstOrNull { it.type == 48 } ?: return null
        val pageWidth = u32(mainBytes, pageInfo.dataStart)
        val pageHeight = u32(mainBytes, pageInfo.dataStart + 4)
        if (pageWidth <= 0 || pageHeight <= 0) return null
        if (pageWidth.toLong() * pageHeight.toLong() > MAX_PAGE_PIXELS) return null

        // 符号词典段（类型 0）可能来自共享的 Globals 流，也可能是这张图片自己
        // 的本地段——按段号解码，谁引用了就现解，解出来缓存住（同一个词典段
        // 理论上不会被引用两次，缓存主要是让代码结构简单，不是为了性能）。
        val dictCache = HashMap<Long, List<Jbig2GenericRegionDecoder.PackedBitmap>>()
        fun resolveDictionary(number: Long): List<Jbig2GenericRegionDecoder.PackedBitmap>? {
            dictCache[number]?.let { return it }
            val seg = globalsSegments.firstOrNull { it.number == number && it.type == 0 }?.let { it to globalsBytes!! }
                ?: mainSegments.firstOrNull { it.number == number && it.type == 0 }?.let { it to mainBytes }
                ?: return null
            val decoded = decodeSymbolDictionary(seg.second, seg.first) ?: return null
            dictCache[number] = decoded
            return decoded
        }

        val textRegionSegment = mainSegments.firstOrNull { it.type == 4 || it.type == 6 || it.type == 7 } ?: return null
        if (textRegionSegment.refs.isEmpty()) return null
        // 见类 KDoc"摸清真实数据形状"一节——引用顺序决定符号编号顺序，必须严格
        // 按引用列表的顺序拼接，不能按段号排序或者别的顺序（跟参考实现
        // `TextRegion.initSymbols()` 按 `getRtSegments()` 顺序遍历是同一个要求）。
        val symbols = mutableListOf<Jbig2GenericRegionDecoder.PackedBitmap>()
        for (ref in textRegionSegment.refs) {
            val dict = resolveDictionary(ref) ?: return null
            symbols.addAll(dict)
        }
        if (symbols.isEmpty()) return null

        val regionBitmap = decodeTextRegion(mainBytes, textRegionSegment, symbols) ?: return null

        // 见 [Jbig2GenericRegionDecoder] 里 JBIG2Page.fitsPage 那条参考逻辑注释
        // ——文字区域尺寸/位置恰好跟整页一致时，直接把它当页面位图，不需要再
        // 走一次 blit（这次真机数据正好是这种情形，但不假设一定是，仍然实现
        // 了通用的 blit 兜底路径）。
        val regionX = u32(mainBytes, textRegionSegment.dataStart + 8)
        val regionY = u32(mainBytes, textRegionSegment.dataStart + 12)
        if (regionX == 0 && regionY == 0 && regionBitmap.width == pageWidth && regionBitmap.height == pageHeight) {
            return Jbig2GenericRegionDecoder.DecodedBitmap(pageWidth, pageHeight, regionBitmap.bytes, regionBitmap.rowStride)
        }
        val pageBitmap = Jbig2GenericRegionDecoder.PackedBitmap(pageWidth, pageHeight, (pageWidth + 7) ushr 3)
        val regionInfoFlags = mainBytes[textRegionSegment.dataStart + 16].toInt() and 0xFF
        blitBitmap(regionBitmap, pageBitmap, regionX, regionY, regionInfoFlags and 0x7)
        return Jbig2GenericRegionDecoder.DecodedBitmap(pageWidth, pageHeight, pageBitmap.bytes, pageBitmap.rowStride)
    }

    /**
     * 解码一个符号词典段（6.5 节），只覆盖类 KDoc"摸清真实数据形状"一节确认过的
     * 路径：算术编码、不用精细化聚合、不导入其它词典的符号。
     */
    private fun decodeSymbolDictionary(bytes: ByteArray, segment: Segment): List<Jbig2GenericRegionDecoder.PackedBitmap>? {
        if (segment.refs.isNotEmpty()) return null // 见类 KDoc："不导入其它词典的符号"这个前提，这次数据确认为空。
        var pos = segment.dataStart
        val end = segment.dataStart + segment.dataLength

        // 符号词典标志（7.4.2.1.1），2 字节，MSB 在前：
        // bit12 sdrTemplate，bit10-11 sdTemplate，bit9 isCodingContextRetained，
        // bit8 isCodingContextUsed，bit1 useRefinementAggregation，bit0 isHuffmanEncoded。
        val flags = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
        pos += 2
        val sdTemplate = (flags shr 10) and 0x3
        val useRefinementAggregation = (flags shr 1) and 1 == 1
        val isHuffmanEncoded = flags and 1 == 1
        if (isHuffmanEncoded || useRefinementAggregation) return null // 见类 KDoc"已知局限"一节。

        val atCount = if (sdTemplate == 0) 4 else 1
        val atX = IntArray(atCount)
        val atY = IntArray(atCount)
        for (i in 0 until atCount) {
            atX[i] = bytes[pos].toInt()
            pos += 1
            atY[i] = bytes[pos].toInt()
            pos += 1
        }
        if (!Jbig2GenericRegionDecoder.isDefaultAtPixels(sdTemplate, atX, atY)) return null
        // useRefinementAggregation=false，跳过"精细化 AT 像素"这个字段（只在
        // useRefinementAggregation=true 且 sdrTemplate==0 时才存在）。

        // 字段顺序（7.4.2.1.4/7.4.2.1.5）：先是导出符号数，再是新增符号数——
        // 两个字段都是 4 字节又紧挨着，容易搞反，写完初稿代码审查时发现过一次
        // 顺序写反（把导出符号数当成了新增符号数用），这里补一句注释免得以后
        // 重构时再犯同样的错。导出符号数本身不需要单独保留——实际导出哪些
        // 符号在下面单独用 IAEX 解码运行长度决定（6.5.10），不依赖这个字段
        // 的数值本身（这个字段更多是给 Huffman 路径用的）。
        pos += 4 // 跳过导出符号数。
        val amountOfNewSymbols = u32(bytes, pos)
        pos += 4
        if (amountOfNewSymbols <= 0 || amountOfNewSymbols > MAX_SYMBOLS_PER_DICTIONARY) return null

        val arith = Jbig2GenericRegionDecoder.ArithmeticDecoder(bytes, pos, end)
        val iDecoder = ArithmeticIntegerDecoder(arith)
        val cxIADH = Jbig2GenericRegionDecoder.Cx(512)
        val cxIADW = Jbig2GenericRegionDecoder.Cx(512)
        val cxIAEX = Jbig2GenericRegionDecoder.Cx(512)
        val cxGB = Jbig2GenericRegionDecoder.Cx(1 shl 16)

        // 6.5.5：逐个"高度类"解码，每个高度类内部逐个符号解码，宽度用 OOB
        // （[ArithmeticIntegerDecoder.OOB]）标记这个高度类结束。
        val newSymbols = mutableListOf<Jbig2GenericRegionDecoder.PackedBitmap>()
        var heightClassHeight = 0
        while (newSymbols.size < amountOfNewSymbols) {
            val deltaHeight = iDecoder.decode(cxIADH)
            if (deltaHeight == ArithmeticIntegerDecoder.OOB) return null // 高度增量不应该是 OOB。
            heightClassHeight += deltaHeight.toInt()
            if (heightClassHeight <= 0 || heightClassHeight > MAX_SYMBOL_DIMENSION) return null
            var symbolWidth = 0
            while (true) {
                val deltaWidth = iDecoder.decode(cxIADW)
                if (deltaWidth == ArithmeticIntegerDecoder.OOB) break // 这个高度类解完了。
                symbolWidth += deltaWidth.toInt()
                if (symbolWidth <= 0 || symbolWidth > MAX_SYMBOL_DIMENSION) return null
                if (newSymbols.size >= amountOfNewSymbols) return null // 数据和头部字段对不上，放弃。
                val symbolBitmap = Jbig2GenericRegionDecoder.decodeSharedContextBitmap(
                    symbolWidth,
                    heightClassHeight,
                    sdTemplate,
                    arith,
                    cxGB,
                )
                newSymbols.add(symbolBitmap)
            }
        }

        // 6.5.10：导出标志——用 IAEX 交替解码"不导出的连续段长度"/"导出的连续
        // 段长度"，从"不导出"开始（参考实现 `currentExportFlag` 初值 0）。
        // 本实现不支持导入其它词典的符号（前面已经 return null 排除），所以
        // "全部符号"就是 newSymbols 本身，不需要区分"导入的"和"新增的"两段。
        val exported = mutableListOf<Jbig2GenericRegionDecoder.PackedBitmap>()
        var currentFlag = 0
        var index = 0
        // 见 6.5.10：正常数据里"运行长度序列"最多在 newSymbols.size 个元素上
        // 切出有限段，不可能需要比"元素数+1"更多次循环——用这个上限挡住"运行
        // 长度反复解出 0、index 永远不推进"这种畸形/损坏数据造成的死循环，
        // 比只检查"第一次就卡住"这种窄条件更可靠（那种写法对"循环到一半才
        // 卡住"的情况完全防不住）。
        var iterations = 0
        val maxIterations = newSymbols.size + 1
        while (index < newSymbols.size) {
            iterations++
            if (iterations > maxIterations) return null
            val runLength = iDecoder.decode(cxIAEX)
            if (runLength == ArithmeticIntegerDecoder.OOB || runLength < 0) return null
            if (currentFlag == 1) {
                for (i in 0 until runLength.toInt()) {
                    if (index + i >= newSymbols.size) return null
                    exported.add(newSymbols[index + i])
                }
            }
            index += runLength.toInt()
            currentFlag = 1 - currentFlag
        }
        return exported.ifEmpty { null }
    }

    /**
     * 解码一个文字区域段（6.4 节），只覆盖类 KDoc"摸清真实数据形状"一节确认过
     * 的路径：算术编码，不用精细化。
     */
    private fun decodeTextRegion(
        bytes: ByteArray,
        segment: Segment,
        symbols: List<Jbig2GenericRegionDecoder.PackedBitmap>,
    ): Jbig2GenericRegionDecoder.PackedBitmap? {
        var pos = segment.dataStart
        val end = segment.dataStart + segment.dataLength

        // 区域段信息字段（7.4.1），17 字节：宽(4) 高(4) x(4) y(4) 标志(1)。
        val width = u32(bytes, pos)
        val height = u32(bytes, pos + 4)
        pos += 17
        if (width <= 0 || height <= 0) return null
        if (width.toLong() * height.toLong() > MAX_PAGE_PIXELS) return null

        // 文字区域段标志（7.4.3.1.1），2 字节，MSB 在前：
        // bit15 sbrTemplate，bit10-14 sbdsOffset(5位有符号)，bit9 defaultPixel，
        // bit7-8 combinationOperator，bit6 isTransposed，bit4-5 referenceCorner，
        // bit2-3 logSBStrips，bit1 useRefinement，bit0 isHuffmanEncoded。
        val flags = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
        pos += 2
        val sbrTemplate = (flags shr 15) and 1
        var sbdsOffset = (flags shr 10) and 0x1F
        if (sbdsOffset > 0x0F) sbdsOffset -= 0x20
        val defaultPixel = (flags shr 9) and 1
        val combinationOperator = (flags shr 7) and 0x3
        val isTransposed = (flags shr 6) and 1
        val referenceCorner = (flags shr 4) and 0x3
        val logSbStrips = (flags shr 2) and 0x3
        val sbStrips = 1 shl logSbStrips
        val useRefinement = (flags shr 1) and 1 == 1
        val isHuffmanEncoded = flags and 1 == 1
        if (isHuffmanEncoded || useRefinement) return null // 见类 KDoc"已知局限"一节；useRefinement=true 时这里还应该跳过精细化 AT 像素字段，本实现不支持，直接放弃。

        val amountOfSymbolInstances = u32(bytes, pos).toLong() and 0xFFFFFFFFL
        pos += 4
        if (amountOfSymbolInstances <= 0 || amountOfSymbolInstances > MAX_SYMBOL_INSTANCES) return null

        // symbolCodeLength = ceil(log2(符号总数))——照抄参考实现的公式，不额外
        // 加"至少 1 位"这类看似合理的保护（有些第三方实现会这样做）：
        // symbols.size==1 时 log2(1)=0，ceil(0)=0，IAID 解码 0 位、固定返回
        // 符号 0，这本身是自洽的（只有一个符号，不需要编码选哪个），跟参考
        // 实现行为一致，强行改成至少 1 位反而会跟编码端实际用的位数对不上。
        val symbolCodeLength = Math.ceil(Math.log(symbols.size.toDouble()) / Math.log(2.0)).toInt()

        val regionBitmap = Jbig2GenericRegionDecoder.PackedBitmap(width, height, (width + 7) ushr 3)
        if (defaultPixel != 0) {
            for (i in regionBitmap.bytes.indices) regionBitmap.bytes[i] = 0xFF.toByte()
        }

        val arith = Jbig2GenericRegionDecoder.ArithmeticDecoder(bytes, pos, end)
        val iDecoder = ArithmeticIntegerDecoder(arith)
        val cxIADT = Jbig2GenericRegionDecoder.Cx(512)
        val cxIAFS = Jbig2GenericRegionDecoder.Cx(512)
        val cxIADS = Jbig2GenericRegionDecoder.Cx(512)
        val cxIAIT = Jbig2GenericRegionDecoder.Cx(512)
        val cxIAID = Jbig2GenericRegionDecoder.Cx(1 shl symbolCodeLength)

        // 6.4.5：strip T 起始值——先解一个 IADT，再乘以 -sbStrips（标准定义的
        // 符号，见参考实现 `decodeStripT`）。
        var stripT = -(iDecoder.decode(cxIADT).let { if (it == ArithmeticIntegerDecoder.OOB) return null else it }) * sbStrips
        var firstS = 0L
        var instanceCounter = 0L

        while (instanceCounter < amountOfSymbolInstances) {
            val dT = iDecoder.decode(cxIADT)
            if (dT == ArithmeticIntegerDecoder.OOB) return null
            stripT += dT * sbStrips

            var currentS = 0L
            var first = true
            while (true) {
                if (first) {
                    val dfS = iDecoder.decode(cxIAFS)
                    if (dfS == ArithmeticIntegerDecoder.OOB) return null
                    firstS += dfS
                    currentS = firstS
                    first = false
                } else {
                    val idS = iDecoder.decode(cxIADS)
                    if (idS == ArithmeticIntegerDecoder.OOB) break // 这一条 strip 解完了。
                    currentS += idS + sbdsOffset
                }

                val currentT = if (sbStrips != 1) {
                    val t = iDecoder.decode(cxIAIT)
                    if (t == ArithmeticIntegerDecoder.OOB) return null
                    t
                } else {
                    0L
                }
                val t = stripT + currentT

                val id = iDecoder.decodeIAID(cxIAID, symbolCodeLength)
                if (id < 0 || id >= symbols.size) return null
                val symbol = symbols[id]

                currentS = blitSymbolInstance(
                    symbol,
                    regionBitmap,
                    currentS,
                    t,
                    isTransposed,
                    referenceCorner,
                    combinationOperator,
                )

                instanceCounter++
                if (instanceCounter > amountOfSymbolInstances) return null // 防御：数据和头部字段对不上。
            }
        }

        return regionBitmap
    }

    /**
     * 把一个符号实例贴到文字区域画布上——照抄参考实现 `blit` 方法的坐标换算
     * 逻辑（[Jbig2GenericRegionDecoder.PackedBitmap] KDoc 解释过为什么这次改用
     * 逐像素 [blitBitmap] 而不是按字节位移的写法，坐标换算本身不变，只是最终
     * 落到像素级 blit）。
     *
     * @return 更新后的 `currentS`（"贴完这个符号后，下一个符号实例的 S 坐标
     * 该从哪里算起"——参考实现里这是同一个变量原地累加，这里改成返回新值，
     * 避免用可变外部变量传参数，函数式一点）。
     */
    private fun blitSymbolInstance(
        symbol: Jbig2GenericRegionDecoder.PackedBitmap,
        region: Jbig2GenericRegionDecoder.PackedBitmap,
        currentSIn: Long,
        tIn: Long,
        isTransposed: Int,
        referenceCorner: Int,
        combinationOperator: Int,
    ): Long {
        var currentS = currentSIn
        if (isTransposed == 0 && (referenceCorner == 2 || referenceCorner == 3)) {
            currentS += symbol.width - 1
        } else if (isTransposed == 1 && (referenceCorner == 0 || referenceCorner == 2)) {
            currentS += symbol.height - 1
        }

        var s = currentS
        var t = tIn
        if (isTransposed == 1) {
            val swap = t
            t = s
            s = swap
        }

        if (referenceCorner != 1) {
            when (referenceCorner) {
                0 -> t -= symbol.height - 1 // BL
                2 -> { // BR
                    t -= symbol.height - 1
                    s -= symbol.width - 1
                }
                3 -> s -= symbol.width - 1 // TR
            }
        }

        blitBitmap(symbol, region, s.toInt(), t.toInt(), combinationOperator)

        if (isTransposed == 0 && (referenceCorner == 0 || referenceCorner == 1)) {
            currentS += symbol.width - 1
        }
        if (isTransposed == 1 && (referenceCorner == 1 || referenceCorner == 3)) {
            currentS += symbol.height - 1
        }
        return currentS
    }

    /**
     * 逐像素合成——见 [Jbig2GenericRegionDecoder.PackedBitmap] KDoc 解释过为什么
     * 这次不照抄参考实现按字节位移的 `Bitmaps.blit`。`op` 是文字区域/区域段
     * 信息字段里那 2/3 位的合成操作符编码（7.4.1.5/7.4.3.1.1 共用同一套编码：
     * 0=OR, 1=AND, 2=XOR, 3=XNOR, 4/其它=REPLACE，见参考实现 `CombinationOperator
     * .translateOperatorCodeToEnum`）。
     */
    private fun blitBitmap(
        src: Jbig2GenericRegionDecoder.PackedBitmap,
        dst: Jbig2GenericRegionDecoder.PackedBitmap,
        x: Int,
        y: Int,
        op: Int,
    ) {
        for (sy in 0 until src.height) {
            val dy = y + sy
            if (dy < 0 || dy >= dst.height) continue
            for (sx in 0 until src.width) {
                val dx = x + sx
                if (dx < 0 || dx >= dst.width) continue
                val srcBit = src.getPixel(sx, sy)
                val dstBit = dst.getPixel(dx, dy)
                val newBit = when (op) {
                    0 -> dstBit or srcBit
                    1 -> dstBit and srcBit
                    2 -> dstBit xor srcBit
                    3 -> 1 - (dstBit xor srcBit)
                    else -> srcBit
                }
                dst.setPixel(dx, dy, newBit)
            }
        }
    }

    /** 见 [decodeInternal]——页面像素数超过这个上限直接放弃，避免极端数据把内存拖垮，跟 [Jbig2GenericRegionDecoder] 的同类安全阀一致。 */
    private const val MAX_PAGE_PIXELS = 30_000_000L

    /** 单个符号词典段最多解码这么多符号，超过判定数据异常，避免死循环/内存爆炸。 */
    private const val MAX_SYMBOLS_PER_DICTIONARY = 100_000

    /** 单个符号的宽/高上限（像素）——正常字形符号远小于这个值，超过判定数据异常。 */
    private const val MAX_SYMBOL_DIMENSION = 10_000

    /** 单个文字区域最多放置这么多符号实例，超过判定数据异常。 */
    private const val MAX_SYMBOL_INSTANCES = 10_000_000L

    /**
     * 算术整数解码器（Annex A.2/A.3）——照 Apache PDFBox 项目 `jbig2-imageio`
     * 的 `ArithmeticIntegerDecoder` 忠实移植（见类 KDoc"算法来源"一节），这个
     * 类本身就是一套简单的状态机（按标准里 Annex A.2 的流程图逐步判断该读
     * 多少个附加位、加多少偏移量），没有需要"翻译"的性能优化写法，基本是
     * 逐行对照参考实现改写成 Kotlin 语法。
     */
    private class ArithmeticIntegerDecoder(private val decoder: Jbig2GenericRegionDecoder.ArithmeticDecoder) {
        private var prev = 1

        private fun setPrev(bit: Int) {
            prev = if (prev < 256) {
                ((prev shl 1) or bit) and 0x1FF
            } else {
                ((((prev shl 1) or bit) and 511) or 256) and 0x1FF
            }
        }

        /**
         * Annex A.2 算术整数解码流程——返回值语义跟参考实现完全一致：正常解码
         * 出的值是普通 `Long`；[OOB]（Out-Of-Band，标准里表示"这一组结束"的
         * 特殊标记，不是一个真正的数值）用 `Long.MAX_VALUE` 表示，调用方必须
         * 先判断是不是等于 [OOB] 再当数值用。
         */
        fun decode(cxIAx: Jbig2GenericRegionDecoder.Cx): Long {
            var v = 0
            prev = 1

            cxIAx.index = prev
            val s = decoder.decode(cxIAx)
            setPrev(s)

            cxIAx.index = prev
            var d = decoder.decode(cxIAx)
            setPrev(d)

            val bitsToRead: Int
            val offset: Int
            if (d == 1) {
                cxIAx.index = prev
                d = decoder.decode(cxIAx)
                setPrev(d)
                if (d == 1) {
                    cxIAx.index = prev
                    d = decoder.decode(cxIAx)
                    setPrev(d)
                    if (d == 1) {
                        cxIAx.index = prev
                        d = decoder.decode(cxIAx)
                        setPrev(d)
                        if (d == 1) {
                            cxIAx.index = prev
                            d = decoder.decode(cxIAx)
                            setPrev(d)
                            if (d == 1) {
                                bitsToRead = 32
                                offset = 4436
                            } else {
                                bitsToRead = 12
                                offset = 340
                            }
                        } else {
                            bitsToRead = 8
                            offset = 84
                        }
                    } else {
                        bitsToRead = 6
                        offset = 20
                    }
                } else {
                    bitsToRead = 4
                    offset = 4
                }
            } else {
                bitsToRead = 2
                offset = 0
            }

            for (i in 0 until bitsToRead) {
                cxIAx.index = prev
                d = decoder.decode(cxIAx)
                setPrev(d)
                v = (v shl 1) or d
            }
            v += offset

            return when {
                s == 0 -> v.toLong()
                s == 1 && v > 0 -> -v.toLong()
                else -> OOB
            }
        }

        /** IAID 解码流程（Annex A.3）——固定读 [symCodeLen] 位，不走上面那套变长编码。 */
        fun decodeIAID(cxIAID: Jbig2GenericRegionDecoder.Cx, symCodeLen: Int): Int {
            prev = 1
            for (i in 0 until symCodeLen) {
                cxIAID.index = prev
                prev = (prev shl 1) or decoder.decode(cxIAID)
            }
            return prev - (1 shl symCodeLen)
        }

        companion object {
            /** Out-Of-Band 标记，见 [decode] KDoc。 */
            const val OOB = Long.MAX_VALUE
        }
    }
}
