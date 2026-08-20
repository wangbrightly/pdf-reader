package app.pdfreader.progress

import java.io.File
import java.security.MessageDigest

/**
 * 用文件字节内容算一个稳定的文件标识（SHA-256 十六进制串），当阅读进度的 key。
 *
 * ## 为什么不直接用 content:// Uri 字符串当 key
 * SAF 返回的 Uri 在"同一次选中的文件重新打开"这个主要场景下通常是稳定的，实现起来
 * 也最省事（不用额外计算），但有两个局限，权衡下来觉得不够稳：
 * 1. 不是"文件内容"级别的标识——同名/同路径文件被换了内容，Uri 字符串可能不变，
 *    会读到过期的进度，把新文件的显示位置滚到跟内容对不上的地方。
 * 2. 不保证跨设备/重装 App 后还一样（持久化 Uri 授权依赖系统 SAF 实现细节，
 *    不是本 App 能控制的稳定契约）。
 *
 * ## 2026-08-20 从"哈希抽取出的段落"改成"哈希文件字节"
 *
 * 最初这里哈希的是 [app.pdfreader.extract.PdfTextExtractor.extractContent] 抽取出的
 * 全部段落——那时候"抽完全部段落"本来就是打开文档必经的一步，顺手用内存里已有的
 * 字符串再算一次哈希代价很低。"文字/图片按需加载"（按页加载，见
 * [app.pdfreader.extract.PdfTextExtractor.Session]）上线后这个前提不再成立：现在
 * 只有真正翻到某一页才会抽取那一页的文字，"打开文档"这一步不再有"全部段落"可用，
 * 等这些段落齐了才能算 key，等于又逼着打开阶段把全文档抽一遍，跟按需加载的目标
 * 直接冲突。
 *
 * 改成直接对文件字节（[loadFile]，[app.pdfreader.MainActivity.copyToCacheFile]
 * 拷贝出来的临时文件）做 SHA-256——文件字节在 `loadPdf` 一开始就有，不需要等任何
 * 抽取步骤，语义上也更直接（内容完全一致的文件被认成同一份，不再依赖"抽取出的
 * 文字恰好一致"这个中间层）。
 *
 * ## 已知局限（跟旧版正好反过来，取舍换了方向）
 * 两份文件字节不同、但抽取出的文字完全一样的 PDF（比如同一份内容用不同工具重新
 * 导出、压缩参数不同）不再共享同一条阅读进度——旧版是反过来（字节不同、文字一样
 * 会共享）。选文件字节是因为它不需要等任何抽取步骤就能算出来，按需加载这个硬约束
 * 下没有回旋余地。
 */
object ReadingProgressKey {
    private const val READ_BUFFER_SIZE = 8192

    fun fromFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
