package app.pdfreader.extract

import android.graphics.Bitmap
import com.gemalto.jp2.JP2Decoder

/**
 * JPX（JPEG2000）图片解码——薄封装，真正的解码工作全部交给
 * `io.github.michaldvorak-gemalto:jp2-android:1.0.5`（`JP2ForAndroid` 项目，
 * OpenJPEG 2.5.4 的 JNI 封装；这不是 Thales 官方发布，是个人维护者重新发布到
 * Maven Central 的坐标，原坐标 `com.gemalto.jp2` 随 JCenter 关停已失效，
 * 见 NOTES.md #43/#48）。
 *
 * 这个类跟 [JpegDecoder] 走同样的对外契约（`decode(bytes): 结果?`，范围外/
 * 任何失败一律返回 `null`，调用方降级到诚实占位图），但内部实现完全不同——
 * [JpegDecoder] 是纯 Kotlin 手写实现（因为 CMYK/YCCK JPEG 没有现成的可靠
 * Android 库），这里恰好相反：JPEG2000 的完整解码算法（小波变换 + EBCOT 位
 * 平面算术编码）极其复杂，手写一份是这个项目目前为止最大的单项工程投入，
 * 而 OpenJPEG 已经是被广泛验证过的成熟实现，所以选择接入而不是重新发明。
 * 见 `/Users/mac/.claude/plans/iridescent-foraging-floyd.md` 完整决策过程
 * （含 POC 阶段验证记录）。
 *
 * ## POC 阶段已验证的边界（装机实测，不是纸面推测）
 *
 * - 正常 JP2/J2K 数据：解码结果与 macOS `sips`（Core Graphics/ImageIO，跟
 *   OpenJPEG 完全独立的另一套实现）逐像素比对，简单无损 fixture 完全一致；
 *   NOTES #43 那份真实扫描文档样本（有损压缩）逐像素比对最大单通道差值为
 *   1（两套独立实现在色彩空间转换舍入策略上的正常差异，不是内容错误）。
 * - 截断的数据流、伪造 magic number 但内容是随机字节的数据：`JP2Decoder
 *   .decode()` 都稳定返回 `null`，没有出现不可控的 native crash（真机装机
 *   反复验证过）。**但这不构成"这个库对任何损坏输入都安全"的保证**——POC
 *   只覆盖了这两类损坏模式，[decode] 仍然用 `runCatching` 兜底，只是要
 *   如实承认 `runCatching` 拦不住真正的 native 层 `SIGSEGV`（如果未来真机
 *   遇到这类崩溃，需要另外补前置格式探测/尺寸上限）。
 * - 1.0.5 没有 `setDecodingArea()`（区域解码），也没有单独确认 HTJ2K 支持
 *   程度——PDF 里整页嵌入图片解码用不上前者，后者目前没有真机数据能验证，
 *   遇到这个库解不出来的编码变体，`decode()` 返回 `null`，走占位图，跟其它
 *   格式范围外情况处理方式一致。
 */
internal object Jpeg2000Decoder {
    fun decode(bytes: ByteArray): Bitmap? = runCatching { JP2Decoder(bytes).decode() }.getOrNull()
}
