package com.example.explaindot.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话历史的拼接与压缩计划。
 *
 * 这里全是纯函数，所以能在 JVM 上直接跑。值得单测的理由很具体：
 * **拼错了服务端只会回一个看不懂的 400**，而压缩的边界算错会静默丢掉
 * 用户问过的内容 —— 两种都不会在本地报错。
 */
class ChatHistoryTest {

    private fun user(text: String, hidden: Boolean = false) =
        ChatMessage(ChatMessage.Role.User, text, hidden)

    private fun bot(text: String) = ChatMessage(ChatMessage.Role.Assistant, text)

    /** 一场攒到 [turns] 轮问答的对话：开场（隐藏的 user + 回答）+ 若干轮追问 */
    private fun conversation(turns: Int): List<ChatMessage> = buildList {
        add(user("这张图讲的是什么？", hidden = true))   // 开场指令
        add(bot("这是一段关于 x 的内容。"))              // 开场回答
        repeat(turns) { i ->
            add(user("问题 $i"))
            add(bot("回答 $i"))
        }
    }

    private fun opening(): List<ChatMessage> = listOf(user("开场", hidden = true), bot("首答"))

    // ------------------------------------------------------------------ 拼接

    @Test
    fun `没压过时整段原样发，一条不改`() {
        val history = conversation(3)

        val outgoing = ChatHistory.build(history, covered = 0, summary = null)

        // 同一条不差，而且应当是同一个列表对象 —— 这条路径是绝大多数情况，
        // 不该有任何重建开销，也不该有重排的可能
        assertSame(history, outgoing)
    }

    @Test
    fun `压过之后：首轮 + 摘要 + 最近几轮`() {
        val history = conversation(10)          // 2 + 20 = 22 条
        val covered = 12

        val outgoing = ChatHistory.build(history, covered, summary = "用户问了三个问题。")

        // 首轮 2 条 + 摘要 1 条 + 剩余 10 条
        assertEquals(13, outgoing.size)
        assertSame("首轮那条带图的消息必须原样在", history[0], outgoing[0])
        assertSame(history[1], outgoing[1])

        val digest = outgoing[2]
        assertTrue("摘要要标明是背景，不是让他回应", digest.text.contains("摘要"))
        assertTrue("摘要内容要带上", digest.text.contains("用户问了三个问题"))
        assertTrue("摘要不该显示在界面上", !digest.visible)

        // 后面接的是 history[covered..]
        assertEquals(history.drop(covered), outgoing.drop(3))
    }

    @Test
    fun `摘要排在中间而不是最前面，首轮前缀才不会变`() {
        val history = conversation(10)

        val outgoing = ChatHistory.build(history, covered = 12, summary = "摘要")

        // 前两条在压缩前后必须是同样的内容 —— 前缀稳定，缓存才命中。
        // 这是这个设计里最容易写错的一处：把摘要放最前面看起来更自然，
        // 但那样从第 0 个 token 起就和上一轮不同，整个前缀全部重算
        assertEquals(history.take(2), outgoing.take(2))
        assertTrue("摘要不能是第三条之前的位置", outgoing[2].text.contains("摘要"))
    }

    @Test
    fun `covered 没超过首轮时不该插摘要`() {
        val history = conversation(3)

        // covered 停在首轮之后、还没有真正压过内容
        val outgoing = ChatHistory.build(history, covered = ChatHistory.OPENING_MESSAGES, summary = "摘要")

        assertSame("这种状态下应该原样发", history, outgoing)
    }

    @Test
    fun `摘要为空时退回整段原样发`() {
        val history = conversation(10)

        val outgoing = ChatHistory.build(history, covered = 12, summary = null)

        // 摘要没了却还按 covered 砍掉一段，用户问过的内容就凭空消失了。
        // 宁可多发一点，也不能丢
        assertSame(history, outgoing)
    }

    @Test
    fun `空历史不炸`() {
        assertEquals(emptyList<ChatMessage>(), ChatHistory.build(emptyList()))
    }

    // ------------------------------------------------------------------ 压缩计划

    @Test
    fun `轮数不够时不压`() {
        // 2 + 2*4 = 10 条，减去保留 8 条只剩 0 条可压
        val history = conversation(4)
        assertEquals(10, history.size)

        assertNull(ChatHistory.planCompression(history, covered = 0))
    }

