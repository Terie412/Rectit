package com.example.explaindot.knowledge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机上验 [KnowledgeStore.replaceAll] —— "强制下载"的落库那一步。
 *
 * ## 为什么它必须跑在真机上
 *
 * JVM 单测里的 `android.database.sqlite` 是一调用就抛的桩，所以这个方法的
 * 行为在真机上跑之前**一行都没被执行过**。而它恰好是整条同步链路上最不能错
 * 的一步：它先删光本地，再写远端的 —— 删完之后的任何意外，用户都看不见，
 * 只会看到"我的知识库变少了"。
 *
 * ## 为什么不碰真实数据
 *
 * 真机上有用户攒了几个月的真库，而本类第一个用例就会 `replaceAll`（整表删光
 * 再写）。所以库文件必须换一个地方。**试过两条路，第一条在真机上不通**：
 *
 * 1. ~~用 `InstrumentationRegistry.getInstrumentation().context`（测试包
 *    `com.example.explaindot.test` 的 Context），指望它自带一个独立目录。~~
 *    不通。仪器测试跑在**目标应用的进程**里（uid 是 `com.example.explaindot`），
 *    而测试包的目录属于另一个 uid —— `getDatabasePath()` 给得出路径，
 *    `mkdirs()` 却建不出来，八个用例一起挂在 `SQLITE_CANTOPEN` 上。
 *    顺带一个坑：那个 Context 的 `getApplicationContext()` 是 null，
 *    而 [KnowledgeStore] 构造时正好取它。
 *
 * 2. **用应用自己的 Context（uid 对得上、写得进去），但把数据库路径重定向到
 *    它私有的缓存目录。** 现在这段抽到了 [IsolatedContext] 里，几个测试类共用。
 *    生产代码一行都不用改 —— 这是 `ContextWrapper` 存在的意义。
 *
 * 真库的安全最终由两件事保证：`IsolatedContext.create` 里那条断言，
 * 以及跑完之后用 `tools/compare_real_kb.py` 比对真库的内容指纹。
 */
@RunWith(AndroidJUnit4::class)
class ReplaceAllInstrumentedTest {

    private lateinit var store: KnowledgeStore

    private fun entry(term: String, tag: String, body: String, c: Long = 0L, u: Long = 0L) =
        KnowledgeStore.Entry(term, tag, body, c, u)

    @Before
    fun setUp() {
        // 隔离环境见 [IsolatedContext]。tag 和别的测试类不同，各用各的目录
        store = KnowledgeStore(IsolatedContext.create("kb-replace-all"))
        // 每个用例从空库开始。replaceAll(emptyList()) 就是"清空"
        store.replaceAll(emptyList())
    }

    @Test
    fun 空库写入后能原样读回来() {
        val input = listOf(
            entry("场", "物理学的场", "一种物质属性。"),
            entry("函数", "数学概念", "输入到输出的映射。"),
            entry("特征", "机器学习/深度学习中", "从数据里挑出的属性。")
        )
        store.replaceAll(input)

        val got = store.allEntries()
        assertEquals("条数", 3, got.size)
        // 只比 term/tag/body：时间戳传 0 会被换成"当下时间"，那是另一条用例在验的行为
        assertEquals(
            "内容与顺序（按 term, tag 排）",
            input.sortedBy { it.term }.map { Triple(it.term, it.tag, it.body) },
            got.sortedBy { it.term }.map { Triple(it.term, it.tag, it.body) }
        )
    }

    @Test
    fun 是整表替换而不是合并() {
        store.replaceAll(listOf(
            entry("旧一", "标签", "要被删掉的"),
            entry("旧二", "标签", "也要被删掉的")
        ))
        assertEquals(2, store.allEntries().size)

        // 换一批完全不相干的
        store.replaceAll(listOf(entry("新一", "标签", "新的")))

        val got = store.allEntries()
        assertEquals("旧的必须一条都不剩", 1, got.size)
        assertEquals("新一", got[0].term)
    }

    @Test
    fun 清空之后库真的是空的() {
        store.replaceAll(listOf(entry("甲", "标签", "内容")))
        store.replaceAll(emptyList())
        assertEquals(0, store.allEntries().size)
    }

