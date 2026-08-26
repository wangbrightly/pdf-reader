package app.pdfreader.extract

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * 2026-08-20 真机反馈修复："一页正常竖版图片被显示成好几张变形的横条图"。
 *
 * ## 根因：有些 PDF 把一整页扫描图切成等宽竖条分别嵌入
 *
 * 真机诊断日志实测：一份 90 页的扫描版书，每一页并不是一张完整的内嵌图片，而是
 * 6-7 张——比如 `125x1078, 125x1078, 125x1078, 125x1078, 125x1078, 125x1078,
 * 19x1078`：前 6 张宽度完全相同（125px）、高度也完全相同（1078px），最后一张
 * 宽度明显更窄（19px，是"整除不尽的余数"）。这是典型的"图片平铺切片"指纹——
 * 某些扫描/压缩工具会把一整页图片切成若干等宽竖条，可能是为了绕过单张图片
 * 大小/内存限制，PDF 里仍然是"挨个画在正确位置"，肉眼看是完整的一页图。
 *
 * [PageContentStreamEngine] 目前遇到几张图片就存几张，[MainActivity.createImageView]
 * 对每一张图独立"撑满可用宽度"显示——一条只占原图 1/6 宽度的窄竖条被单独拉伸到
 * 全屏宽度，效果就是好几张被横向拉得很扭曲的图片堆在一起，肉眼看起来像"变成
 * 横版了"。
 *
 * ## 修法：识别出"平铺切片"模式就拼回一整张，认不出就原样返回
 *
 * 判断标准（都满足才拼接，任一条件不满足就原样返回，不强行拼接可能不相关的图片）：
 * 1. 至少 [MIN_STRIPS] 张图片——2 张同高度的图片更可能是页面上刻意并排的两张
 *    独立图片（比如"前后对比图"），不该被强行拼接；至少 3 张挨在一起才是"切片"
 *    而不是"巧合"的强信号，跟本文件其它检测器（[TableGridDetector] 的
 *    `MIN_GRID_LINES`）同一个保守精神。
 * 2. 全部图片高度完全相同——同一页切出来的竖条，高度天然一致；高度不一致更可能
 *    是页面上互不相关的图片。
 * 3. 除最后一张外，宽度也完全相同——"等宽切片+最后一条余数"是切片工具的典型
 *    产出模式；最后一张宽度不能超过前面的统一宽度（超过说明不是"余数"，模式不对）。
 * 4. 高宽比够"窄"（见 [MIN_STRIP_ASPECT_RATIO]）——2026-08-26 真机反馈修复：
 *    一份年报"Board of Directors"页真机复现过反例，10 张董事头像（每张
 *    318×353，高宽比约 1.1，接近正方形）凑巧同时满足上面 1-3 三条（都是同一
 *    批次导出、尺寸统一的证件照），被误判成"切片"，10 个人的照片被强行横向
 *    拼接成一张 3180×353 的宽图再整体压缩显示，效果是好几个人的脸糊在一起、
 *    整体发暗发黑——用户反馈"颜色不对"最初以为是 CMYK 解码问题，装机排查
 *    到这条 KDoc"根因"一节描述的真实切片样本（125×1078，高宽比约 8.6）
 *    时才意识到：真正的切片在设计上高宽比必然悬殊（宽度是页面宽度切出来的
 *    1/6~1/8，高度是整页高度），不会是这种接近正方形的证件照比例——这个
 *    形状差异是切片这个生成机制本身决定的，不是巧合凑出来的两个数据点，
 *    加一道高宽比门槛能同时保住原始场景、排除这次的假阳性。
 *
 * 拼接顺序沿用 PDFBox 抽取出来的原始顺序（[PageContentStreamEngine] 遇到 `Do`
 * 操作符的顺序）——没有记录每张图片在页面上的精确坐标（CTM 平移分量），假设切片
 * 工具画图的顺序就是从左到右的视觉顺序，这是本次修复没有验证、但对真机实测到的
 * 这份文档成立的假设；如果以后遇到画图顺序跟视觉顺序不一致的反例，需要改成读取
 * CTM 平移分量来精确定位，工作量更大，这次不做。
 */
object ImageStripStitcher {
    private const val MIN_STRIPS = 3

    /**
     * 见类 KDoc"判断标准"第 4 条——`height / width` 至少要到这个倍数才当"窄条"处理。
     * 真实切片样本是约 8.6（125×1078），真实反例（证件照）是约 1.1（318×353），
     * 两者之间有巨大空档，3 倍这个门槛离两头都留了很宽的安全余量，不是贴着任何
     * 一个样本的边界值凑出来的。
     */
    private const val MIN_STRIP_ASPECT_RATIO = 3f

    /**
     * 传入一页原始抽取出的图片列表，识别出"等宽切片"模式就返回拼接后的单张图片
     * （列表只有 1 个元素），识别不出就原样返回 [images]（包括 `images.size <= 1`
     * 的正常情况，此时也是原样返回）。
     */
    fun stitchIfTiled(images: List<Bitmap>): List<Bitmap> {
        if (images.size < MIN_STRIPS) return images
        val height = images.first().height
        if (images.any { it.height != height }) return images
        val body = images.dropLast(1)
        val bodyWidth = body.first().width
        if (body.any { it.width != bodyWidth }) return images
        val last = images.last()
        if (last.width > bodyWidth) return images
        if (height < bodyWidth * MIN_STRIP_ASPECT_RATIO) return images

        val totalWidth = bodyWidth * body.size + last.width
        val combined = Bitmap.createBitmap(totalWidth, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        var x = 0
        for (strip in images) {
            canvas.drawBitmap(strip, x.toFloat(), 0f, null)
            x += strip.width
        }
        return listOf(combined)
    }
}
