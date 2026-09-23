package com.example.explaindot.ai

import com.example.explaindot.BuildConfig

/**
 * DeepSeek 配置的唯一出处。
 *
 * 值有两个来源，优先级是「用户改过 > 编译期默认」：
 *   - **编译期默认值** 来自项目根目录的 ai.properties，经 BuildConfig 注入。
 *     改完必须重新编译 —— 这些是开发者调优用的旋钮，不该摆到界面上。
 *   - **用户覆盖值** 由用户在设置页自己改，存在手机上（[AiUserSettings]），
 *     立刻生效。
 *
 * 哪些交给用户，判据是「编译期猜不到的」：
 *   - API Key —— 只有用户有
 *   - 模型名 —— 官方会下线旧名字，硬编码在包里意味着每次都得重新发一版
 *   - 两种思考强度 —— 是拿时间换准确度的个人取舍，跟读什么书、聊什么有关
 *
 * 把两个来源收在同一个对象里，是为了让调用方只认 [AiConfig] 一个门面：
 * DeepSeekClient 读的是 `AiConfig.model`，它不需要知道那个值是从包里来的
 * 还是用户刚在设置里填的。
 *
 * **这个门面只管"发给服务端的请求参数"。** 用户写的提示词素材
 * （知识背景、对话理解技能）不在这儿 —— 那些是 prompt 的内容，
 * 由 [ChatProfile] 存、由 [Prompts] 拼，见 [Prompts.chatSystem]。
 */
object AiConfig {

    // ------------------------------------------------------------------ 编译期默认值

    /** 出厂模型名。用户在设置页改过的话会被盖掉 */
    val defaultModel: String = BuildConfig.DEEPSEEK_MODEL.trim().ifBlank { "deepseek-flash" }

    /** 出厂的解释思考强度。同样可能被用户盖掉 */
    val defaultThinking: String = validLevel(BuildConfig.DEEPSEEK_THINKING).ifEmpty { "off" }

    /**
     * 出厂的对话思考强度，默认 high。
     *
     * 和解释那边默认 off 不同，这是有意的：解释是一次框选里的两个来回，
     * 用户站在那儿等结果，速度快才是净收益；对话是用户自己进来聊的，
     * 等几秒在预期之内，而它要读懂整张图、还要接住一轮轮追问 ——
     * 判断"他到底在问什么"本身就吃推理。答偏一轮的代价，比首字慢三秒大得多。
     */
    val defaultChatThinking: String =
        validLevel(BuildConfig.DEEPSEEK_CHAT_THINKING).ifEmpty { "high" }

    val baseUrl: String = BuildConfig.DEEPSEEK_BASE_URL.trim().trimEnd('/')

    val useSystemProxy: Boolean = BuildConfig.DEEPSEEK_USE_SYSTEM_PROXY

    val connectTimeoutSeconds: Long = BuildConfig.DEEPSEEK_CONNECT_TIMEOUT_SECONDS.toLong()

    val readTimeoutSeconds: Long = BuildConfig.DEEPSEEK_READ_TIMEOUT_SECONDS.toLong()

    /** 送给模型的图不必无损。注意它省的是流量，不是 token —— 图片按尺寸计费 */
    val imageQuality: Int = BuildConfig.DEEPSEEK_IMAGE_QUALITY.coerceIn(1, 100)

    /**
     * 输出长度上限。默认 2048 —— 一段 500 字的解释约 900 token，留了一倍余量。
     *
     * 不设的话非思考模式默认按 8K 走，模型偶尔啰嗦起来，等待时间全花在废话上。
     */
    val maxTokens: Int = BuildConfig.DEEPSEEK_MAX_TOKENS.coerceIn(256, 393216)

    // ------------------------------------------------------------------ 实际生效值

    /**
     * 用户填的 DeepSeek API Key。
     *
     * 下面这几个都是 getter 而不是 val：每次读都去取当前值，所以设置页
     * 改完下一个请求就用新的，不需要重启 App 或者重建客户端。
     */
    val apiKey: String get() = AiUserSettings.apiKey

    /** 实际使用的模型：用户改过就用他的 */
    val model: String get() = AiUserSettings.model.ifBlank { defaultModel }

    /**
     * 实际使用的**解释**思考强度：off / low / high / max。
     *
     * off 是默认值，也是这个 App 最关键的延迟开关。DeepSeek 的思考模式
     * 官方默认是「开启、强度 high」—— 不在请求里显式关掉，用户框完一次要干等十几秒。
     * 而「解释一个看不懂的词」属于知识型任务，链式推理带来的提升远不抵等待成本。
     *
     * 保留其余三档是因为它们有真实价值：哲学、数学证明类的概念调到 low，
     * 解释的准确度能看出来差别。这也是它值得做成运行时开关的原因 ——
     * 该用哪一档取决于当下读的是什么书。
     */
    val thinkingMode: String get() = AiUserSettings.thinking.ifBlank { defaultThinking }

    /**
     * 实际使用的**对话**思考强度。
     *
     * 和 [thinkingMode] 各读各的，互不影响。默认值也不同（那边 off、这边 high），
     * 原因见 [defaultChatThinking]。
     */
    val chatThinkingMode: String get() = AiUserSettings.chatThinking.ifBlank { defaultChatThinking }

    val thinkingEnabled: Boolean get() = thinkingMode != "off"

    val chatThinkingEnabled: Boolean get() = chatThinkingMode != "off"

    /**
     * 传给推理强度字段的值。
     *
     * off 映射成 none（官方语义就是「关闭思考」），和 thinking.type=disabled
     * 说的是同一件事，两个都发是为了不依赖任何一边的兼容性。
     */
    val reasoningEffort: String get() = effortFor(thinkingMode)

    /** 对话那一路的同名参数。同 [reasoningEffort]，只是读的是对话那一档 */
    val chatReasoningEffort: String get() = effortFor(chatThinkingMode)

    /**
     * 档位 → 请求参数。**纯函数，单独拎出来是为了能测。**
     *
     * 它决定真正发出去的那个字符串，而发错了不会有任何报错 —— 服务端只当
     * 是个没听懂的档位，静默按默认值跑。所以这几行值得有测试盯着。
     *
     * 兜底给 high 而不是 off：走到 else 只可能是上游放进来了一个不认识的档位
     * （正常情况下 [AiUserSettings] 已经过滤掉了）。那时宁可多等几秒也别悄悄
     * 把用户要的"想深一点"变成"别想"。
     */
    fun effortFor(mode: String): String = when (mode) {
        "off" -> "none"
        "low" -> "low"
        "max" -> "max"
        else -> "high"
    }

    /** 把编译期来的档位规整一下：认不出的当作没填，交给调用方兜底 */
    private fun validLevel(raw: String): String =
        raw.trim().lowercase().takeIf { it in AiUserSettings.THINKING_LEVELS }.orEmpty()

    /** 没填 key 时界面要给明确指引，而不是抛一个看不懂的网络异常 */
    val isConfigured: Boolean get() = AiUserSettings.isConfigured

    const val CHAT_COMPLETIONS_PATH = "/chat/completions"
}
