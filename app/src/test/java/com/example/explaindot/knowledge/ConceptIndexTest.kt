package com.example.explaindot.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分组逻辑的单测。
 *
 * 用的是一个假排序器（首字母取字符本身、比较用字面序），不涉及拼音 ——
 * 见 [TermOrdering] 的注释：真正的拼音排序依赖 Android 的 ICU，在 JVM 上
 * 跑出来的行为不一样，测它只会得到一份骗人的绿灯。
 * 这里要盖住的是**分组、去重、排序、兜底**这些和拼音无关的部分。
 */
private object FakeOrdering : TermOrdering {
    override fun letterOf(term: String): String {
        val c = term.firstOrNull() ?: return ConceptIndex.OTHER_GROUP
        return if (c in 'A'..'Z' || c in 'a'..'z') c.uppercaseChar().toString()
        else ConceptIndex.OTHER_GROUP
    }

    override fun compare(a: String, b: String): Int = a.compareTo(b)
}

class ConceptIndexTest {

    // ---------------------------------------------------------------- 分组

    @Test
    fun `按首字母分组`() {
        val groups = ConceptIndex.group(listOf("Apple", "Banana", "Avocado"), FakeOrdering)
        assertEquals(listOf("A", "B"), groups.map { it.letter })
        assertEquals(listOf("Apple", "Avocado"), groups[0].terms)
        assertEquals(listOf("Banana"), groups[1].terms)
    }

    @Test
    fun `字母按升序排列`() {
        val groups = ConceptIndex.group(listOf("Zebra", "Apple", "Moon"), FakeOrdering)
        assertEquals(listOf("A", "M", "Z"), groups.map { it.letter })
    }

    @Test
    fun `其他组永远排在最后`() {
        // 数字和符号开头的不该插在字母中间
        val groups = ConceptIndex.group(listOf("123", "#tag", "Apple", "Zebra"), FakeOrdering)
        assertEquals(listOf("A", "Z", ConceptIndex.OTHER_GROUP), groups.map { it.letter })
        // 断言确切顺序而不是「排过序的等于…」—— 后者会把 group() 有没有排好这件事盖住
        assertEquals(listOf("#tag", "123"), groups.last().terms)
    }

    @Test
    fun `只有非空的组`() {
        // 字母表上摆一堆空组只会让用户多滑几屏
        val groups = ConceptIndex.group(listOf("Apple"), FakeOrdering)
        assertEquals(1, groups.size)
        assertEquals("A", groups.single().letter)
    }

    @Test
    fun `组内按排序器排好`() {
        // 同组的两个词输入是乱序，出来必须排好；A 组在前（字母升序）
        val groups = ConceptIndex.group(listOf("Cherry", "Apple", "Cranberry"), FakeOrdering)
        assertEquals(listOf("A", "C"), groups.map { it.letter })
        val cGroup = groups.first { it.letter == "C" }
        assertEquals(listOf("Cherry", "Cranberry"), cGroup.terms)
    }

    @Test
    fun `大小写不同的首字母归到同一组`() {
        val groups = ConceptIndex.group(listOf("apple", "Avocado", "APPLE2"), FakeOrdering)
        assertEquals(1, groups.size)
        assertEquals("A", groups.single().letter)
        assertEquals(3, groups.single().terms.size)
    }

    // ---------------------------------------------------------------- 去重与清洗

    @Test
    fun `重复的概念只出现一次`() {
        val groups = ConceptIndex.group(listOf("Apple", "Apple", "Apple"), FakeOrdering)
        assertEquals(listOf("Apple"), groups.single().terms)
    }

    @Test
    fun `空串和纯空白被丢掉`() {
        // 概念名理论上不会是空的，但库里可能留着历史数据 ——
        // 一个空名字在列表里既没法显示也没法点
        val groups = ConceptIndex.group(listOf("Apple", "", "   ", "\n"), FakeOrdering)
        assertEquals(1, groups.size)
        assertEquals(listOf("Apple"), groups.single().terms)
    }

    @Test
    fun `首尾空白被清掉后再去重`() {
        val groups = ConceptIndex.group(listOf("  Apple  ", "Apple"), FakeOrdering)
        assertEquals(listOf("Apple"), groups.single().terms)
    }

    @Test
    fun `空输入不产生任何组`() {
        assertTrue(ConceptIndex.group(emptyList(), FakeOrdering).isEmpty())
        assertTrue(ConceptIndex.group(listOf("", "  "), FakeOrdering).isEmpty())
    }

    @Test
    fun `所有组加起来等于输入里有效概念的个数`() {
        val input = listOf("Apple", "Banana", "1x", "Cherry", "Apple", "")
        val total = ConceptIndex.group(input, FakeOrdering).sumOf { it.terms.size }
        assertEquals(4, total)
    }

    // ---------------------------------------------------------------- 过滤

    @Test
    fun `空查询返回全部`() {
        val terms = listOf("Apple", "Banana")
        assertEquals(terms, ConceptIndex.filter(terms, ""))
        assertEquals(terms, ConceptIndex.filter(terms, "   "))
    }

    @Test
    fun `子串匹配而不是前缀匹配`() {
        // 搜「场」要能搜出「市场机制」—— 用户记得住的往往是中间那几个字
        val terms = listOf("市场机制", "场论", "粒子")
        assertEquals(listOf("市场机制", "场论"), ConceptIndex.filter(terms, "场"))
    }

    @Test
    fun `英文不区分大小写`() {
        val terms = listOf("GDP", "gdp growth", "CPI")
        // 用户不会记得库里存的是大写还是小写
        assertEquals(listOf("GDP", "gdp growth"), ConceptIndex.filter(terms, "gdp"))
        assertEquals(listOf("GDP", "gdp growth"), ConceptIndex.filter(terms, "GDP"))
    }

    @Test
    fun `查不到就是空`() {
        assertTrue(ConceptIndex.filter(listOf("Apple", "Banana"), "Zebra").isEmpty())
    }

    @Test
    fun `查询串首尾空白被忽略`() {
        val terms = listOf("Apple", "Banana")
        assertEquals(listOf("Apple"), ConceptIndex.filter(terms, "  App  "))
    }

    @Test
    fun `过滤之后仍然能正常分组`() {
        val filtered = ConceptIndex.filter(listOf("Apple", "Banana", "Cherry"), "an")
        // Banana 含 "an"，其余不含
        assertEquals(listOf("Banana"), filtered)
        val groups = ConceptIndex.group(filtered, FakeOrdering)
        assertEquals(listOf("B"), groups.map { it.letter })
    }

    @Test
    fun `过滤不排序 保持原有顺序`() {
        // 输入顺序 Banana → Apple 本身不是字母序。如果 filter 顺手排了序，
        // 结果会变成 Apple → Banana，这个断言就会失败。
        // 过滤和排序是两件事，分开才好在别处改排序规则而不影响过滤
        val terms = listOf("Banana", "Apple", "Cherry")
        assertEquals(listOf("Banana", "Apple"), ConceptIndex.filter(terms, "a"))
    }
}
