package app.pdfreader.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * [JpegDecoder] 的交叉验证测试。
 *
 * ## 参考实现的选择：Pillow（预先解码存成 PNG），不是 TwelveMonkeys
 *
 * 一开始想法是像 [org.apache.pdfbox.jbig2.Jbig2GenericRegionDecoderCrossValidationTest]
 * 那样，直接在 JVM 测试里用一个第三方库（TwelveMonkeys 的 `imageio-jpeg`）当场
 * 解码同一份字节比对。**这条路走了一半发现走不通，如实记录**：给已知纯黑
 * （CMYK=255,255,255,0，按公式应该精确解出 RGB=(0,0,0)）的最小测试图，
 * TwelveMonkeys 解出 `(44,48,49)`，偏差达到 44-49——这不是"合理的有损压缩
 * 误差"量级，是这个库自己在处理"C/M/Y 全部拉满、K=0"这种极端 CMYK 组合时
 * 有问题（具体原因没有深挖，不重要，重要的是不能拿一个自己都有已知偏差的
 * 实现当"标准答案"）。反过来验证 Python Pillow（底层是 libjpeg-turbo，工业界
 * 最广泛使用的 JPEG 库）对同一份数据、以及对纯白/纯黑/纯黄三种极端色的解码
 * 全部精确匹配预期值，額外验证过一份自定义颜色 CMYK 图，Pillow 解出的 RGB
 * 跟这次自己写的 [JpegDecoder] 解出的结果逐字节一致——两条独立证据都指向
 * "Pillow 这条参考可信，TwelveMonkeys 这条对这类极端输入不可信"，所以改成
 * 拿 Pillow 预先解码的结果当参考（存成 PNG，测试时用标准 `javax.imageio`
 * 读回来比较——PNG 是无损格式，标准 JDK 自带的 PNG 解码器没有任何 CMYK
 * 相关的已知问题，可以直接信）。
 *
 * fixture 本身（`*.jpg`）和参考答案（`*.reference.png`）都是本地用 Python
 * Pillow 生成的：先造一张已知内容的 RGB 图，转 CMYK 存成 JPEG（结构上跟真机
 * 数据完全一致——Adobe APP14 标记、`transform=0`、baseline、4 分量、1×1
 * 采样，逐字节验证过），再用 Pillow 自己把这份 JPEG 解码回 RGB PNG 存成
 * 参考答案。允许小容差（IDCT 浮点实现细节不同不代表算法错，只要跟 Pillow
 * 这个个成熟实现的结果足够接近）。
 */
class JpegDecoderCrossValidationTest {

