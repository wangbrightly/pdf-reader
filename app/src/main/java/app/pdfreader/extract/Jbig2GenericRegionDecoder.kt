package app.pdfreader.extract

/**
 * 纯 Kotlin 手写的 JBIG2 "通用区域"（generic region）解码器——见
 * [PdfTextExtractor.decodeJbig2OrNull] KDoc 完整背景（两次接第三方库都因为
 * Android 运行时不存在 `javax.imageio.stream` 整个子包而失败，NOTES.md #25/#27）。
 *
 * ## 为什么只做"通用区域"，不做符号词典/文字区域
 *
 * 第一本真机反馈的书，用一个轻量诊断（只扫段头的类型字段，不解码任何像素）确认
 * 过：全书 JBIG2 图片的段类型都只有 `[48, 38]`——48 是"页信息"，38 是"立即通用
 * 区域"，没有出现 0（符号词典）/4/6/7（文字区域）这些更复杂的段类型，是最简单的
 * "整页当一张位图算术编码"这种用法（常见于普通扫描软件）——只实现通用区域这一种
 * 曾经就足够覆盖那本书。
 *
 * **2026-08-23 真机反馈修复"这本书还是有图片不能正常显示"，追出这个覆盖范围
 * 的边界**：另一本书（`opened-1732065261531915798.pdf`，23944418 字节）的
 * JBIG2 图片用的是符号词典（类型 0）+ 文字区域编码——直接把这份图片的原始字节
 * 打进日志、手工按 7.2 节格式解析段头确认：页信息段（类型 48）之后紧跟的不是
 * 类型 38，是类型 0（符号词典）。**这不是同一本书的两个副本，是两本不同的书用了
 * 不同的 JBIG2 编码策略**——"通用区域"和"符号词典+文字区域"是 JBIG2 标准允许的
 * 两种完全不同的画风，前者简单但压缩率低（整页当图片编码），后者复杂但压缩率
 * 高得多（重复出现的字形只编码一次、正文按引用复用），扫描软件/OCR 工具选哪种
 * 取决于具体实现，没有统一标准。这次的教训是："某一份真机数据只用了简单编码"
 * 这个结论不能泛化成"JBIG2 在这个项目的真实使用场景里都是简单编码"——每一份
 * 新数据都可能命中不同的编码策略，这个类的"已知局限"范围是基于**已经观察到的
 * 真机数据**划定的，不是基于对"真实世界大多数情况"的猜测，遇到新的真机反馈
 * 命中新的边界是完全预期内的事，不代表这次的修复本身有问题。
 *
 * 符号词典/文字区域解码器工作量是另一个数量级（符号词典本身要解通用区域的一个
 * 变体拿到符号位图集合，文字区域要另外解算术整数解码 + 符号引用位置 + 合成到
 * 画布，标准里这两节篇幅和复杂度都远超通用区域那一节），这次继续不做，遇到就
 * 返回 `null`，调用方按现有的"诚实占位图"降级（见 [PdfTextExtractor
 * .createUnsupportedImagePlaceholder]）——不是"没做完"，是这次投入的范围内
 * 经过真实权衡的决定，如果以后这类"符号词典"编码的书变多、用户需求明确，
 * 值得单独再评估一轮要不要投入实现。
 *
 * ## 算法来源、以及为什么逐字节的"滑动窗口"写法照抄，不改写成直觉上更直白的逐像素版本
 *
 * 算术解码器（MQ-coder）和通用区域逐行解码这两部分，是照着 ITU-T T.88（JBIG2 标准）
 * /ISO IEC 14492:2001 Annex E 描述的算法，对照 Apache PDFBox 项目的
 * `jbig2-imageio`（Apache 2.0 许可证，`org.apache.pdfbox.jbig2.decoder.arithmetic
 * .ArithmeticDecoder`/`CX`、`org.apache.pdfbox.jbig2.segments.GenericRegion`）
 * 这份开源实现重新用 Kotlin 写的，不是照抄 Java 源码那份实现依赖
 * `javax.imageio.stream.ImageInputStream`（Android 运行时不存在），本文件的
 * 读取器是完全独立、只操作内存里 `ByteArray` 的最小实现。
 *
 * 参考实现的逐行解码函数（`decodeTemplate0a`/`1`/`2`/`3`）不是按"每个上下文像素
 * 对应哪一位"这种直觉写法，是维护两个滑动的 16 位整数窗口（当前行、上一行各一个）
 * 按字节推进、通过固定的位掩码/移位算出 context——这套写法的位掩码/移位量是从
 * "AT 像素在默认位置"这个前提反推出来的固定常量，换句话说，**默认 AT 像素这件事
 * 已经隐含编码在这些掩码常量本身里**，不是另外查表加上去的。这次照抄这套滑动
 * 窗口写法（不改写成"每个上下文位对应哪个邻居像素、用坐标查表"这种更符合直觉
 * 但是自己重新推导的版本），是因为算术编码这类状态机对 context 数值的精确性
 * 极其敏感——哪怕只有一位算错，也只是解码结果整体跑偏成另一张（错误的）图，
 * 不会報错、不会崩溃，没有其它信号能提示"这里错了"，唯一可靠的做法是让每一步
 * 操作都能跟参考实现逐行对应，靠"抄的时候不引入新错误"而不是"重新推导后自己
 * 相信它是对的"来保证正确性。
 *
 * ## 已知局限（有意收窄范围，不是遗漏）
 *
 * - 只支持"默认 AT 像素位置"——原因见上一节，AT 像素不是默认值时返回 `null`。
 * - 不支持"扩展模板"（`useExtTemplates`，`gbTemplate==0` 时的一个很少见的
 *   非标准扩展，参考实现里叫 `decodeTemplate0b`）。
 * - 不支持 MMR（Modified-Modified-READ，通用区域标志位选择的另一种跟算术编码
 *   完全不同的编码方式，类似传真 G4 编码）——这次真机数据没有遇到，遇到直接
 *   返回 `null`。
 * - 只处理"一页恰好一个通用区域、区域尺寸就是整页尺寸"这种最简单的情形（真机
 *   数据的真实形状），不做多区域按坐标合成到页面画布这种更通用的处理。
 *
 * 任何一步不满足这些前提，或者数据本身损坏/不完整，都返回 `null`——不抛异常
 * 让调用方处理，也不展示解码失败/损坏的画面，跟本项目一贯的降级精神一致。
 */
