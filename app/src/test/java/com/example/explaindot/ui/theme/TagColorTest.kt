package com.example.explaindot.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签随机色的测试。
 *
 * 这里要钉住的不是「颜色好不好看」（那个只能看图），而是两条**承诺**：
 *
 * 1. 确定的输入产生确定的输出 —— 换了实现、换了版本，同一个标签必须还是同一个色。
 *    所以下面的档位表是**写死的期望值**，不是照着实现算出来的。
 * 2. 对比度不是碰运气 —— 不管标签里写什么字，底色和字色都必须过 4.5:1。
 *    这一条覆盖全 12 个档位 × 浅深两套主题。
 */
class TagColorTest {

    /**
     * 档位表。
     *
     * 期望值来自算法本身（FNV-1a + 雪崩 + mod 12），在 Python 侧独立算过一遍
     * （tools/tag_color.py）。两边一致才说明「跨语言、跨版本同一个值」不是空话。
     */
    @Test
    fun `档位是写死的 -- 换了实现必须还是同一个颜色`() {
        val expected = mapOf(
            "产品名：ChatGPT" to 10,
            "人工智能公司 OpenAI" to 0,
            "人工智能的大语言模型" to 11,
            "化学元素" to 2,
            "数学概念" to 4,
            "经济指标" to 1,
            "游戏类型的肉鸽" to 5,
            "代数式" to 11,
            "" to 4,
        )
        expected.forEach { (tag, slot) ->
            assertEquals("「$tag」的档位变了", slot, TagColor.slotOf(tag))
        }
    }

    @Test
    fun `同一个标签反复算，底和字都完全一样`() {
        val tag = "机器学习中的感知机"
        listOf(false, true).forEach { dark ->
            assertEquals(TagColor.background(tag, dark), TagColor.background(tag, dark))
            assertEquals(TagColor.foreground(tag, dark), TagColor.foreground(tag, dark))
        }
    }

    @Test
    fun `12 个档位在两套主题下对比度都过 4点5比1`() {
        // 公开接口是按文字取色，所以先造一批样本、每档抓一个
        val bySlot = (0 until 8000).map { "标签样本$it" }.groupBy { TagColor.slotOf(it) }
        assertEquals("12 档应该都能覆盖到", 12, bySlot.size)

        bySlot.toSortedMap().forEach { (slot, tags) ->
            val tag = tags.first()
            listOf(false to "浅色", true to "深色").forEach { (dark, name) ->
                val ratio = contrast(TagColor.background(tag, dark), TagColor.foreground(tag, dark))
                assertTrue(
                    "$name 第 $slot 档（$tag）对比度只有 $ratio:1，低于 4.5",
                    ratio >= 4.5
                )
            }
        }
    }

    @Test
    fun `深色的底一定比浅色的底暗`() {
        // 防呆：参数写反了（比如把两套的亮度常量对调）的话，这条会立刻响
        (0 until 8000).map { "标签样本$it" }.groupBy { TagColor.slotOf(it) }.values.forEach { tags ->
            val tag = tags.first()
            assertTrue(
                "深色徽章居然比浅色的还亮：$tag",
                TagColor.background(tag, true).luminance() < TagColor.background(tag, false).luminance()
            )
        }
    }

    /** WCAG 对比度。和核算脚本同一个口径，用 Double 避免浮点误差累积 */
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }
}
