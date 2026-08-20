package app.pdfreader.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * [ReadingProgressKey] 的单元测试——用文件字节内容算一个稳定的文件标识（2026-08-20
 * 从"哈希抽取出的段落"改成"哈希文件字节"，见该类 KDoc 完整背景：按需加载上线后
 * "打开文档"这一步不再有"全部段落"可用）。
 *
 * 纯函数，不需要 Context，普通 JVM 单元测试即可（不用 Robolectric）。
 */
class ReadingProgressKeyTest {

    private fun tempFileWithContent(bytes: ByteArray): File {
        val file = File.createTempFile("reading-progress-key-test", ".bin")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun `相同文件字节算出相同 key`() {
        val bytes = "第一段\n第二段".toByteArray(Charsets.UTF_8)
        val key1 = ReadingProgressKey.fromFile(tempFileWithContent(bytes))
        val key2 = ReadingProgressKey.fromFile(tempFileWithContent(bytes))

        assertEquals(key1, key2)
    }

    @Test
    fun `不同文件字节算出不同 key`() {
        val key1 = ReadingProgressKey.fromFile(tempFileWithContent("第一段".toByteArray(Charsets.UTF_8)))
        val key2 = ReadingProgressKey.fromFile(tempFileWithContent("第三段".toByteArray(Charsets.UTF_8)))

        assertNotEquals(key1, key2)
    }

    @Test
    fun `空文件也能稳定算出同一个 key`() {
        val key1 = ReadingProgressKey.fromFile(tempFileWithContent(ByteArray(0)))
        val key2 = ReadingProgressKey.fromFile(tempFileWithContent(ByteArray(0)))

        assertEquals(key1, key2)
    }

    @Test
    fun `跨越读取缓冲区大小的大文件也能正确算出哈希`() {
        // READ_BUFFER_SIZE 是 8192——构造一份明显更大的文件，确认分块读取不会算错。
        val bytes = ByteArray(20000) { (it % 256).toByte() }
        val key1 = ReadingProgressKey.fromFile(tempFileWithContent(bytes))
        val key2 = ReadingProgressKey.fromFile(tempFileWithContent(bytes))

        assertEquals(key1, key2)
    }
}
