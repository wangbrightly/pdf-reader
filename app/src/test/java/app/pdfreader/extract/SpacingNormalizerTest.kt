package app.pdfreader.extract

import org.junit.Assert.assertEquals
import org.junit.Test

class SpacingNormalizerTest {

    @Test
    fun `中文字符之间的多余空格被去掉`() {
        assertEquals("中文", normalizeCjkSpacing("中 文"))
        assertEquals("中文测试", normalizeCjkSpacing("中文  测试"))
    }

    @Test
    fun `中文和数字之间没有空格时会补上一个`() {
        assertEquals("血压 127", normalizeCjkSpacing("血压127"))
        assertEquals("127 血压", normalizeCjkSpacing("127血压"))
    }

    @Test
    fun `中文和英文字母之间没有空格时会补上一个`() {
        assertEquals("mmHg 中文", normalizeCjkSpacing("mmHg中文"))
        assertEquals("中文 mmHg", normalizeCjkSpacing("中文mmHg"))
    }

    @Test
    fun `中文和数字字母之间已有单个空格时保持不变`() {
        assertEquals("abc 中文", normalizeCjkSpacing("abc 中文"))
    }

    @Test
    fun `中文和数字字母之间的多个空格会收成一个`() {
        assertEquals("abc 中文", normalizeCjkSpacing("abc   中文"))
    }

    @Test
    fun `英文单词之间的多余空格会收成一个，但不会凭空插入`() {
        assertEquals("abc def", normalizeCjkSpacing("abc   def"))
        assertEquals("abcdef", normalizeCjkSpacing("abcdef"))
    }

    @Test
    fun `开头结尾的空白会被去掉`() {
        assertEquals("中文", normalizeCjkSpacing("  中文  "))
    }

    @Test
    fun `空字符串和单字符原样返回`() {
        assertEquals("", normalizeCjkSpacing(""))
        assertEquals("中", normalizeCjkSpacing("中"))
        assertEquals("a", normalizeCjkSpacing("a"))
    }

    @Test
    fun `混合场景`() {
        assertEquals("血压 120mmHg 超标", normalizeCjkSpacing("血压120mmHg超标"))
    }
}
