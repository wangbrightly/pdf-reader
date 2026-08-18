package app.pdfreader.progress

import java.security.MessageDigest

/**
 * 用抽取出的段落内容算一个稳定的文件标识（SHA-256 十六进制串），当阅读进度的 key。
 *
 * ## 为什么不直接用 content:// Uri 字符串当 key
 * SAF 返回的 Uri 在"同一次选中的文件重新打开"这个主要场景下通常是稳定的，实现起来
 * 也最省事（不用额外计算），但有两个局限，权衡下来觉得不够稳：
 * 1. 不是"文件内容"级别的标识——同名/同路径文件被换了内容，Uri 字符串可能不变，
 *    会读到过期的进度，把新文件的显示位置滚到跟内容对不上的地方。
 * 2. 不保证跨设备/重装 App 后还一样（持久化 Uri 授权依赖系统 SAF 实现细节，
 *    不是本 App 能控制的稳定契约）。
 *
 * ## 为什么选内容 hash，而且这个代价能接受
 * [app.pdfreader.MainActivity.loadPdf] 成功后，抽取出的段落本来就要缓存在内存里
 * 供字号/边距变化时重新 [app.pdfreader.reflow.reflow]（见 MainActivity 类注释），
 * 这里只是多算一次 SHA-256——不需要重新读取 PDF 原始字节流、不需要额外文件 I/O，
 * 只是对已经在内存里的字符串再做一次哈希，代价很低。换来"内容变了 key 就跟着变"，
 * 比 Uri 字符串更准确地回答"这是不是同一份文件"这个问题。
 *
 * ## 已知局限
 * 两份内容完全相同的不同 PDF（比如同一份文件复制了两份分别打开）会共享同一个 key、
 * 共享同一份阅读进度——这是"用内容而不是路径/身份标识文件"的必然取舍。可以接受：
 * 对用户来说，两份内容一样的文件本来就该显示"读到同一个位置"，不算错误行为。
 */
object ReadingProgressKey {
    fun fromParagraphs(paragraphs: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // 用 \n 连接各段落再编码，而不是直接 concat：避免"AB"+"C" 和 "A"+"BC" 这类
        // 拼接结果相同但段落切分方式不同的输入被误判成同一份文件内容。
        val joined = paragraphs.joinToString("\n")
        val bytes = digest.digest(joined.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }
}
