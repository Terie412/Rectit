package com.example.explaindot.chat

/**
 * 请求消息列表怎么拼、什么时候该压缩。
 *
 * **全是纯函数**，没有状态、不碰网络、不碰 Android —— 因为这里是这一整块功能里
 * 最该被单测盖住的一段：拼错了服务端只会回一个看不懂的 400，而压缩的边界
 * 算错会静默丢掉用户问过的内容。把决策和"真的去发请求"分开之后，
 * 这些规则可以在 JVM 上直接跑。
 *
 * ## 为什么要压缩，以及为什么它必须低频
 *
 * 追问到十几轮之后，历史本身会超出模型窗口。更现实的问题是成本：
 * 每一轮都要把之前全部内容重发一遍。
 *
 * 但**压缩会弄坏上下文缓存**。DeepSeek 的缓存是从第 0 个 token 开始逐字节
 * 匹配前缀的，一旦删掉中间几条消息，从删除点往后的前缀全变，那部分就重新
 * 按未命中的价格算（差五十倍）。所以压缩不能每轮都做 —— 攒够一批才做一次，
 * 做完重新建前缀。这里的两个阈值就是为此设的：
 *
 *   [KEEP_RECENT] 每次压缩保留最近这么多条原始消息（约 4 轮问答）
 *   [MIN_BATCH]   一次至少要压这么多条才值得，否则摘出来的东西还没请求本身值钱
 *
 * 所以第一次压缩发生在 `首轮 2 条 + 保留 8 条 + 最少 6 条 = 16 条` 时，
 * 之后每多攒 6 条压一次。
 *
 * ## 首轮为什么永远不压
 *
 * 前两条（自动发起的开场指令 + 模型的回答）里那条 user 消息**带着图片**，
 * 而图片是这场对话的全部依据 —— 压掉它，模型就再也看不见这张图，
 * 后面所有追问都会变成盲猜。
 *
 * 它同时还是前缀的开头：只要它不动，`system + 首轮` 这一段在每次请求里都相同，
 * 缓存命中区就至少有那么大。
 */
internal object ChatHistory {

    /** 开场那两条：自动发起的指令 + 模型的首答。永不压缩，理由见类注释 */
    const val OPENING_MESSAGES = 2

    /** 每次压缩保留的最近消息条数。8 条约等于 4 轮问答 */
    const val KEEP_RECENT = 8

    /** 一次压缩至少覆盖的条数。太短就不压，省得为省一点白花一次请求 */
    const val MIN_BATCH = 6

    /**
     * 该不该压缩，压哪一段。
     *
     * 返回 null 表示不用压。[Covered] 里是一个**左闭右开**区间：
     * `history[from until to]` 是这次要交给模型摘要的部分。
     *
     * @param covered 已经被摘要覆盖到哪（0 表示还没压过）
     */
    fun planCompression(history: List<ChatMessage>, covered: Int): Covered? {
        // 起点不能比开场还靠前 —— 首轮那两条永远不参与压缩（见类注释）
        val from = maxOf(covered, OPENING_MESSAGES)
        val to = history.size - KEEP_RECENT

        if (to - from < MIN_BATCH) return null
        return Covered(from = from, to = to)
    }

    data class Covered(val from: Int, val to: Int)

    /**
     * 拼出这次请求要发的消息（**不含 system**，那个由调用方放最前面）。
     *
     * [covered] 表示 `history[..covered]` 已经被 [summary] 取代了，
     * 所以那一段不再发原文。于是列表的形状是：
     *
     * ```
     * 首轮 2 条（带图）
     * 摘要（一条隐藏消息）      ← covered > 2 时才有
     * history[covered..]        ← 最近这几轮原样
     * ```
     *
     * 摘要排在**中间**而不是最前面，是为了让「首轮带图那两条」这个前缀
     * 在压缩前后完全一致 —— 排在前面的话，摘要一变，后面全部重算。
     */
    fun build(
        history: List<ChatMessage>,
        covered: Int = 0,
        summary: String? = null
    ): List<ChatMessage> {
        if (history.isEmpty()) return emptyList()

        // 还没压过：整段原样发。这是最常见的一条路径，也是缓存最完整的一条
        if (covered <= OPENING_MESSAGES || summary.isNullOrBlank()) {
            return history
        }

        val out = ArrayList<ChatMessage>(history.size)
        out += history.take(OPENING_MESSAGES)
        out += ChatMessage(
            role = ChatMessage.Role.User,
            // hidden：它不是你打的话，不该出现在屏幕上
            hidden = true,
            text = "[以下是这场对话早前内容的摘要，供你参考，不需要回应它]\n$summary"
        )
        out += history.drop(covered)
        return out
    }

    /**
     * 把要压缩的那批消息摊成一段文本，交给模型摘。
     *
     * **必须带上 [previousSummary]。** 第二次压缩发生在第一次之后，
     * 只摘新那一段的话，第一次摘掉的内容就永久丢了 —— 用户先前问过的
     * 「这个公式为什么成立」会从模型的记忆里消失，而它还以为自己记得全部。
     *
     * 角色也标出来（「用户：」「你：」），否则摘出来的东西分不清谁说的，
     * 压缩之后模型会把用户的问题当成自己的结论。
     */
    fun digest(
        history: List<ChatMessage>,
        range: Covered,
        previousSummary: String?
    ): String {
        val sb = StringBuilder()

        if (!previousSummary.isNullOrBlank()) {
            sb.append("=== 已经存在的摘要（这部分内容也要一并保住） ===\n")
            sb.append(previousSummary.trim())
            sb.append("\n\n")
        }

        sb.append("=== 需要你摘要的对话 ===")
        history.subList(range.from, range.to).forEach { m ->
            sb.append('\n')
            sb.append(if (m.role == ChatMessage.Role.User) "用户：" else "你：")
            sb.append(m.text.trim())
        }
        return sb.toString()
    }
}
