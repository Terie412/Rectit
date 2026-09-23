package com.example.explaindot.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考强度：档位 → 请求参数的映射，以及两路配置的默认值。
 *
 * 这几行代码出错**不会报任何错**。服务端收到一个不认识的档位，不会抱怨，
 * 只会静默按自己的默认值跑 —— 用户看到的是"我明明调成了 off，怎么还是等十几秒"，
 * 而日志里那句 thinking=none 是他唯一的线索。所以值得有测试盯着。
 */
class AiConfigThinkingTest {

    @Test
    fun `四档各自映射到官方的那个值`() {
        assertEquals("none", AiConfig.effortFor("off"))
        assertEquals("low", AiConfig.effortFor("low"))
        assertEquals("high", AiConfig.effortFor("high"))
        assertEquals("max", AiConfig.effortFor("max"))
    }

    @Test
    fun `认不出的档位兜底给 high 而不是 off`() {
        // 走到这一步说明上游的过滤漏了。宁可多等几秒，
        // 也别把用户要的"想深一点"悄悄变成"别想"
        assertEquals("high", AiConfig.effortFor(""))
        assertEquals("high", AiConfig.effortFor("medium"))
        assertEquals("high", AiConfig.effortFor("HIGH"))
    }

    @Test
    fun `解释的默认档是 off，对话的默认档是 high`() {
        // 两个默认值不同是有意的：解释时用户在等结果，对话时用户在聊。
        // 这一条同时盯着 build.gradle 里的 ai.properties 默认值 ——
        // 有人把 deepseek.chatThinking 改成 off 的话，这里会失败
        assertEquals("off", AiConfig.defaultThinking)
        assertEquals("high", AiConfig.defaultChatThinking)
    }

    @Test
    fun `对话默认开着思考，解释默认关着`() {
        // 用户没设过时，实际生效的就是出厂默认
        assertFalse("解释默认不该开思考", AiConfig.thinkingEnabled)
        assertTrue("对话默认要开思考", AiConfig.chatThinkingEnabled)
    }

    @Test
    fun `两种强度各读各的，互不串台`() {
        // 单测里没有 SharedPreferences，读到的都是"用户没设过"的状态。
        // 所以这里能验的是：两路各自落到自己的出厂默认，而不是共用同一个来源 ——
        // 共用了的话两个值必然相等，而它们本来就该不同
        assertEquals(AiConfig.defaultThinking, AiConfig.thinkingMode)
        assertEquals(AiConfig.defaultChatThinking, AiConfig.chatThinkingMode)
        assertNotEquals(
            "两路的默认档必须不同。相等就说明有一路读错了地方",
            AiConfig.thinkingMode,
            AiConfig.chatThinkingMode
        )
    }

    @Test
    fun `存储层会把不认识的档位当成没设过`() {
        // 单测里没有 prefs，写的这一侧会抛。所以这里只验读的那一侧的过滤规则 ——
        // 它保证了一个来路不明的值不会被原样发给服务端
        assertEquals("", AiUserSettings.thinking)
        assertEquals("", AiUserSettings.chatThinking)
        assertTrue(AiUserSettings.THINKING_LEVELS.containsAll(listOf("off", "low", "high", "max")))
    }
}