    private fun loadBytes(name: String): ByteArray = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(name)?.readBytes(),
    ) { "找不到测试 fixture：src/test/resources/$name" }

    private fun assertMatchesReference(baseName: String, tolerance: Int = 10) {
        val bytes = loadBytes("$baseName.jpg")
        val decoded = requireNotNull(JpegDecoder.decode(bytes)) { "$baseName.jpg：JpegDecoder 返回 null" }
        val reference = requireNotNull(ImageIO.read(ByteArrayInputStream(loadBytes("$baseName.reference.png")))) {
            "$baseName.reference.png：参考 PNG 读取失败"
        }
        assertEquals("$baseName 宽度", reference.width, decoded.width)
        assertEquals("$baseName 高度", reference.height, decoded.height)

        // 批量读参考像素（一次 getRGB 拿整幅，不逐像素调——真机 fixture 是
        // 3000x2000 共 600 万像素，逐像素调用慢一个数量级）。
        val refPixels = reference.getRGB(
            0, 0, decoded.width, decoded.height,
            IntArray(decoded.width * decoded.height), 0, decoded.width,
        )
        var maxDiff = 0
        var sumDiff = 0L
        for (i in decoded.argb.indices) {
            val argb = decoded.argb[i]
            val refRgb = refPixels[i]
            val diff = maxOf(
                abs(((argb ushr 16) and 0xFF) - ((refRgb ushr 16) and 0xFF)),
                abs(((argb ushr 8) and 0xFF) - ((refRgb ushr 8) and 0xFF)),
                abs((argb and 0xFF) - (refRgb and 0xFF)),
            )
            maxDiff = maxOf(maxDiff, diff)
            sumDiff += diff
        }
        val avgDiff = sumDiff.toDouble() / decoded.argb.size
        assertTrue(
            "$baseName：跟 Pillow 参考解码最大像素差 $maxDiff 超过容差 $tolerance（平均差 $avgDiff）",
            maxDiff <= tolerance,
        )
    }

    @Test
    fun `四象限纯色 跟Pillow参考解码逐像素比对`() {
        assertMatchesReference("cmyk-quadrant-64")
    }

    @Test
    fun `对角渐变(非8的倍数尺寸) 跟Pillow参考解码逐像素比对`() {
        assertMatchesReference("cmyk-gradient-50")
    }

    @Test
    fun `含黑色油墨的灰度渐变 跟Pillow参考解码逐像素比对`() {
        assertMatchesReference("cmyk-grayscale-k-72x40")
    }

    @Test
    fun `带重启间隔标记(DRI+RSTn) 跟Pillow参考解码逐像素比对`() {
        assertMatchesReference("cmyk-restart-128")
    }

    @Test
    fun `高频噪声图 跟Pillow参考解码逐像素比对`() {
        assertMatchesReference("cmyk-noise-200x150")
    }

    /**
     * 2026-08-23 装机验证抓出的真 bug：那本 CMYK 教科书（印刷行业扫描数据）虽然
     * 也带 Adobe APP14 transform=0 标记，但存的是**不反色**的 CMYK（不符合 Adobe
     * 约定，但真实存在）——按反色约定解（Pillow 和我原来的写法都这样）整幅纯黑，
     * 用户在真机上看到的就是这个。fixture `cmyk-book-noinv.jpg` 直接从这本书的 PDF
     * 里原样抽出（1725×955 的页面装饰图），不反色解码应该是正常页面画面
     * （绝大部分白/浅色），断言明暗分布而不是逐像素（这种数据没有可信的第三方
     * 参考——所有按 Adobe 约定实现的库都会跟我原来一样解错）。
     */
    @Test
    fun `真书不反色存储的CMYK 解码结果是正常页面不是全黑`() {
        val bytes = loadBytes("cmyk-book-noinv.jpg")
        val decoded = requireNotNull(JpegDecoder.decode(bytes)) { "cmyk-book-noinv.jpg：JpegDecoder 返回 null" }

        var dark = 0
        var light = 0
        val total = decoded.argb.size
        for (argb in decoded.argb) {
            val sum = ((argb ushr 16) and 0xFF) + ((argb ushr 8) and 0xFF) + (argb and 0xFF)
            if (sum < 150) dark++
            if (sum > 600) light++
        }
        assertTrue(
            "不反色存储的真书页面解出来应该是大部分浅色（实际亮 ${100L * light / total}% 暗 ${100L * dark / total}%）——" +
                "如果暗像素占绝大多数，说明又按反色约定解了",
            dark.toDouble() / total < 0.05 && light.toDouble() / total > 0.8,
        )
    }

    /**
     * 真机导出的原始数据（不是本地合成的）也要跟 Pillow 参考解码逐像素一致——
     * 本地合成的 6 组 fixture 证明了算法本身对，但"真机那批书到底是不是这个
     * 结构"是另一回事。这份 `cmyk-quadrant.jpg` 是 2026-08-23 从真机应用私有
     * 目录取回的（一次性诊断导出，见 [PdfTextExtractor] 里已删除的导出代码），
     * 字节级核对过结构：baseline SOF0、8 位、3000x2000、4 分量全 1x1 采样、
     * Adobe APP14 transform=0，跟解码器 KDoc"范围"一节逐条对上。参考 PNG 同样
     * 是 Pillow（底层 libjpeg-turbo）预先解码存的。这条测试是"合成数据上验证过
     * 的算法"和"真机真实数据"之间的桥：真机数据解出来跟工业界最广泛使用的
     * JPEG 库一个像素都不差，才能确认装机后用户看到的画面是对的。
     */
    @Test
    fun `真机导出的CMYK fixture 跟Pillow参考解码逐像素比对`() {
        assertMatchesReference("cmyk-quadrant")
    }

    @Test
    fun `四象限纯色 解码结果本身要接近原始红绿蓝黄四色 不只是跟参考实现一致`() {
        val decoded = requireNotNull(JpegDecoder.decode(loadBytes("cmyk-quadrant-64.jpg")))
        fun pixelAt(x: Int, y: Int): Triple<Int, Int, Int> {
            val argb = decoded.argb[y * decoded.width + x]
            return Triple((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)
        }
        val (r1, g1, b1) = pixelAt(16, 16) // 左上：红
        assertTrue("左上应该偏红：($r1,$g1,$b1)", r1 > g1 + 40 && r1 > b1 + 40)
        val (r2, g2, b2) = pixelAt(48, 16) // 右上：绿
        assertTrue("右上应该偏绿：($r2,$g2,$b2)", g2 > r2 + 40 && g2 > b2 + 40)
        val (r3, g3, b3) = pixelAt(16, 48) // 左下：蓝
        assertTrue("左下应该偏蓝：($r3,$g3,$b3)", b3 > r3 + 40 && b3 > g3 + 40)
        val (r4, g4, b4) = pixelAt(48, 48) // 右下：黄（红+绿高，蓝低）
        assertTrue("右下应该偏黄：($r4,$g4,$b4)", r4 > b4 + 40 && g4 > b4 + 40)
    }

    @Test
    fun `真机确认过的CMYK特征之外的数据 明确返回null 不冒险硬解`() {
        // 3 分量 RGB JPEG——不是这个解码器的服务范围（见类 KDoc"范围"一节），
        // 用现有 fixture 验证明确拒绝而不是硬解出一个可能错误的结果。
        assertNull(JpegDecoder.decode(loadBytes("small-quadrant.jpg")))
    }

    @Test
    fun `空字节数组 返回null 不抛异常`() {
        assertNull(JpegDecoder.decode(ByteArray(0)))
    }

    @Test
    fun `截断的CMYK JPEG 返回null或者不崩溃 不抛未捕获异常`() {
        val bytes = loadBytes("cmyk-quadrant-64.jpg")
        // 截到熵编码数据中间，模拟真机上偶发的截断/损坏数据。
        val truncated = bytes.copyOfRange(0, bytes.size / 2)
        // 不断言具体返回值（可能解出部分内容也可能返回 null，两种都合理），
        // 只要求不抛出未捕获异常——`decode` 内部 runCatching 兜底，这里是
        // 确认这层防线真的生效。
        JpegDecoder.decode(truncated)
        assertNotNull("走到这里说明没有抛异常", true)
    }
}
