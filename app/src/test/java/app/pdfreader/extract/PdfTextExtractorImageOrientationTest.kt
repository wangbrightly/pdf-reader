package app.pdfreader.extract

import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * [PdfTextExtractor.applyCtmOrientation]（内嵌图片朝向修正）的单元测试——见
 * [PdfTextExtractor] 类注释"内嵌图片朝向修正"一节（2026-08-18，真机反馈"部分图片
 * 方向不对"）。
 *
 * ## 为什么不是"造一份 PDF，抽取，跟整页渲染比对"这种更直观的写法
 *
 * 一开始确实是照任务描述的思路写的：造一份最小 PDF（一张四色象限测试图，用某个
 * CTM 摆上去），一边跑 [PdfTextExtractor.extractContent] 抽取，一边用
 * `PDFRenderer.renderImageWithDPI` 整页渲染同一份文档当"真实应该看到的样子"，
 * 两者的四角颜色做对比。实测这条路在本机 Robolectric + PdfBox-Android 2.0.27.0
 * 这套工具链下踩了两个和本次任务无关的坑，逐一排查确认过：
 *
 * 1. 用 `ImageIO` 现编的 PNG 字节，经 `PDImageXObject.createFromByteArray` 塞进
 *    PDF 再 `getImage()` 读出来，颜色会错乱（比如纯红 `(255,0,0)` 读出来变成
 *    `(0,0,255)`）——排查过不是调色板优化的锅（加了扰动像素让颜色数远超 256 种，
 *    结果不变），规律是"结果 R=原 G、结果 G=原 B、结果 B 恒为 255、原 R 整个丢失"，
 *    这是一个稳定复现、和 CTM/朝向完全无关的独立解码问题。
 * 2. 对着一个从零现搭、只有一张图片的最小内存态 `PDDocument` 调用
 *    `PDFRenderer.renderImageWithDPI`，渲染出来的整页几乎全透明（只有最左一列
 *    不透明白色），换成"先存盘再重新 `PDDocument.load()`"、换成不同的图片写入
 *    方式结果都一样——图片内容根本没有被画出来，这也是和本次任务的 CTM 逻辑无关
 *    的独立问题。
 *
 * 两个坑都在"怎么构造/解码测试用的图片数据"这一层，不在 [PdfTextExtractor
 * .applyCtmOrientation] 本身要验证的翻转/旋转逻辑上，继续在这条路上排查对本次任务
 * 没有增量价值。改成下面这个更直接、也更可信的验证方式：
 *
 * ## 实际验证方式：独立写一个参照模型，只比对 CTM 数学，不经过任何图片编解码/像素重采样
 *
 * 排查上面两个坑的过程中还发现第三个、真正跟本次任务相关的环境限制：本机
 * Robolectric 环境下 `Bitmap.createBitmap(src,x,y,w,h,matrix,filter)`（
 * [PdfTextExtractor.applyCtmOrientation] 最后一步用来真正按矩阵重采样像素的那个
 * 重载）的影子实现（Shadow）不会真的按 `matrix` 重采样——程序化验证过：随便一个
 * 水平翻转矩阵传进去，输出位图四个角全是黑的（不是翻转后的颜色，是完全没有内容）；
 * 换成 `Matrix.mapPoints(...)`（纯数学坐标变换，不涉及像素重采样）在同一环境下
 * 结果完全正确。这是 Robolectric 对 Canvas 像素级绘制这类操作的影子实现精度
 * 限制，不是真机行为——真机上 `Bitmap.createBitmap` 带 `Matrix` 参数是 Android
 * 平台最基础、最成熟的位图变换 API，不需要也没条件在这个项目里重新验证平台本身
 * 对不对。
 *
 * 因此 [PdfTextExtractor.applyCtmOrientation] 拆成了 [PdfTextExtractor
 * .orientationMatrixOrNull]（纯矩阵计算）+ `applyCtmOrientation`（拿到矩阵后调
 * `Bitmap.createBitmap` 重采样）两步——这里测的是前者：直接调用
 * `orientationMatrixOrNull(ctm, width, height)`，拿到的 `Matrix` 用
 * `mapPoints` 作用在四个角的像素坐标上（这一步在 Robolectric 下可靠，上面已经
 * 验证过），看落点对不对，不需要真的生成一张变换后的 `Bitmap`。
 *
 * "预期落点"来自 [referenceLayout]——一个完全独立写的参照模型，直接照抄 PDF
 * 规范里"图片空间→页面空间"的线性映射公式 `px = a·x + c·y + e`、
 * `py = b·x + d·y + f`（这行公式是 PDF 规范定义，和
 * [PdfTextExtractor.orientationMatrixOrNull] 内部"R0 · CTM"那套推导是两套独立
 * 写法），算出原始位图四个角分别落在页面的哪个象限，反过来就是"页面每个象限该
 * 显示原图哪个角的颜色"。这个参照模型和被测函数除了都读同一个 CTM 输入之外，
 * 没有共享任何代码/推导路径，两者算出来的结果一致，才是"翻转/旋转逻辑写对了"的
 * 证据。
 */
