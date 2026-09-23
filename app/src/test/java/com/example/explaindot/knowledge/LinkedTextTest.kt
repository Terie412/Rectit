package com.example.explaindot.knowledge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 这一组测的是最容易出「错位」bug 的地方：加粗记号被剥掉之后，
 * 显示文本变短了，链接的下标必须跟着落在新坐标上。
 */
class LinkedTextTest {

    private fun styled(raw: String, vararg terms: String) =
        buildStyledText(raw, ConceptMatcher(terms.toList()))

    // ---------------------------------------------------------------- 记号剥离

    @Test
    fun `剥掉加粗记号并记下加粗范围`() {
        val result = styled("**熵**是热力学量")
        assertEquals("熵是热力学量", result.text)
        assertEquals(listOf(BoldSpan(0, 1)), result.bold)
    }

    @Test
    fun `没有记号时原样返回`() {
        val result = styled("普通的一句话")
        assertEquals("普通的一句话", result.text)
        assertEquals(emptyList<BoldSpan>(), result.bold)
    }

    @Test
    fun `多处加粗各自记下范围`() {
        val result = styled("**场**和**熵**")
        assertEquals("场和熵", result.text)
        assertEquals(listOf(BoldSpan(0, 1), BoldSpan(2, 3)), result.bold)
    }

    @Test
    fun `流式输出中半个记号原样留下不崩`() {
        // 写到一半停下时，"**" 可能只有开头没有收尾。
        // 正则配不上这类片段，它就留在显示文本里 —— 下一帧补全即可
        val result = styled("**熵")
        assertEquals("**熵", result.text)
        assertEquals(emptyList<BoldSpan>(), result.bold)
    }

    // ---------------------------------------------------------------- 坐标对齐

    @Test
    fun `链接下标落在剥掉记号之后的坐标上`() {
        // 「熵」被加粗包住，剥完在显示文本的第 0 位。
        // 如果拿原始文本去匹配，位置会算到第 2 位（** 之后），链到「是」上
        val result = styled("**熵**是热力学量", "熵")
        assertEquals("熵是热力学量", result.text)
        assertEquals(1, result.links.size)
        val link = result.links[0]
        assertEquals("熵", result.text.substring(link.start, link.end))
        assertEquals(0, link.start)
    }

    @Test
    fun `加粗和链接同时存在时互不干扰`() {
        val result = styled("**场**与**熵**", "场", "熵")
        assertEquals("场与熵", result.text)
        assertEquals(listOf(BoldSpan(0, 1), BoldSpan(2, 3)), result.bold)
        // 场在句首 → 连；熵左边是「与」→ 按单字左边界规则不连
        assertEquals(listOf("场"), result.links.map { result.text.substring(it.start, it.end) })
    }

    @Test
    fun `链接词跨过多个加粗段时不会错位`() {
        // 概念名本身没被加粗，但它前面有加粗段，下标要整体前移
        val result = styled("**甲乙**市场", "市场")
        assertEquals("甲乙市场", result.text)
        assertEquals(1, result.links.size)
        assertEquals(2, result.links[0].start)
        assertEquals("市场", result.text.substring(result.links[0].start, result.links[0].end))
    }

    // ---------------------------------------------------------------- 退化情形

    @Test
    fun `没有匹配器时不产生链接`() {
        val result = buildStyledText("**熵**是热力学量", null)
        assertEquals("熵是热力学量", result.text)
        assertEquals(emptyList<LinkSpan>(), result.links)
    }

    @Test
    fun `空文本不崩`() {
        val result = styled("", "熵")
        assertEquals("", result.text)
        assertEquals(emptyList<LinkSpan>(), result.links)
    }

    @Test
    fun `只有记号没有内容时不崩`() {
        val result = styled("****", "熵")
        assertEquals("****", result.text)
    }

    // ---------------------------------------------------------------- 两套词表

    /**
     * 库里已有的词（蓝）和模型建议的词（另一色）走的是两套词表，
     * 这里测的是它们怎么合到一起。
     */
    private fun merged(raw: String, known: List<String>, suggested: List<String>) =
        buildStyledText(raw, ConceptMatcher(known), null, ConceptMatcher(suggested))

    @Test
    fun `建议的词标成未知 已知的标成已知`() {
        val result = merged("电场与磁场", listOf("电场"), listOf("磁场"))
        assertEquals("电场与磁场", result.text)
        assertEquals(listOf("电场", "磁场"), result.links.map { it.term })
        assertEquals(listOf(true, false), result.links.map { it.known })
    }

    @Test
    fun `重叠时保留已知的那一个`() {
        // 库里存着「场论」，模型在这段解释里建议了「场」——
        // 两条链接盖住同一个字。留已知的：点下去立刻出结果，不用花钱等模型
        val result = merged("场论讲的是…", listOf("场论"), listOf("场"))
        assertEquals(1, result.links.size)
        assertEquals("场论", result.links.single().term)
        assertEquals(true, result.links.single().known)
    }

    @Test
    fun `合并之后仍按位置排列`() {
        // 两套词表各扫一遍，结果是两段拼接；不排序的话 addLink 的调用顺序
        // 会变成「先全部已知、再全部建议」，位置是乱的
        val result = merged("电场与磁场", listOf("磁场"), listOf("电场"))
        assertEquals(listOf(0, 3), result.links.map { it.start })
        assertEquals(listOf(false, true), result.links.map { it.known })
    }

    @Test
    fun `不传建议时和以前完全一样`() {
        val result = styled("电场与磁场", "电场")
        assertEquals(listOf("电场"), result.links.map { it.term })
        assertEquals(listOf(true), result.links.map { it.known })
    }

    @Test
    fun `建议为空表时不产生额外链接`() {
        val result = merged("电场与磁场", listOf("电场"), emptyList())
        assertEquals(listOf("电场"), result.links.map { it.term })
    }
}