    @Test
    fun `攒够一批才压，而且不碰首轮和最近几轮`() {
        // 2 + 2*7 = 16 条 → 可压区间 [2, 8)，6 条，正好达到 MIN_BATCH
        val history = conversation(7)
        assertEquals(16, history.size)

        val plan = ChatHistory.planCompression(history, covered = 0)

        assertEquals("起点必须是首轮之后", ChatHistory.OPENING_MESSAGES, plan!!.from)
        assertEquals("终点要把最近几条让出来", history.size - ChatHistory.KEEP_RECENT, plan.to)
        assertEquals("这一批正好是 MIN_BATCH", ChatHistory.MIN_BATCH, plan.to - plan.from)
    }

    @Test
    fun `首轮永远不进压缩区间`() {
        // 就算 covered 传了 0，起点也要提到首轮之后
        val history = conversation(7)

        val plan = ChatHistory.planCompression(history, covered = 0)!!

        assertTrue("压掉带图那条，模型就再也看不见这张图了", plan.from >= ChatHistory.OPENING_MESSAGES)
    }

    @Test
    fun `压过一次之后再攒，从上次的终点接着压`() {
        // 26 条 → 可压 [2, 18)，16 条
        val history = conversation(12)
        val first = ChatHistory.planCompression(history, covered = 0)!!
        assertEquals(18, first.to)

        // **压缩不删消息，只是把 covered 往前挪。** 所以压完之后不能马上再压 ——
        // 要等用户接着聊、历史继续长，直到"没被覆盖的部分"又够一批。
        // 这一步是压完立刻再调用：此时 covered 已经追上了 to，没什么可压
        assertNull(
            "压完立刻再调不该重复劳动",
            ChatHistory.planCompression(history, covered = first.to)
        )

        // 用户又问了 4 轮（8 条），历史长到 34 条
        val grown = history + listOf(
            user("问题 A"), bot("回答 A"),
            user("问题 B"), bot("回答 B"),
            user("问题 C"), bot("回答 C"),
            user("问题 D"), bot("回答 D")
        )
        assertEquals(34, grown.size)

        val second = ChatHistory.planCompression(grown, covered = first.to)!!

        assertEquals("第二个区间从第一个的终点接着开始，不能重叠", first.to, second.from)
        assertEquals("终点仍然是让出最近那几条", grown.size - ChatHistory.KEEP_RECENT, second.to)
        assertTrue("新的一批也要够长才值得压", second.to - second.from >= ChatHistory.MIN_BATCH)
    }

    @Test
    fun `压到没什么可压时返回 null，不会空转`() {
        val history = conversation(12)
        val plan = ChatHistory.planCompression(history, covered = 0)!!

        // 假装已经压到很靠后，剩下不足一批
        assertNull(ChatHistory.planCompression(history, covered = history.size - ChatHistory.KEEP_RECENT))
    }

    // ------------------------------------------------------------------ 摘要的输入

    @Test
    fun `摘要输入要标出谁说的，并带上旧摘要`() {
        val history = conversation(7)
        val plan = ChatHistory.planCompression(history, covered = 0)!!

        val digest = ChatHistory.digest(history, plan, previousSummary = null)

        assertTrue("用户的话要标出来", digest.contains("用户："))
        assertTrue("模型的回答要标出来", digest.contains("你："))
        // 只摘这一段，不该把保留的那几条也带进去
        assertTrue(digest.contains("问题 1"))
    }

    @Test
    fun `第二次压缩必须带上第一次的摘要`() {
        val history = conversation(12)
        val plan = ChatHistory.planCompression(history, covered = 10)!!

        val digest = ChatHistory.digest(history, plan, previousSummary = "早前聊过公式的事")

        // 不带旧摘要的话，第一次摘掉的内容就永久丢了 ——
        // 用户问过的"这个公式为什么成立"会从模型记忆里消失
        assertTrue("旧摘要要一并带进去", digest.contains("早前聊过公式的事"))
    }

    @Test
    fun `摘要只覆盖指定区间，不碰保留的那几条`() {
        val history = conversation(12)
        val plan = ChatHistory.planCompression(history, covered = 0)!!

        val digest = ChatHistory.digest(history, plan, previousSummary = null)

        // 区间末尾之后的消息不该出现
        val kept = history[plan.to].text
        assertTrue("保留区间里的内容不该被摘进去", !digest.contains(kept))
    }
}
