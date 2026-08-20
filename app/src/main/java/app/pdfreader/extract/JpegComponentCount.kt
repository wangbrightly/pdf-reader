package app.pdfreader.extract

/**
 * 2026-08-20 真机反馈修复"CMYK JPEG 走原生解码路径花屏"过程中的一个真机踩坑：
 * 见 [PdfTextExtractor] 里 `decodeJpegWithNativeSubsampling` KDoc"已修"一节完整
 * 背景，这里只记录"为什么不用 `PDImage.colorSpace` 判断，改成自己解析 JPEG 字节"
 * 这一步的具体原因。
 *
 * ## 为什么不能用 `PDImage.colorSpace.numberOfComponents`
 *
 * 反编译 pdfbox-android 2.0.27.0 的 `PDColorSpace.create(COSBase, ...)` 发现：这个
 * 版本压根没有实现 `PDDeviceCMYK` 这个类（`com.tom_roush.pdfbox.pdmodel.graphics
 * .color` 包里只有 Gray/RGB/JPX，没有 CMYK）——遇到 PDF 图片字典里声明
 * `/ColorSpace /DeviceCMYK` 时，这个方法会打一条 `"Unsupported color space kind:
 * DeviceCMYK. Will try DeviceRGB instead"` 的日志，然后**返回 `PDDeviceRGB
 * .INSTANCE`**。也就是说不管 JPEG 实际是几通道，`pdImage.colorSpace
 * .numberOfComponents` 在这个库里对 CMYK 图片永远报 3，这条路径完全测不出真机
 * 遇到的问题（真机诊断日志已经确认过这一点：加了这个判断条件之后问题毫无变化）。
 *
 * ## 改成直接读 JPEG 字节本身的 SOF 段
 *
 * JPEG 文件结构是一串"标记"（marker）：`FF D8`（SOI，文件开头）之后跟着一系列
 * `FF <code>` 标记，大多数标记后面跟 2 字节大端长度 + 载荷；其中 `FF C0`-`FF CF`
 * （除了 `C4`=DHT 哈夫曼表、`C8`=保留、`CC`=DAC 算术编码表，这三个的载荷格式不是
 * "帧信息"）是"SOF"（Start Of Frame）标记，无论 baseline 还是 progressive 编码，
 * SOF 载荷的前 6 个字节固定是：1 字节精度 + 2 字节高度 + 2 字节宽度，紧接着第 7
 * 个字节就是"颜色分量数"（Nf）——RGB/YCbCr 是 3，CMYK/YCCK 是 4，灰度是 1。这是
 * JPEG 编码本身的底层事实，不依赖任何 PDF 库怎么理解颜色空间，最可靠。
 */
internal object JpegComponentCount {

    /**
     * 解析 [bytes] 这份 JPEG 数据里 SOF 段声明的颜色分量数；解析不出来（不是合法
     * JPEG、数据被截断、没找到 SOF 段就到文件尾）返回 `null`，调用方应该按"不确定"
     * 保守处理（不确定就不用可能有问题的快速路径），不要当成"3 通道"乐观处理。
     */
    fun of(bytes: ByteArray): Int? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var offset = 2
        while (offset + 1 < bytes.size) {
            if (bytes[offset] != 0xFF.toByte()) return null
            val marker = bytes[offset + 1].toInt() and 0xFF
            offset += 2
            // SOI/占位字节/RST0-7 这几个标记没有长度字段，直接跳过继续找下一个标记。
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) continue
            if (marker == 0xD9) return null // 遇到 EOI（文件尾）还没找到 SOF。
            if (offset + 1 >= bytes.size) return null
            val length = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                val componentCountOffset = offset + 2 + 1 + 2 + 2 // 长度(2)+精度(1)+高(2)+宽(2) 之后。
                if (componentCountOffset >= bytes.size) return null
                return bytes[componentCountOffset].toInt() and 0xFF
            }
            if (length < 2) return null // 非法长度，避免死循环/越界。
            offset += length
        }
        return null
    }
}