    @Test
    fun 时间戳为零的按当下时间落库_非零的原样保留() {
        val before = System.currentTimeMillis()
        store.replaceAll(listOf(
            entry("没有时间", "标签", "远端文件里没写时间戳", 0L, 0L),
            entry("有时间", "标签", "远端文件里写了", 1758000006000L, 1758000009000L)
        ))
        val after = System.currentTimeMillis()

        val byTerm = store.allEntries().associateBy { it.term }
        val zero = byTerm.getValue("没有时间")
        assertTrue("0 应该被换成当下时间", zero.createdAt in before..after)
        assertTrue("两个时间戳都该被补上", zero.updatedAt in before..after)

        val kept = byTerm.getValue("有时间")
        assertEquals("非零的不能被动", 1758000006000L, kept.createdAt)
        assertEquals("非零的不能被动", 1758000009000L, kept.updatedAt)
    }

    @Test
    fun 真实数据规模的一次替换() {
        // 用和真机同一个数量级，确认批量插入不会在真实 SQLite 上出问题
        val big = (1..500).map {
            entry("概念$it", "标签", "第 $it 条的正文，带一点中文和换行\u000A第二行。")
        }
        store.replaceAll(big)
        assertEquals(500, store.allEntries().size)

        // 再替换成更少的，确认旧的全部清掉（前面那条"整表替换"在规模上的版本）
        store.replaceAll(big.take(10))
        assertEquals(10, store.allEntries().size)
    }

    /**
     * **这个是本次专门要问真机的一个问题。**
     *
     * 表上有 `UNIQUE(term, tag)`。远端文件里若出现两条完全相同的 (term, tag)
     * —— 用户手动编辑过那份 JSON、或者别的工具生成的 —— `db.insert` 会失败，
     * 而 **SQLite 的 insert 失败是返回 -1，不抛异常**。
     *
     * [KnowledgeStore.replaceAll] 没有检查那个返回值。所以这里要问的是：
     * 那种情况下，到底是"抛出来让人知道"，还是"静默少一条"。
     *
     * 这个用例断言的是**当前的真实行为**，不是"应该的行为"。如果哪天
     * 在 decode 那侧加了重复检查，这个用例会失败 —— 那时把它改掉就对了。
     */
    @Test
    fun 远端有重复的term和tag时_会静默少一条而不是报错() {
        store.replaceAll(listOf(
            entry("重复的", "同一标签", "先插入的这条"),
            entry("重复的", "同一标签", "后插入的这条"),
            entry("另一个", "标签", "无关的")
        ))

        val got = store.allEntries()
        assertEquals("三条里有一条被 UNIQUE 挡掉了", 2, got.size)

        // 关键不在少了，而在于：整个过程没有抛异常，调用方完全不知道少了一条。
        // 要证明"没抛"，跑完这个方法本身就是证据 —— 抛了就到不了下一行
        val dup = got.first { it.term == "重复的" }
        assertEquals("留下的是先插入的那条", "先插入的这条", dup.body)
    }

    /**
     * 同一个 (term, tag) 可以**换**内容（这是"重新解释"的路径），
     * 但那是 UPDATE，不是靠 replaceAll 里连续两次 insert。
     *
     * 这里验的是另一件事：replaceAll 之后再用同一个 (term, tag) 覆盖，
     * 库里不该攒出两条 —— 「同一个标签只能有一份解释」这条规则在替换之后
     * 依然成立。
     */
    @Test
    fun 同一term和tag再替换一次仍然只有一条() {
        store.replaceAll(listOf(entry("场", "物理学的场", "第一版")))
        store.replaceAll(listOf(entry("场", "物理学的场", "第二版")))

        val got = store.allEntries()
        assertEquals(1, got.size)
        assertEquals("第二版", got[0].body)
    }

    /** 统计走的 SQL 也要在真机上跑一次：设置页那张卡靠它显示数字 */
    @Test
    fun 统计与实际写入一致() {
        store.replaceAll(listOf(
            entry("概念一", "标签甲", "x"),
            entry("概念一", "标签乙", "y"),   // 同一个概念、两个标签
            entry("概念二", "标签甲", "z")
        ))
        val s = store.stats()
        assertEquals("概念数按去重的 term 算", 2, s.concepts)
        assertEquals("解释数按行算", 3, s.tags)
        assertTrue("字节数应该是正的", s.bytes > 0)
    }
}
