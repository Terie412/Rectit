package com.example.explaindot.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话理解的 system 拼装。
 *
 * 这两段文本用户想写多长写多长，模型会照单全收 —— 所以拼错不会有任何报错，
 * 只会让回答悄悄变差（比如把"请讲浅一点"当成了读者背景的一部分塞进去）。
 * 值得盯住的是三件事：空的不留痕、顺序固定、基础段永远在最前面。
 */
class PromptsTest {

    /** 什么都不填时的那份，也就是两段拼装都要挂上去的那块底 */
    private val base = Prompts.chatSystem("", "")

    @Test
    fun `两段都空时原样返回基础提示词`() {
        assertEquals(base, Prompts.chatSystem("", ""))
        // 空白串要等同于空 —— 用户在框里敲了几个空格然后保存，不该被当成填过了
        assertEquals(base, Prompts.chatSystem("   ", "\n\n  \t "))
    }

    @Test
    fun `只填知识背景时只出现读者那一段`() {
        val result = Prompts.chatSystem("我是做后端的，没学过经济学。", "")

        assertTrue("要带上背景原文", result.contains("我是做后端的，没学过经济学。"))
        assertTrue("要有引导语，否则模型不知道这段是干什么用的", result.contains("关于这位读者"))
        assertFalse("没填的那段一个字都不该出现", result.contains("对回答方式的要求"))
    }

    @Test
    fun `只填技能时只出现要求那一段`() {
        val result = Prompts.chatSystem("", "先给结论，再讲原因。")

        assertTrue(result.contains("先给结论，再讲原因。"))
        assertTrue(result.contains("对回答方式的要求"))
        assertFalse(result.contains("关于这位读者"))
    }

    @Test
    fun `两段都填时，读者在前、要求在后`() {
        val background = "我在读研究生的统计课。"
        val skill = "多举例子，少用公式。"
        val result = Prompts.chatSystem(background, skill)

        val iBackground = result.indexOf(background)
        val iSkill = result.indexOf(skill)
        assertTrue("两段都要在", iBackground >= 0 && iSkill >= 0)
        assertTrue("先交代读者是谁，再说请怎么讲话", iBackground < iSkill)
    }

    /**
     * **这是这份测试里最要紧的一条。**
     *
     * DeepSeek 的缓存是「从头逐字节匹配」的前缀缓存，命中价约为未命中的五十分之一。
     * system 是请求里最靠前的内容，基础段只要仍然严格是前缀，那几十行就永远命中；
     * 反过来把用户文本插到前面，用户改一个字，后面全部重算。
     */
    @Test
    fun `基础提示词永远是最前面那一段`() {
        val result = Prompts.chatSystem("读者背景", "说话要求")

        assertTrue("基础段必须是前缀", result.startsWith(base))
        assertTrue("用户文本只能追加在后面", result.indexOf("读者背景") >= base.length)
    }

    @Test
    fun `用户文本里的换行与长内容原样保留`() {
        val background = "第一行\n第二行\n\n第四行"
        val result = Prompts.chatSystem(background, "")

        assertTrue("多行要原样带上，不能压成一行", result.contains(background))
    }

    @Test
    fun `技能被声明为优先于基础原则`() {
        // 用户完全可能要求"每次结尾总结一句"，而基础段里恰好写着"说完就停"。
        // 两个都在的情况下必须说清听谁的 —— 否则模型只能自己猜
        val result = Prompts.chatSystem("", "每次结尾用一句话总结。")

        assertTrue(result.contains("优先"))
    }
}
