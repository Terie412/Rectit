package com.example.explaindot.knowledge

import org.junit.Assert.assertEquals
import org.junit.Test

class TermNormalizerTest {

    @Test
    fun `剥掉首尾的成对括号和引号`() {
        assertEquals("熵", TermNormalizer.normalize("「熵」"))
        assertEquals("熵", TermNormalizer.normalize("“熵”"))
        assertEquals("场", TermNormalizer.normalize("(场)"))
        assertEquals("场", TermNormalizer.normalize("（场）"))
        assertEquals("GDP", TermNormalizer.normalize("\"GDP\""))
    }

    @Test
    fun `套了两层也剥得干净`() {
        // 模型抄出「“熵”」这种情况实测会有：先抄了引号，又补了方括号
        assertEquals("熵", TermNormalizer.normalize("「“熵”」"))
    }

    @Test
    fun `剥掉首尾的句读`() {
        assertEquals("熵", TermNormalizer.normalize("熵，"))
        assertEquals("熵", TermNormalizer.normalize("熵。"))
        assertEquals("熵", TermNormalizer.normalize("，熵"))
        assertEquals("熵增", TermNormalizer.normalize("熵增、"))
    }

    @Test
    fun `只带半边括号也剥得掉`() {
        assertEquals("见下", TermNormalizer.normalize("（见下"))
        assertEquals("见下", TermNormalizer.normalize("见下）"))
    }

    @Test
    fun `不动中间的标点`() {
        // 并列写法是有意义的，改了就是篡改概念名
        assertEquals("图灵测试/中文房间", TermNormalizer.normalize("图灵测试/中文房间"))
        assertEquals("A·B", TermNormalizer.normalize("A·B"))
    }

    @Test
    fun `不做大小写折叠`() {
        // 有意为之：折叠会逼着匹配层去做不区分大小写的搜索，
        // 又要处理「显示的大小写和库里不一致」，不划算
        assertEquals("GDP", TermNormalizer.normalize("GDP"))
        assertEquals("gdp", TermNormalizer.normalize("gdp"))
    }

    @Test
    fun `内部连续空白折成一个空格`() {
        assertEquals("Alpha Beta", TermNormalizer.normalize("Alpha    Beta"))
        assertEquals("Alpha Beta", TermNormalizer.normalize("Alpha\n\tBeta"))
    }

    @Test
    fun `空串和纯标点不会崩`() {
        assertEquals("", TermNormalizer.normalize(""))
        assertEquals("", TermNormalizer.normalize("   "))
        assertEquals("", TermNormalizer.normalize("「」"))
    }
}
