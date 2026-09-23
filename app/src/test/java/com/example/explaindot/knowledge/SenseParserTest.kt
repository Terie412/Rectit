package com.example.explaindot.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SenseParserTest {

    @Test
    fun `标准格式切成多条`() {
        val md = """
            ## 物理学的场
            场是物质存在的一种形式。

            ## 作量词用
            用于计量事情的数量，比如「一场雨」。
        """.trimIndent()

        val senses = SenseParser.parse(md)
        assertEquals(2, senses.size)
        assertEquals("物理学的场", senses[0].tag)
        assertEquals("场是物质存在的一种形式。", senses[0].body)
        assertEquals("作量词用", senses[1].tag)
        assertEquals("用于计量事情的数量，比如「一场雨」。", senses[1].body)
    }

    @Test
    fun `正文里的列表和粗体原样保留`() {
        // 这些记号归渲染层处理，解析器不该动它们
        val md = """
            ## 统计概念
            它是这样定义的：

            - **第一点**：甲
            - 第二点：乙
        """.trimIndent()

        val body = SenseParser.parse(md).single().body
        assertTrue(body.contains("**第一点**"))
        assertTrue(body.contains("- 第二点：乙"))
    }

    @Test
    fun `正文里的多行段落不会被拆开`() {
        val md = """
            ## 物理学的场
            第一段。

            第二段。
        """.trimIndent()
        val body = SenseParser.parse(md).single().body
        assertEquals("第一段。\n\n第二段。", body)
    }

    @Test
    fun `一到六级标题都认`() {
        // 模型偶尔把 ## 写成 # 或 ###，不值得为此丢内容
        assertEquals("A", SenseParser.parse("# A\n正文").single().tag)
        assertEquals("A", SenseParser.parse("### A\n正文").single().tag)
        assertEquals("A", SenseParser.parse("###### A\n正文").single().tag)
    }

    @Test
    fun `标签里的粗体和结尾冒号被清掉`() {
        // 标签会被当作 targetTag 发回去定位，带记号会让模型对不上
        val md = """
            ## **物理学的场**：
            正文
        """.trimIndent()
        assertEquals("物理学的场", SenseParser.parse(md).single().tag)
    }

    @Test
    fun `模型没写标题时整篇按一条存且内容不丢`() {
        val md = "这个概念指的是某种东西。\n\n它通常出现在这里。"
        val senses = SenseParser.parse(md)
        assertEquals(1, senses.size)
        assertEquals(SenseParser.FALLBACK_TAG, senses.single().tag)
        assertEquals(md, senses.single().body)
    }

    @Test
    fun `标题下面是空的不会产生空条目`() {
        val md = """
            ## 有内容的
            正文

            ## 空标题

            ## 后面的
            正文二
        """.trimIndent()
        val senses = SenseParser.parse(md)
        assertEquals(listOf("有内容的", "后面的"), senses.map { it.tag })
    }

    @Test
    fun `开头的正文被丢弃但不影响后续分节`() {
        // 模型偶尔会先来一句"好的，我来解释"，然后才开始写标题。
        // 那句不算任何一个含义，丢掉；后面的分节必须完好
        val md = """
            好的，我来解释这个概念。

            ## 物理学的场
            场是物质存在的一种基本形态。

            ## 作量词用
            用于计量事情的数量。
        """.trimIndent()
        val senses = SenseParser.parse(md)
        assertEquals(listOf("物理学的场", "作量词用"), senses.map { it.tag })
    }

    @Test
    fun `空串不产生条目`() {
        assertTrue(SenseParser.parse("").isEmpty())
        assertTrue(SenseParser.parse("   \n\n  ").isEmpty())
    }

    @Test
    fun `超长标题被截断而不是整条丢掉`() {
        // 模型把一整段话当标题写时，截断比丢弃好 —— 内容还在正文里
        val longTag = "这是一个非常长的标签" + "啊".repeat(50)
        val senses = SenseParser.parse("## $longTag\n正文")
        assertEquals(1, senses.size)
        assertTrue(senses.single().tag.length <= 24)
    }

    @Test
    fun `流式写到一半的半个标题也不会崩`() {
        // 写到 "## 物理" 就断了：它仍然是个合法标题，正常切成一条
        val senses = SenseParser.parse("## 物理\n场是")
        assertEquals(1, senses.size)
        assertEquals("物理", senses.single().tag)
        assertEquals("场是", senses.single().body)
    }

    @Test
    fun `同一标签的判定忽略大小写和首尾空白`() {
        assertTrue(SenseParser.sameTag("物理学的场", "物理学的场 "))
        assertTrue(SenseParser.sameTag("Field", "field"))
        assertTrue(SenseParser.sameTag("场：", "场"))
        assertTrue(!SenseParser.sameTag("物理学的场", "作量词用"))
    }

    @Test
    fun `tagsOf 按顺序给出标签`() {
        val senses = listOf(Sense("A", "a"), Sense("B", "b"))
        assertEquals(listOf("A", "B"), SenseParser.tagsOf(senses))
    }

    // -------------------------------------------------- 模型照抄 prompt 模板

    @Test
    fun `模型照抄模板时把真标签从正文里捞回来`() {
        // 实测遇到的形态：prompt 里的「## 标签 / 正文……」被当成要照抄的内容，
        // 于是标题成了「标签」，真标签「物理学的场」被挤到正文第一行
        val md = """
            ## 标签
            物理学的场

            正文
            场是物质存在的一种基本形态。
        """.trimIndent()

        val senses = SenseParser.parse(md)
        assertEquals(1, senses.size)
        assertEquals("物理学的场", senses.single().tag)
        // 模型照抄的「正文」那行必须被丢掉，不能混进解释里
        assertEquals("场是物质存在的一种基本形态。", senses.single().body)
    }

    @Test
    fun `捞回来的正文保留原有的段落结构`() {
        val md = """
            ## 标签
            热力学量

            正文
            第一段。

            - 要点一
            - 要点二
        """.trimIndent()

        val body = SenseParser.parse(md).single().body
        assertTrue(body.startsWith("第一段。"))
        assertTrue(body.contains("- 要点一"))
        assertTrue(body.contains("- 要点二"))
        // 那段空行要留着，否则列表会和上一段粘在一起
        assertTrue(body.contains("\n\n"))
    }

    @Test
    fun `两个节都是占位符标题时只救第一条`() {
        // 这种输出结构上就只有一节内容，硬凑多条会把噪声当内容存进库
        val md = """
            ## 标签
            物理学的场

            ## 正文
            场是物质存在的一种基本形态。
        """.trimIndent()

        val senses = SenseParser.parse(md)
        assertEquals(1, senses.size)
        assertEquals("物理学的场", senses.single().tag)
    }

    @Test
    fun `只有一节是占位符标题时那一节被丢掉 其余保留`() {
        // 混合形态：模型前一半照抄了模板，后一半正常写
        val md = """
            ## 标签
            噪声

            ## 物理学的场
            场是物质存在的一种基本形态。
        """.trimIndent()

        val senses = SenseParser.parse(md)
        assertEquals(listOf("物理学的场"), senses.map { it.tag })
        assertTrue(senses.none { it.body == "噪声" })
    }

    @Test
    fun `正常标题不会被占位符规则误伤`() {
        // 「标签」是占位符，但「标签体系」不是 —— 别把正常标签一刀切掉
        val md = """
            ## 标签体系
            正文一

            ## 内容管理
            正文二
        """.trimIndent()

        val senses = SenseParser.parse(md)
        assertEquals(listOf("标签体系", "内容管理"), senses.map { it.tag })
    }

    @Test
    fun `捞不出可用标签时退化成通用 且内容不丢`() {
        // 正文里第一行就超长（不像标签），救不回来 —— 但也绝不能把内容扔掉
        val md = "## 标签\n" + "这".repeat(60) + "\n真正的解释内容。"
        val senses = SenseParser.parse(md)
        assertEquals(1, senses.size)
        assertEquals(SenseParser.FALLBACK_TAG, senses.single().tag)
        assertTrue(senses.single().body.contains("真正的解释内容"))
    }

    @Test
    fun `占位符标题带井号或多级井号都能识别`() {
        val md = """
            ### 正文
            物理学的场

            真正的解释。
        """.trimIndent()
        val senses = SenseParser.parse(md)
        assertEquals(1, senses.size)
        assertEquals("物理学的场", senses.single().tag)
    }

    // -------------------------------------------------- 末尾那行「相关概念」标记

    @Test
    fun `标记之后的内容不算解释正文`() {
        // 那行是给程序看的，混进正文就会被当成最后一条解释的尾巴存进库
        val md = """
            ## 物理学的场
            场是物质存在的一种基本形态。

            <!--相关概念：电场、磁场-->
        """.trimIndent()

        val senses = SenseParser.parse(md)
        assertEquals(1, senses.size)
        assertEquals("场是物质存在的一种基本形态。", senses.single().body)
    }

    @Test
    fun `标记里的词被提取出来`() {
        val md = "## A\n正文\n\n<!--相关概念：电场、磁场、 通量 -->"
        assertEquals(listOf("电场", "磁场", "通量"), SenseParser.suggestedTerms(md))
    }

    @Test
    fun `标记没收尾也能提取`() {
        // 流被截断时只剩开头。不能因为少个 --> 就把模型写的东西整段丢掉
        val md = "正文\n<!--相关概念：电场、磁场"
        assertEquals(listOf("电场", "磁场"), SenseParser.suggestedTerms(md))
    }

    @Test
    fun `冒号写成半角 或者干脆没写 都能提取`() {
        assertEquals(listOf("A", "B"), SenseParser.suggestedTerms("<!--相关概念: A, B-->"))
        assertEquals(listOf("A", "B"), SenseParser.suggestedTerms("<!--A、B-->"))
    }

    @Test
    fun `空标记给出空表 而不是一个空字符串项`() {
        // prompt 里明确让它在没有可列的词时写空标记，
        // 这条路径必须干净 —— 一个空字符串会变成一条匹配一切的链接
        assertTrue(SenseParser.suggestedTerms("正文\n<!--相关概念：-->").isEmpty())
        assertTrue(SenseParser.suggestedTerms("正文\n<!--相关概念：  -->").isEmpty())
    }

    @Test
    fun `词上的引号和书名号会被归一化掉`() {
        // 入库的键和词表用的都是归一化之后的形态，两边必须一致，
        // 否则这个词永远匹配不上已经存在的解释
        assertEquals(
            listOf("熵", "场论"),
            SenseParser.suggestedTerms("<!--相关概念：「熵」、《场论》-->")
        )
    }

    @Test
    fun `重复的词只留一个`() {
        assertEquals(
            listOf("熵", "场"),
            SenseParser.suggestedTerms("<!--相关概念：熵、场、熵-->")
        )
    }

    @Test
    fun `词数有上限 免得正文被链接淹掉`() {
        val many = (1..30).joinToString("、") { "词$it" }
        assertEquals(8, SenseParser.suggestedTerms("<!--相关概念：$many-->").size)
    }

    @Test
    fun `没有标记时提不出东西 正文也一字不动`() {
        val md = "## A\n正文"
        assertTrue(SenseParser.suggestedTerms(md).isEmpty())
        assertEquals(md, SenseParser.explanationOnly(md))
    }

    @Test
    fun `流式过程中见到半个标记就停笔`() {
        // 一边收一边显示时，标记可能只到了 "<!--" 或者 "<!--相关"。
        // explanationOnly 只看分隔符，所以这些中间态一律被掐掉，
        // 用户不会看见它闪一下再消失
        assertEquals("正文", SenseParser.explanationOnly("正文<!--"))
        assertEquals("正文", SenseParser.explanationOnly("正文<!--相关"))
        assertEquals("正文", SenseParser.explanationOnly("正文<!--相关概念：电场"))
    }
}
