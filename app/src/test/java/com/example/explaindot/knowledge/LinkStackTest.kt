package com.example.explaindot.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkStackTest {

    @Test
    fun `空栈没有当前项`() {
        val stack = LinkStack<String>()
        assertTrue(stack.isEmpty)
        assertEquals(0, stack.size)
        assertNull(stack.current)
        assertNull(stack.parent)
    }

    @Test
    fun `压栈后当前项是栈顶`() {
        val stack = LinkStack<String>()
        stack.push("A")
        assertEquals("A", stack.current)
        stack.push("B")
        assertEquals("B", stack.current)
        assertEquals(2, stack.size)
    }

    @Test
    fun `允许同一个概念重复出现在栈上`() {
        // 需求里明确要求的一条：A→B→C→D 时从 D 跳到 A，新栈必须是 A,B,C,D,A
        val stack = LinkStack<String>()
        listOf("A", "B", "C", "D", "A").forEach { stack.push(it) }
        assertEquals(listOf("A", "B", "C", "D", "A"), stack.trail)
        assertEquals("A", stack.current)
        assertEquals(5, stack.size)
    }

    @Test
    fun `重复概念逐层返回而不是一步弹到底`() {
        // 去重实现最容易在这里出问题：栈里有两个 A，返回时只该弹掉最上面那个
        val stack = LinkStack<String>()
        listOf("A", "B", "A").forEach { stack.push(it) }
        assertTrue(stack.pop())
        assertEquals("B", stack.current)
        assertTrue(stack.pop())
        assertEquals("A", stack.current)
        assertTrue(stack.pop())
        assertTrue(stack.isEmpty)
    }

    @Test
    fun `parent 是上一层而不是栈底`() {
        val stack = LinkStack<String>()
        listOf("A", "B", "C").forEach { stack.push(it) }
        assertEquals("B", stack.parent)
    }

    @Test
    fun `只有一层时没有 parent`() {
        // 用户从概念列表直接点进来的情形：没有"上一个解释"可以当上下文
        val stack = LinkStack<String>()
        stack.push("A")
        assertNull(stack.parent)
    }

    @Test
    fun `栈底再返回报告失败但不改变状态`() {
        val stack = LinkStack<String>()
        stack.push("A")
        assertTrue(stack.pop())
        // 已经在栈底，再返回应当失败，而且栈要保持原样
        assertFalse(stack.pop())
        assertTrue(stack.isEmpty)
    }

    @Test
    fun `空栈上返回不抛异常`() {
        assertFalse(LinkStack<String>().pop())
    }

    @Test
    fun `clear 一次清空不管多深`() {
        val stack = LinkStack<String>()
        listOf("A", "B", "C", "D").forEach { stack.push(it) }
        stack.clear()
        assertTrue(stack.isEmpty)
        assertNull(stack.current)
    }

    @Test
    fun `trail 是快照 改它不影响栈`() {
        val stack = LinkStack<String>()
        stack.push("A")
        val snapshot = stack.trail
        stack.push("B")
        // 快照必须是只读的历史记录，不能跟着栈一起长
        assertEquals(listOf("A"), snapshot)
        assertEquals(listOf("A", "B"), stack.trail)
    }

    @Test
    fun `清空之后还能重新压栈`() {
        val stack = LinkStack<String>()
        stack.push("A")
        stack.clear()
        stack.push("B")
        assertEquals(listOf("B"), stack.trail)
    }
}