internal object Jbig2GenericRegionDecoder {

    /**
     * @param bytes PDF 里 `/Filter /JBIG2Decode` 这个流本身的原始字节（PDF 内嵌
     * 组织形式，没有标准 JBIG2 文件头，直接是一个个段）。
     * @return 解码成功返回按行打包的 1 位位图（0=白，1=黑，每行按字节对齐，
     * MSB 在前——PDF 图像样本流的标准约定，跟 [PdfTextExtractor
     * .decodeRawImageByBitDepth] 用的是同一套约定）+ 宽高；不满足实现范围内的
     * 前提或数据有问题时返回 `null`。
     */
    fun decode(bytes: ByteArray): DecodedBitmap? {
        val segment = findFirstGenericRegionSegment(bytes) ?: return null
        return runCatching { decodeGenericRegionSegment(bytes, segment) }.getOrNull()
    }

    internal data class DecodedBitmap(val width: Int, val height: Int, val packedBits: ByteArray, val rowStride: Int)

    private class GenericRegionSegment(val dataStart: Int, val dataLength: Int)

    /**
     * 扫段头找到第一个"通用区域"段（类型 36/38/39，intermediate/immediate/
     * immediate lossless——这三种段头结构完全一样，区别只在"要不要立刻显示/
     * 保不保留给后续段引用"这类跟像素解码无关的语义，解码逻辑不用区分）。
     * 段头格式见 [PdfTextExtractor.scanJbig2SegmentTypes] KDoc（诊断代码，两处
     * 段头解析逻辑一致，这里额外记下数据区的起止位置)。
     */
    private fun findFirstGenericRegionSegment(bytes: ByteArray): GenericRegionSegment? {
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
            pos += refCount * refSegSize
            pos += if (pageAssocIs4Bytes) 4 else 1
            val dataLength = u32(pos)
            pos += 4
            if (dataLength == 0xFFFFFFFFL || dataLength < 0 || pos + dataLength > bytes.size) return null
            if (type == 36 || type == 38 || type == 39) {
                return GenericRegionSegment(pos, dataLength.toInt())
            }
            pos += dataLength.toInt()
        }
        return null
    }

    private fun decodeGenericRegionSegment(bytes: ByteArray, segment: GenericRegionSegment): DecodedBitmap? {
        var pos = segment.dataStart
        val end = segment.dataStart + segment.dataLength
        fun u8(): Int {
            val v = bytes[pos].toInt() and 0xFF
            pos += 1
            return v
        }
        fun u32(): Long = ((u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong())

        // 区域段信息字段（7.4.1），17 字节：宽(4) 高(4) x(4) y(4) 标志(1，本实现
        // 只用于跳过，不关心具体合成操作符——见类 KDoc"已知局限"一节，只处理
        // "整页一个区域"这种情形）。
        val width = u32().toInt()
        val height = u32().toInt()
        u32() // x location，未使用。
        u32() // y location，未使用。
        u8() // 区域段标志（含合成操作符），未使用。
        if (width <= 0 || height <= 0) return null
        if (width.toLong() * height.toLong() > MAX_GENERIC_REGION_PIXELS) return null

        // 通用区域段标志（7.4.6.2），1 字节：
        // bit7-5 保留，bit4 useExtTemplates，bit3 isTPGDon，bit2-1 gbTemplate，bit0 isMMREncoded。
        val flagsByte = u8()
        val useExtTemplates = (flagsByte shr 4) and 1 == 1
        val isTPGDon = (flagsByte shr 3) and 1 == 1
        val gbTemplate = (flagsByte shr 1) and 0x3
        val isMMREncoded = flagsByte and 1 == 1
        if (useExtTemplates || isMMREncoded) return null // 见类 KDoc"已知局限"一节。

        val atCount = if (gbTemplate == 0) 4 else 1
        val atX = IntArray(atCount)
        val atY = IntArray(atCount)
        for (i in 0 until atCount) {
            atX[i] = bytes[pos].toInt() // 有符号字节，跟参考实现的 readByte()（Java byte 天然有符号）语义一致。
            pos += 1
            atY[i] = bytes[pos].toInt()
            pos += 1
        }
        if (!isDefaultAtPixels(gbTemplate, atX, atY)) return null // 见类 KDoc"已知局限"一节。

        val rowStride = (width + 7) ushr 3
        val bitmap = PackedBitmap(width, height, rowStride)
        val arith = ArithmeticDecoder(bytes, pos, end)
        val cx = Cx(1 shl 16)

        var ltp = 0
        val paddedWidth = (width + 7) and -8
        for (line in 0 until height) {
            if (isTPGDon) {
                cx.index = sltpContextIndex(gbTemplate)
                ltp = ltp xor arith.decode(cx)
            }
            if (ltp == 1) {
                if (line > 0) bitmap.copyLineAbove(line)
                continue
            }
            val byteIndex = bitmap.byteIndexOf(0, line)
            val idx = byteIndex - rowStride
            when (gbTemplate) {
                0 -> decodeTemplate0(bitmap, arith, cx, line, width, rowStride, paddedWidth, byteIndex, idx)
                1 -> decodeTemplate1(bitmap, arith, cx, line, width, rowStride, paddedWidth, byteIndex, idx)
                2 -> decodeTemplate2(bitmap, arith, cx, line, width, rowStride, paddedWidth, byteIndex, idx)
                else -> decodeTemplate3(bitmap, arith, cx, line, width, rowStride, paddedWidth, byteIndex, idx)
            }
        }

        return DecodedBitmap(width, height, bitmap.bytes, rowStride)
    }

    /** 见参考实现 `decodeSLTP`——每种模板固定的上下文索引，用来解码"这一行是不是直接复制上一行"这个标志位。 */
    private fun sltpContextIndex(gbTemplate: Int): Int = when (gbTemplate) {
        0 -> 0x9b25
        1 -> 0x0795
        2 -> 0x00e5
        else -> 0x0195
    }

    /**
     * 默认 AT 像素位置（7.4.6.3 表 7），见类 KDoc"已知局限"一节——只支持这组默认值。
     */
    private fun isDefaultAtPixels(gbTemplate: Int, atX: IntArray, atY: IntArray): Boolean = when (gbTemplate) {
        0 -> atX[0] == 3 && atY[0] == -1 && atX[1] == -3 && atY[1] == -1 &&
            atX[2] == 2 && atY[2] == -2 && atX[3] == -2 && atY[3] == -2
        1 -> atX[0] == 3 && atY[0] == -1
        2 -> atX[0] == 2 && atY[0] == -1
        else -> atX[0] == 2 && atY[0] == -1
    }

    /**
     * 打包位图，方法/字段命名跟参考实现的 `Bitmap` 类（`getByteIndex`/
     * `getByteAsInteger`/`setByte`）一一对应，行为完全一致（`getByteIndex(x,y)
     * = y*rowStride + (x>>3)`，MSB 在前）——[decodeTemplate0]/`1`/`2`/`3` 这几个
     * 函数要跟参考实现的位运算逐行对得上，字段/方法名不一致容易在移植时看错。
     */
    private class PackedBitmap(val width: Int, val height: Int, val rowStride: Int) {
        val bytes = ByteArray(rowStride * height)
        fun byteIndexOf(x: Int, y: Int): Int = y * rowStride + (x shr 3)
        fun getByteAsInt(index: Int): Int = if (index in bytes.indices) bytes[index].toInt() and 0xFF else 0
        fun setByte(index: Int, value: Int) {
            bytes[index] = value.toByte()
        }

        fun copyLineAbove(line: Int) {
            var target = line * rowStride
            var source = target - rowStride
            for (i in 0 until rowStride) {
                bytes[target++] = bytes[source++]
            }
        }
    }

    /**
     * 模板 0——照抄参考实现 `decodeTemplate0a`（`useExtTemplates=false` 那个分支，
     * 见类 KDoc"已知局限"一节），context 的位掩码/移位量是标准定死的常量，不重新
     * 推导。
     */
    private fun decodeTemplate0(
        bitmap: PackedBitmap,
        arith: ArithmeticDecoder,
        cx: Cx,
        lineNumber: Int,
        width: Int,
        rowStride: Int,
        paddedWidth: Int,
        byteIndexStart: Int,
        idxStart: Int,
    ) {
        var byteIndex = byteIndexStart
        var idx = idxStart
        var context: Int
        var line1 = 0
        var line2 = 0

        if (lineNumber >= 1) line1 = bitmap.getByteAsInt(idx)
        if (lineNumber >= 2) line2 = bitmap.getByteAsInt(idx - rowStride) shl 6

        context = (line1 and 0xf0) or (line2 and 0x3800)

        var x = 0
        while (x < paddedWidth) {
            var result = 0
            val nextByte = x + 8
            val minorWidth = if (width - x > 8) 8 else width - x

            if (lineNumber > 0) {
                line1 = (line1 shl 8) or (if (nextByte < width) bitmap.getByteAsInt(idx + 1) else 0)
            }
            if (lineNumber > 1) {
                line2 = (line2 shl 8) or (if (nextByte < width) bitmap.getByteAsInt(idx - rowStride + 1) shl 6 else 0)
            }

            for (minorX in 0 until minorWidth) {
                val toShift = 7 - minorX
                cx.index = context
                val bit = arith.decode(cx)
                result = result or (bit shl toShift)
                context = ((context and 0x7bf7) shl 1) or bit or ((line1 shr toShift) and 0x10) or ((line2 shr toShift) and 0x800)
            }

            bitmap.setByte(byteIndex, result)
            byteIndex++
            idx++
            x = nextByte
        }
    }

    /** 模板 1——照抄参考实现 `decodeTemplate1`。 */
    private fun decodeTemplate1(
        bitmap: PackedBitmap,
        arith: ArithmeticDecoder,
        cx: Cx,
        lineNumber: Int,
        width: Int,
        rowStride: Int,
        paddedWidth: Int,
        byteIndexStart: Int,
        idxStart: Int,
    ) {
        var byteIndex = byteIndexStart
        var idx = idxStart
        var context: Int
        var line1 = 0
        var line2 = 0

        if (lineNumber >= 1) line1 = bitmap.getByteAsInt(idx)
        if (lineNumber >= 2) line2 = bitmap.getByteAsInt(idx - rowStride) shl 5

        context = ((line1 shr 1) and 0x1f8) or ((line2 shr 1) and 0x1e00)

        var x = 0
        while (x < paddedWidth) {
            var result = 0
            val nextByte = x + 8
            val minorWidth = if (width - x > 8) 8 else width - x

            if (lineNumber >= 1) {
                line1 = (line1 shl 8) or (if (nextByte < width) bitmap.getByteAsInt(idx + 1) else 0)
            }
            if (lineNumber >= 2) {
                line2 = (line2 shl 8) or (if (nextByte < width) bitmap.getByteAsInt(idx - rowStride + 1) shl 5 else 0)
            }

            for (minorX in 0 until minorWidth) {
                cx.index = context
                val bit = arith.decode(cx)
                result = result or (bit shl (7 - minorX))
                val toShift = 8 - minorX
                context = ((context and 0xefb) shl 1) or bit or ((line1 shr toShift) and 0x8) or ((line2 shr toShift) and 0x200)
            }

            bitmap.setByte(byteIndex, result)
            byteIndex++
            idx++
            x = nextByte
        }
    }

    /** 模板 2——照抄参考实现 `decodeTemplate2`。 */
    private fun decodeTemplate2(
        bitmap: PackedBitmap,
        arith: ArithmeticDecoder,
        cx: Cx,
        lineNumber: Int,
        width: Int,
        rowStride: Int,
        paddedWidth: Int,
        byteIndexStart: Int,
        idxStart: Int,
    ) {
        var byteIndex = byteIndexStart
        var idx = idxStart
        var context: Int
        var line1 = 0
        var line2 = 0

        if (lineNumber >= 1) line1 = bitmap.getByteAsInt(idx)
        if (lineNumber >= 2) line2 = bitmap.getByteAsInt(idx - rowStride) shl 4

        context = ((line1 shr 3) and 0x7c) or ((line2 shr 3) and 0x380)

        var x = 0
        while (x < paddedWidth) {
            var result = 0
            val nextByte = x + 8
            val minorWidth = if (width - x > 8) 8 else width - x

            if (lineNumber >= 1) {
                line1 = (line1 shl 8) or (if (nextByte < width) bitmap.getByteAsInt(idx + 1) else 0)
            }
            if (lineNumber >= 2) {
                line2 = (line2 shl 8) or (if (nextByte < width) bitmap.getByteAsInt(idx - rowStride + 1) shl 4 else 0)
            }

            for (minorX in 0 until minorWidth) {
                cx.index = context
                val bit = arith.decode(cx)
                result = result or (bit shl (7 - minorX))
                val toShift = 10 - minorX
                context = ((context and 0x1bd) shl 1) or bit or ((line1 shr toShift) and 0x4) or ((line2 shr toShift) and 0x80)
            }

            bitmap.setByte(byteIndex, result)
            byteIndex++
            idx++
            x = nextByte
        }
    }

    /** 模板 3——照抄参考实现 `decodeTemplate3`（只有当前行和上一行，没有 line2）。 */
    private fun decodeTemplate3(
        bitmap: PackedBitmap,
        arith: ArithmeticDecoder,
        cx: Cx,
        lineNumber: Int,
        width: Int,
        rowStride: Int,
        paddedWidth: Int,
        byteIndexStart: Int,
        idxStart: Int,
    ) {
        var byteIndex = byteIndexStart
        var idx = idxStart
        var context: Int
        var line1 = 0

        if (lineNumber >= 1) line1 = bitmap.getByteAsInt(idx)

        context = (line1 shr 1) and 0x70

        var x = 0
        while (x < paddedWidth) {
            var result = 0
            val nextByte = x + 8
            val minorWidth = if (width - x > 8) 8 else width - x

            if (lineNumber >= 1) {
                line1 = (line1 shl 8) or (if (nextByte < width) bitmap.getByteAsInt(idx + 1) else 0)
            }

            for (minorX in 0 until minorWidth) {
                cx.index = context
                val bit = arith.decode(cx)
                result = result or (bit shl (7 - minorX))
                context = ((context and 0x1f7) shl 1) or bit or ((line1 shr (8 - minorX)) and 0x010)
            }

            bitmap.setByte(byteIndex, result)
            byteIndex++
            idx++
            x = nextByte
        }
    }

    /** 见类 KDoc"已知局限"一节——超过这个像素数（宽×高）直接放弃，避免极端数据把内存拖垮。 */
    private const val MAX_GENERIC_REGION_PIXELS = 30_000_000L

    /**
     * 算术解码的上下文状态表——[ArithmeticDecoder.decode] 每次调用前要把 [index]
     * 设成"这个上下文对应哪个格子"，`cxState`/`mps` 两个数组分别存这个格子当前的
     * 概率状态编号和"更可能的符号"，解码之后原地更新，跟参考实现的 `CX` 类是
     * 同一个数据结构。
     */
    private class Cx(size: Int) {
        var index = 0
        val cxState = IntArray(size)
        val mps = IntArray(size)
    }

    /**
     * MQ 算术解码器——照 ITU-T T.88/ISO IEC 14492:2001 Annex E 描述的算法，对照
     * 参考实现（见类 KDoc"算法来源"一节）用 Kotlin 重写，用一个只操作内存
     * `ByteArray`（配合起止偏移量，不需要单独切片拷贝一份）的最小读取器代替原
     * 实现依赖的 `javax.imageio.stream.ImageInputStream`。`byteIn()` 里"先退
     * 一格再读、检查是不是 0xFF 标记字节"这套逻辑是标准算法本身的一部分（处理
     * 算术编码流里 0xFF 字节的位填充规则，避免跟真正的标记码混淆），照抄参考
     * 实现的调用顺序，没有做任何"看起来可以简化"的改动。
     */
    private class ArithmeticDecoder(private val data: ByteArray, start: Int, private val end: Int) {
        private var readPos = start
        private val streamPos0: Int = start
        private var a = 0
        private var c = 0L
        private var ct = 0
        private var b = 0

        init {
            b = readByte()
            c = b.toLong() shl 16
            byteIn()
            c = c shl 7
            ct -= 7
            a = 0x8000
        }

        /** 流耗尽时返回 0xFF——等价于参考实现里 InputStream 到 EOF 后 `read()` 返回
         * -1、被当无符号字节参与位运算时的行为（-1 的低 8 位就是 0xFF）。 */
        private fun readByte(): Int {
            if (readPos >= end) return 0xFF
            val v = data[readPos].toInt() and 0xFF
            readPos++
            return v
        }

        private fun byteIn() {
            if (readPos > streamPos0) readPos--
            b = readByte()
            if (b == 0xFF) {
                val b1 = readByte()
                if (b1 > 0x8F) {
                    c += 0xFF00
                    ct = 8
                    readPos -= 2
                } else {
                    c += (b1.toLong() shl 9)
                    ct = 7
                }
            } else {
                b = readByte()
                c += (b.toLong() shl 8)
                ct = 8
            }
            c = c and 0xFFFFFFFFL
        }

        private fun renormalize() {
            do {
                if (ct == 0) byteIn()
                a = a shl 1
                c = c shl 1
                ct--
            } while ((a and 0x8000) == 0)
            c = c and 0xFFFFFFFFL
        }

        private fun mpsExchange(cx: Cx, icx: Int): Int {
            val mps = cx.mps[cx.index]
            return if (a < QE[icx][0]) {
                if (QE[icx][3] == 1) cx.mps[cx.index] = cx.mps[cx.index] xor 1
                cx.cxState[cx.index] = QE[icx][2]
                1 - mps
            } else {
                cx.cxState[cx.index] = QE[icx][1]
                mps
            }
        }

        private fun lpsExchange(cx: Cx, icx: Int, qeValue: Int): Int {
            val mps = cx.mps[cx.index]
            return if (a < qeValue) {
                cx.cxState[cx.index] = QE[icx][1]
                a = qeValue
                mps
            } else {
                if (QE[icx][3] == 1) cx.mps[cx.index] = cx.mps[cx.index] xor 1
                cx.cxState[cx.index] = QE[icx][2]
                a = qeValue
                1 - mps
            }
        }

        fun decode(cx: Cx): Int {
            val icx = cx.cxState[cx.index]
            val qeValue = QE[icx][0]
            a -= qeValue
            val d: Int
            if ((c ushr 16) < qeValue) {
                d = lpsExchange(cx, icx, qeValue)
                renormalize()
            } else {
                c -= (qeValue.toLong() shl 16)
                if ((a and 0x8000) == 0) {
                    d = mpsExchange(cx, icx)
                    renormalize()
                } else {
                    return cx.mps[cx.index]
                }
            }
            return d
        }

        companion object {
            /** Qe 概率估计表（ISO IEC 14492:2001 Annex E.1，表 E.1）：[Qe值, NMPS, NLPS, SWITCH]。 */
            private val QE = arrayOf(
                intArrayOf(0x5601, 1, 1, 1), intArrayOf(0x3401, 2, 6, 0), intArrayOf(0x1801, 3, 9, 0),
                intArrayOf(0x0AC1, 4, 12, 0), intArrayOf(0x0521, 5, 29, 0), intArrayOf(0x0221, 38, 33, 0),
                intArrayOf(0x5601, 7, 6, 1), intArrayOf(0x5401, 8, 14, 0), intArrayOf(0x4801, 9, 14, 0),
                intArrayOf(0x3801, 10, 14, 0), intArrayOf(0x3001, 11, 17, 0), intArrayOf(0x2401, 12, 18, 0),
                intArrayOf(0x1C01, 13, 20, 0), intArrayOf(0x1601, 29, 21, 0), intArrayOf(0x5601, 15, 14, 1),
                intArrayOf(0x5401, 16, 14, 0), intArrayOf(0x5101, 17, 15, 0), intArrayOf(0x4801, 18, 16, 0),
                intArrayOf(0x3801, 19, 17, 0), intArrayOf(0x3401, 20, 18, 0), intArrayOf(0x3001, 21, 19, 0),
                intArrayOf(0x2801, 22, 19, 0), intArrayOf(0x2401, 23, 20, 0), intArrayOf(0x2201, 24, 21, 0),
                intArrayOf(0x1C01, 25, 22, 0), intArrayOf(0x1801, 26, 23, 0), intArrayOf(0x1601, 27, 24, 0),
                intArrayOf(0x1401, 28, 25, 0), intArrayOf(0x1201, 29, 26, 0), intArrayOf(0x1101, 30, 27, 0),
                intArrayOf(0x0AC1, 31, 28, 0), intArrayOf(0x09C1, 32, 29, 0), intArrayOf(0x08A1, 33, 30, 0),
                intArrayOf(0x0521, 34, 31, 0), intArrayOf(0x0441, 35, 32, 0), intArrayOf(0x02A1, 36, 33, 0),
                intArrayOf(0x0221, 37, 34, 0), intArrayOf(0x0141, 38, 35, 0), intArrayOf(0x0111, 39, 36, 0),
                intArrayOf(0x0085, 40, 37, 0), intArrayOf(0x0049, 41, 38, 0), intArrayOf(0x0025, 42, 39, 0),
                intArrayOf(0x0015, 43, 40, 0), intArrayOf(0x0009, 44, 41, 0), intArrayOf(0x0005, 45, 42, 0),
                intArrayOf(0x0001, 45, 43, 0), intArrayOf(0x5601, 46, 46, 0),
            )
        }
    }
}
