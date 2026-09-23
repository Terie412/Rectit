package com.example.explaindot.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConceptMatcherTest {

    private fun spans(text: String, vararg terms: String) =
        ConceptMatcher(terms.toList()).matchesIn(text).map { text.substring(it.start, it.end) }

    // ---------------------------------------------------------------- 最长优先

    @Test
    fun `词相交时取更长的那个`() {
        // 「场」和「市场」都在库里，遇到「市场」只该链「市场」。
        // 这是需求里明确点出的一条
        assertEquals(listOf("市场"), spans("市场", "场", "市场"))
    }

    @Test
    fun `短词在别处仍然连`() {
        // 上一条的对照：别因为「市场」命中了就把「场」整个废掉。
        // 这里第二个「场」左边是逗号，边界成立，该连
        assertEquals(listOf("市场", "场"), spans("市场，场", "场", "市场"))
    }

    @Test
    fun `三个词嵌套时取最长`() {
        assertEquals(
            listOf("社会主义市场经济"),
            spans("社会主义市场经济", "市场", "社会主义", "社会主义市场经济")
        )
    }

    // ---------------------------------------------------------------- 单字边界

    @Test
    fun `单字在句首可以连`() {
        // 这是单字概念最常见的出现方式，必须连上，否则单字概念等于废了
        assertEquals(listOf("熵"), spans("熵是描述混乱程度的物理量", "熵"))
    }

    @Test
    fun `单字左边紧邻汉字时不连`() {
        // 库里只有「场」，读到「市场」不能连 —— 误连比不连更糟：
        // 点开会看到一份和当前语境无关的解释
        assertTrue(spans("市场的作用", "场").isEmpty())
        assertTrue(spans("一场梦", "场").isEmpty())
        assertTrue(spans("现场的观众", "场").isEmpty())
    }

    @Test
    fun `单字左边是标点空格或数字时都可以连`() {
        assertEquals(listOf("熵"), spans("“熵”", "熵"))
        assertEquals(listOf("熵"), spans("熵、温度与内能", "熵"))
        assertEquals(listOf("熵"), spans("热力学量 熵 的定义", "熵"))
    }

    @Test
    fun `单字右边紧邻汉字不影响命中`() {
        // 有意只看左边界：中文里「熵+是」「场+指」太常见，
        // 要求右边也断开会把最常见的情形全挡掉，单字概念就等于废了
        val result = ConceptMatcher(listOf("熵")).matchesIn("熵增")
        assertEquals(1, result.size)
        assertEquals(0, result[0].start)
        assertEquals(1, result[0].end)
    }

    @Test
    fun `单字左边是英文或数字时不连`() {
        assertTrue(spans("abcX", "X").isEmpty())
        assertTrue(spans("42X", "X").isEmpty())
    }

    // ---------------------------------------------------------------- 多字不做边界要求

    @Test
    fun `多字概念允许局部命中`() {
        // 「市场」命中在「市场经济」里，语义仍相关，不算误连。
        // 中文没有词边界，多字还要求断开就等于要求先做分词
        assertEquals(listOf("市场"), spans("市场经济", "市场"))
    }

    // ---------------------------------------------------------------- 位置与不重叠

    @Test
    fun `同一段文字里多处出现都要标出来`() {
        val result = ConceptMatcher(listOf("熵")).matchesIn("熵增加了，熵是不可逆的")
        assertEquals(2, result.size)
        assertEquals(0, result[0].start)
        assertEquals(listOf(0, 5), result.map { it.start })
    }

    @Test
    fun `命中的区间首尾正确且不重叠`() {
        val text = "市场与计划"
        val result = ConceptMatcher(listOf("市场", "计划")).matchesIn(text)
        assertEquals(2, result.size)
        assertEquals("市场", text.substring(result[0].start, result[0].end))
        assertEquals("计划", text.substring(result[1].start, result[1].end))
        // 后一段必须从前一段结束处之后开始
        assertTrue(result[1].start >= result[0].end)
    }

    @Test
    fun `连续重复的词会被切成两段而不是一段`() {
        // 命中后跳过整个词，所以 "ABAB" 是两处 AB，不是一处从 0 到 4 的怪东西
        val result = ConceptMatcher(listOf("AB")).matchesIn("ABAB")
        assertEquals(listOf(0 to 2, 2 to 4), result.map { it.start to it.end })
    }

    @Test
    fun `命中区间永远落在文本之内`() {
        val text = "熵"
        val result = ConceptMatcher(listOf("熵", "熵增原理")).matchesIn(text)
        assertEquals(1, result.size)
        assertEquals(0, result[0].start)
        assertEquals(1, result[0].end)
    }

    // ---------------------------------------------------------------- 退化情形

    @Test
    fun `词表为空不返回任何东西`() {
        assertTrue(ConceptMatcher(emptyList()).matchesIn("市场与熵").isEmpty())
        assertTrue(ConceptMatcher(emptyList()).isEmpty)
    }

    @Test
    fun `文本为空不返回任何东西`() {
        assertTrue(ConceptMatcher(listOf("熵")).matchesIn("").isEmpty())
    }

    @Test
    fun `词表里的空串被忽略`() {
        // 归一化之后可能留下空串（比如原本只有标点的概念名），不能让它到处命中
        val result = ConceptMatcher(listOf("", "熵")).matchesIn("熵")
        assertEquals(listOf("熵"), result.map { "熵".substring(it.start, it.end) })
    }

    @Test
    fun `库里没有的词不会被连上`() {
        assertTrue(spans("完全无关的一段话", "熵", "场").isEmpty())
    }

    // ---------------------------------------------------------------- 排除当前概念

    @Test
    fun `exclude 排掉正在看的那个概念`() {
        // 看「场」的解释时，正文里出现的「场」不该连 —— 点进去还是同一页，是死路
        val result = ConceptMatcher(listOf("场", "粒子")).matchesIn("场和粒子", exclude = "场")
        assertEquals(1, result.size)
        assertEquals("粒子", "场和粒子".substring(result[0].start, result[0].end))
    }

    @Test
    fun `exclude 只比完全相同的 不排掉更长的概念`() {
        // 看「场」时正文提到「场论」，那是另一个概念，必须连上
        val result = ConceptMatcher(listOf("场", "场论")).matchesIn("场论里的场", exclude = "场")
        assertEquals(listOf("场论"), result.map { "场论里的场".substring(it.start, it.end) })
    }

    @Test
    fun `exclude 为 null 时行为和以前一样`() {
        val withNull = ConceptMatcher(listOf("场")).matchesIn("场", null)
        val withoutArg = ConceptMatcher(listOf("场")).matchesIn("场")
        assertEquals(1, withNull.size)
        assertEquals(1, withoutArg.size)
    }

    @Test
    fun `exclude 传库里没有的词不影响任何命中`() {
        val result = ConceptMatcher(listOf("场")).matchesIn("场", exclude = "不存在")
        assertEquals(1, result.size)
    }

    @Test
    fun `exclude 排掉后不会漏掉其他位置的同类命中`() {
        // 两处都是「场」，全部排掉；中间的「粒子」要留着
        val text = "场与粒子，场也"
        val result = ConceptMatcher(listOf("场", "粒子")).matchesIn(text, exclude = "场")
        assertEquals(listOf("粒子"), result.map { text.substring(it.start, it.end) })
    }
}