@RunWith(RobolectricTestRunner::class)
class PdfTextExtractorImageOrientationTest {

    private enum class Corner { TL, TR, BL, BR }

    private val cornerColors = mapOf(
        Corner.TL to Color.RED,
        Corner.TR to Color.GREEN,
        Corner.BL to Color.BLUE,
        Corner.BR to Color.YELLOW,
    )

    /** 40×40 正方形测试图：左上红、右上绿、左下蓝、右下黄，四象限各 20×20。 */
    private fun buildQuadrantBitmap(): Bitmap {
        val size = 40
        val half = size / 2
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val color = when {
                    x < half && y < half -> cornerColors.getValue(Corner.TL)
                    x >= half && y < half -> cornerColors.getValue(Corner.TR)
                    x < half && y >= half -> cornerColors.getValue(Corner.BL)
                    else -> cornerColors.getValue(Corner.BR)
                }
                bitmap.setPixel(x, y, color)
            }
        }
        return bitmap
    }

    /** 取 [bitmap] 四个角（各留 15% 内边距避开翻转/旋转插值造成的边界模糊），按 [Corner.TL, TR, BL, BR] 顺序分类出最接近的象限色。 */
    private fun corners(bitmap: Bitmap): List<Corner> {
        val inset = 0.15f
        val near = (bitmap.width * inset).toInt().coerceAtLeast(1)
        val far = (bitmap.width * (1 - inset)).toInt().coerceAtMost(bitmap.width - 1)
        val nearY = (bitmap.height * inset).toInt().coerceAtLeast(1)
        val farY = (bitmap.height * (1 - inset)).toInt().coerceAtMost(bitmap.height - 1)
        return listOf(near to nearY, far to nearY, near to farY, far to farY)
            .map { (x, y) -> classify(bitmap.getPixel(x, y)) }
    }

    private fun classify(pixel: Int): Corner =
        cornerColors.entries.minBy { (_, color) -> colorDistance(pixel, color) }.key

    private fun colorDistance(a: Int, b: Int): Int {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return dr * dr + dg * dg + db * db
    }

    /**
     * 独立参照模型：不复用 [PdfTextExtractor.applyCtmOrientation] 的任何代码，直接
     * 照抄 PDF 规范"图片空间→页面空间"的线性映射公式，算出原始位图 [Corner.TL]/
     * [Corner.TR]/[Corner.BL]/[Corner.BR] 四个角分别落在 100×100 页面的哪个象限，
     * 返回"页面象限 -> 该象限应该显示哪个原始角的颜色"这张表——见类注释。
     *
     * 页面象限判断：`pageX<50` 为左、`pageY>50` 为上（页面坐标系 y 轴向上，
     * 大 y 值是视觉上的"上"）。
     */
    private fun referenceLayout(ctm: Matrix): Map<Corner, Corner> {
        // 图片空间的四个角，(x, y) 都是 0..1 的分数坐标。
        val imageCorners = mapOf(
            Corner.TL to (0.0 to 0.0),
            Corner.TR to (1.0 to 0.0),
            Corner.BL to (0.0 to 1.0),
            Corner.BR to (1.0 to 1.0),
        )
        val result = mutableMapOf<Corner, Corner>()
        for ((rawCorner, xy) in imageCorners) {
            val (x, y) = xy
            val pageX = ctm.scaleX * x + ctm.shearX * y + ctm.translateX
            val pageY = ctm.shearY * x + ctm.scaleY * y + ctm.translateY
            val pageQuadrant = when {
                pageX < 50 && pageY > 50 -> Corner.TL
                pageX >= 50 && pageY > 50 -> Corner.TR
                pageX < 50 && pageY <= 50 -> Corner.BL
                else -> Corner.BR
            }
            result[pageQuadrant] = rawCorner
        }
        return result
    }

    /**
     * CTM 参数用 PDF 标准的 `a b c d e f` 顺序（[Matrix] 构造函数同样的顺序），
     * `e`/`f` 取值让每种 CTM 摆出来的图片都恰好铺满 100×100 的页面——这样
     * [referenceLayout] 算出来的四个角必然落在四个不同象限，不会有歧义。
     */
    private val ctmCases = listOf(
        "不翻转(canonical)" to Matrix(100f, 0f, 0f, -100f, 0f, 100f),
        "水平镜像" to Matrix(-100f, 0f, 0f, -100f, 100f, 100f),
        "垂直镜像" to Matrix(100f, 0f, 0f, 100f, 0f, 0f),
        "180度旋转" to Matrix(-100f, 0f, 0f, 100f, 100f, 0f),
        "90度旋转变体1" to Matrix(0f, 100f, 100f, 0f, 0f, 0f),
        "90度旋转变体2" to Matrix(0f, -100f, -100f, 0f, 100f, 100f),
        "90度旋转变体3" to Matrix(0f, 100f, -100f, 0f, 100f, 0f),
        "90度旋转变体4" to Matrix(0f, -100f, 100f, 0f, 0f, 100f),
    )

    /** [Corner] 在 40×40 源位图里的像素坐标（不是分数坐标，配合 [Matrix.mapPoints] 用）。 */
    private val pixelCorners = mapOf(
        Corner.TL to (0f to 0f),
        Corner.TR to (40f to 0f),
        Corner.BL to (0f to 40f),
        Corner.BR to (40f to 40f),
    )

    /** 把 [Matrix.mapPoints] 映射出来的像素坐标分类成 40×40 画布的哪个角。 */
    private fun classifyMappedPixel(x: Float, y: Float): Corner = when {
        x < 20 && y < 20 -> Corner.TL
        x >= 20 && y < 20 -> Corner.TR
        x < 20 && y >= 20 -> Corner.BL
        else -> Corner.BR
    }

    @Test
    fun `8 种轴对齐 CTM 下 orientationMatrixOrNull 算出的矩阵都和独立参照模型算出的象限映射一致`() {
        for ((label, ctm) in ctmCases) {
            val expectedLayout = referenceLayout(ctm) // 页面象限 -> 该显示哪个原始角
            val matrix = PdfTextExtractor.orientationMatrixOrNull(ctm, width = 40, height = 40)

            // matrix 为 null 表示"不需要修正"，等价于恒等矩阵——直接用原始像素坐标。
            val actualLayout = mutableMapOf<Corner, Corner>()
            for ((rawCorner, xy) in pixelCorners) {
                val pts = floatArrayOf(xy.first, xy.second)
                matrix?.mapPoints(pts)
                actualLayout[classifyMappedPixel(pts[0], pts[1])] = rawCorner
            }

            assertEquals(
                "[$label] orientationMatrixOrNull 算出的矩阵映射结果应该和参照模型一致",
                expectedLayout,
                actualLayout,
            )
        }
    }

    @Test
    fun `不翻转的 canonical CTM 下修正结果四角顺序确实是左上红右上绿左下蓝右下黄`() {
        // 上面那条测试只保证"和参照模型一致"，不直接暴露具体颜色顺序——这条补一个
        // 具体断言，双重锁定这套验证方法本身可信：canonical CTM 下不应该做任何
        // 修正，四角应该还是原始位图本来的样子。
        val corrected = PdfTextExtractor.applyCtmOrientation(
            buildQuadrantBitmap(),
            Matrix(100f, 0f, 0f, -100f, 0f, 100f),
        )
        assertEquals(listOf(Corner.TL, Corner.TR, Corner.BL, Corner.BR), corners(corrected))
    }

    @Test
    fun `非轴对齐的任意角度旋转 CTM 不在修复范围内，原样返回不崩溃`() {
        // 见 PdfTextExtractor 类注释"已知局限"一节：a/b/c/d 四个分量都明显非零
        // （这里用 30° 旋转）时不做修正，直接返回原始 Bitmap 本身。
        val radians = Math.toRadians(30.0)
        val cos = Math.cos(radians).toFloat()
        val sin = Math.sin(radians).toFloat()
        val ctm = Matrix(100f * cos, 100f * sin, -100f * sin, 100f * cos, 50f, 20f)
        val source = buildQuadrantBitmap()

        val result = PdfTextExtractor.applyCtmOrientation(source, ctm)

        assertTrue("非轴对齐 CTM 应该原样返回同一个 Bitmap，不做修正", result === source)
    }

    // ------------------------------------------------------------------
    // 下面是端到端的集成回归测试：用已经证明能可靠解码的真实 PNG fixture
    // （沿用 PdfTextExtractorImageTest 的 tiny.png），验证内嵌图片抽取整条链路
    // （PdfTextExtractor.extractContent → ImageDrawStreamEngine → drawImage 回调
    // → applyCtmOrientation）接起来能跑、不崩溃、抽取数量正确——不断言具体朝向
    // （tiny.png 的具体内容未知，断言朝向没有意义），朝向的精确性已经由上面几条
    // 直接测 applyCtmOrientation 的用例覆盖。
    // ------------------------------------------------------------------

    @Test
    fun `真实图片经过翻转 CTM 画出来，端到端抽取链路不崩溃、能正常拿到 Bitmap`() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)

        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val pngBytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("tiny.png")?.readBytes()) {
            "找不到测试用的 tiny.png"
        }
        val imageXObject: PDImageXObject = PDImageXObject.createFromByteArray(document, pngBytes, "tiny")

        // 故意用一个带水平镜像的 CTM（a<0）——这是这次修复要覆盖的场景。
        val flippedCtm = Matrix(-100f, 0f, 0f, -100f, 200f, 200f)
        PDPageContentStream(document, page).use { stream ->
            stream.drawImage(imageXObject, flippedCtm)
        }

        val output = File.createTempFile("flipped-image-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()

        val content = PdfTextExtractor.extractContent(context, output)
        assertEquals(1, content.images.size)
        val bitmap = content.images.single().bitmap
        assertTrue(bitmap.width > 0)
        assertTrue(bitmap.height > 0)
    }
}
