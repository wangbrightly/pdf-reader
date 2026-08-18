package app.pdfreader.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [ReadingProgressKey] 的单元测试——用抽取出的段落内容算一个稳定的文件标识。
 *
 * 纯函数，不需要 Context，普通 JVM 单元测试即可（不用 Robolectric）。
 */
class ReadingProgressKeyTest {

    @Test
    fun `相同段落内容算出相同 key`() {
        val key1 = ReadingProgressKey.fromParagraphs(listOf("第一段", "第二段"))
        val key2 = ReadingProgressKey.fromParagraphs(listOf("第一段", "第二段"))

        assertEquals(key1, key2)
    }

    @Test
    fun `不同段落内容算出不同 key`() {
        val key1 = ReadingProgressKey.fromParagraphs(listOf("第一段", "第二段"))
        val key2 = ReadingProgressKey.fromParagraphs(listOf("第一段", "第三段"))

        assertNotEquals(key1, key2)
    }

    @Test
    fun `段落切分不同但拼接后相同的内容不会算出同一个 key`() {
        // "AB" + "C" 和 "A" + "BC" 拼接后都是 "ABC"，用换行符分隔再编码可以区分开，
        // 不会把两份实际上切分方式不同的内容误判成同一份文件。
        val key1 = ReadingProgressKey.fromParagraphs(listOf("AB", "C"))
        val key2 = ReadingProgressKey.fromParagraphs(listOf("A", "BC"))

        assertNotEquals(key1, key2)
    }

    @Test
    fun `空段落列表也能稳定算出同一个 key`() {
        val key1 = ReadingProgressKey.fromParagraphs(emptyList())
        val key2 = ReadingProgressKey.fromParagraphs(emptyList())

        assertEquals(key1, key2)
    }
}
