package app.pdfreader.extract

import app.pdfreader.ui.DisplayBlock
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 见 [PdfTextExtractor.Session] 里 `documentLock` KDoc"读锁"一节明确留下的
 * 未验证问题："loadPage 之间会不会互相踩到彼此，是这次改动之前就存在的、
 * 没有实证过的另一个问题"。2026-08-24 那一轮并发排查只受控实验过"后台线程
 * vs loadPage"这一种组合（[PdfjsEdgeCaseFixturesTest] 里的 JBIG2Globals 测试），
 * `PdfPageAdapter.loadExecutor` 本来就是固定 3 线程池、设计成让最多 3 个页面
 * 真正并发解码（见该类 KDoc `LOAD_POOL_SIZE`），"loadPage 之间互相并发"从
 * `documentLock` 换成读写锁那一刻起就被恢复成"跟改动前完全一样的并发度"，
 * 但"改动前的并发度本身安不安全"从来没有独立验证过。
 *
 * 这里直接照抄真实场景的并发形状：6 页文档，每页一张不同的真实 CMYK/YCCK
 * 图片（复用已有 fixture，覆盖 transform=0 反色/不反色、YCCK 1×1、YCCK 子
 * 采样几种真正会触发 [JpegDecoder] 走不同代码路径的组合，不是随便凑 6 张同
 * 样的图——如果不同分量的采样/换算路径之间有隐藏的共享可变状态，用同样的
 * 图片测不出来），3 个线程各自反复对不同页面调 `loadPage`，检查：
 * 1. 全程不抛出未捕获异常。
 * 2. 每一页解出来的图片数量/尺寸在多次并发调用之间保持一致（不一致就是
 *    数据被并发访问污染的直接证据，跟 [PdfjsEdgeCaseFixturesTest] 里
 *    JBIG2Globals 那次"图片=2 文字=0"变成"图片=0 文字=300"是同一类信号）。
 */
@RunWith(RobolectricTestRunner::class)
class SessionConcurrentLoadPageTest {

    private fun loadBytes(name: String): ByteArray = requireNotNull(
        javaClass.classLoader?.getResourceAsStream(name)?.readBytes(),
    ) { "找不到测试 fixture：src/test/resources/$name" }

    private data class PageSpec(val jpegBytes: ByteArray, val width: Int, val height: Int)

    /** 建一份多页文档，每页嵌一张不同的真实 CMYK/YCCK 图片。 */
    private fun buildMultiPageDocument(pages: List<PageSpec>): File {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val document = PDDocument()
        for (spec in pages) {
            val page = PDPage()
            document.addPage(page)
            val cosStream = document.document.createCOSStream()
            cosStream.createOutputStream().use { it.write(spec.jpegBytes) }
            cosStream.setItem(COSName.TYPE, COSName.XOBJECT)
            cosStream.setItem(COSName.SUBTYPE, COSName.IMAGE)
            cosStream.setInt(COSName.WIDTH, spec.width)
            cosStream.setInt(COSName.HEIGHT, spec.height)
            cosStream.setInt(COSName.BITS_PER_COMPONENT, 8)
            cosStream.setItem(COSName.FILTER, COSName.DCT_DECODE)
            cosStream.setItem(COSName.COLORSPACE, COSName.DEVICECMYK)
            val image = PDImageXObject(PDStream(cosStream), PDResources())
            PDPageContentStream(document, page).use { it.drawImage(image, 0f, 0f, 100f, 100f) }
        }
        val output = File.createTempFile("concurrent-loadpage-doc", ".pdf")
        output.deleteOnExit()
        document.save(output)
        document.close()
        return output
    }

    @Test
    fun `loadPage 之间3线程并发反复调用 不抛异常且结果稳定`() {
        val pageSpecs = listOf(
            PageSpec(loadBytes("cmyk-quadrant-64.jpg"), 64, 64),
            PageSpec(loadBytes("cmyk-book-noinv.jpg"), 1725, 955),
            PageSpec(loadBytes("cmyk-ycck-book.jpg"), 767, 2159),
            PageSpec(loadBytes("cmyk-ycck-subsampled.jpg"), 263, 256),
            PageSpec(loadBytes("cmyk-gradient-50.jpg"), 50, 50),
            PageSpec(loadBytes("cmyk-restart-128.jpg"), 128, 128),
        )
        val file = buildMultiPageDocument(pageSpecs)
        val context = RuntimeEnvironment.getApplication()

        PdfTextExtractor.Session.open(context, file).use { session ->
            session.awaitFooterLearningForTest()
            session.awaitOutlineForTest()

            // 每一页先跑一次单线程基准，记下"应该长什么样"，后面并发结果照这个比对。
            val baseline = (1..session.pageCount).associateWith { pageNo ->
                val blocks = session.loadPage(pageNo).blocks
                blocks.filterIsInstance<DisplayBlock.Image>().map { it.bitmap.width to it.bitmap.height }
            }

            val iterationsPerThread = 60
            // 2026-08-25/26：PdfPageAdapter.LOAD_POOL_SIZE 中间短暂改成过 1（见该
            // 常量 KDoc"先改1隔天又改回3"一节），最终定在 3——NOTES #41 把图片
            // 解码挪到 documentLock 外面之后，3 个线程重新有了真实的并发收益。
            // 这里的 3 现在又对应真实池子大小了；即使以后又不对应，也应该继续
            // 保留至少这个量级的并发压力测试——真机数据损坏就是当年 3 线程并发
            // 撞出来的（见类 KDoc），这条测试要验证的是 documentLock 本身的互斥
            // 保证依然成立，不是单纯跟着 LOAD_POOL_SIZE 的值走。
            val threadCount = 3
            val executor = Executors.newFixedThreadPool(threadCount)
            val startLatch = CountDownLatch(1)
            val mismatchCount = AtomicInteger(0)
            val exceptionCount = AtomicInteger(0)
            val mismatchDetails = java.util.Collections.synchronizedList(mutableListOf<String>())

            val futures = (0 until threadCount).map { threadIndex ->
                executor.submit {
                    startLatch.await()
                    for (i in 0 until iterationsPerThread) {
                        // 3 个线程按不同起点错开页码，制造"同一时刻不同线程处理不同页"
                        // 的真实场景（PdfPageAdapter 就是这样：可见的几个页面一起提交）。
                        val pageNo = ((threadIndex + i) % session.pageCount) + 1
                        try {
                            val blocks = session.loadPage(pageNo).blocks
                            val images = blocks.filterIsInstance<DisplayBlock.Image>()
                                .map { it.bitmap.width to it.bitmap.height }
                            if (images != baseline[pageNo]) {
                                mismatchCount.incrementAndGet()
                                mismatchDetails.add(
                                    "page=$pageNo thread=$threadIndex 基准=${baseline[pageNo]} 本次=$images",
                                )
                            }
                        } catch (t: Throwable) {
                            exceptionCount.incrementAndGet()
                            mismatchDetails.add("page=$pageNo thread=$threadIndex 抛出异常: $t")
                        }
                    }
                }
            }
            startLatch.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
            executor.shutdown()

            println(
                "loadPage 并发测试：$threadCount 线程 × $iterationsPerThread 次，" +
                    "不一致=${mismatchCount.get()} 异常=${exceptionCount.get()}\n" +
                    mismatchDetails.take(20).joinToString("\n"),
            )
            assertTrue(
                "loadPage 并发调用出现异常或结果不一致，说明 loadPage 之间并发不安全，" +
                    "详情见上面 println：$mismatchDetails",
                mismatchCount.get() == 0 && exceptionCount.get() == 0,
            )
        }
    }
}
