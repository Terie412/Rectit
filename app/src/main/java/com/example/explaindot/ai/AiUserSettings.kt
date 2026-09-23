package com.example.explaindot.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 用户能在 App 里改的 AI 设置：API Key、模型、两种思考强度。
 *
 * 归一个对象，是因为它们的性质相同 —— 都是"编译期不知道、只有用户知道"
 * 的值，都要能在设置页改完立刻生效。剩下的参数（地址、超时、图片质量、
 * 输出上限）仍然走 ai.properties 编译期注入，那些是开发者调优用的，
 * 摆进界面只会变成一堆没人敢动的旋钮。
 *
 * 思考强度有两个（[thinking] 管解释、[chatThinking] 管对话），
 * 理由见各自的注释 —— 简单说，两个场景该等多久是相反的取舍。
 *
 * 早先这些值全走编译期注入，那条路的两个问题：
 *   1. Key 被打进 APK，换 key 必须重新编译装机
 *   2. 模型名会过期 —— 官方下线一个名字，就得重新发一版
 *
 * **空串 = 用编译期的默认值。** 这条约定让"用户改过没有"不需要额外的标志位，
 * 也让「恢复默认」就是存一个空串那么简单。
 *
 * 值落在应用私有的 SharedPreferences 里 —— 那已经是系统沙箱保护的位置。
 * 没有加密：加密只会把密钥和它存在同一台手机上（自欺欺人），有效的防护
 * 是别把它交出去，所以下面那份备份规则把整个文件排除在外了。
 *
 * **这个文件里只有凭据性质的东西**，所以整份排除备份是安全的做法。
 * 用户手写的文字（知识背景之类）不放这儿，见 [ChatProfile]。
 */
object AiUserSettings {

    /**
     * 偏好文件名。
     *
     * 这个名字同时出现在 backup_rules.xml 和 data_extraction_rules.xml 里，
     * 改这里必须一起改 —— 否则 key 会被系统悄悄备份到云端。
     */
    const val PREFS_NAME = "ai_user_settings"

    private const val KEY_API = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_THINKING = "thinking"
    private const val KEY_CHAT_THINKING = "chat_thinking"

    /**
     * 思考强度的四档，顺序就是由快到慢。
     *
     * 这是个封闭集合（官方语义），所以校验和界面都拿它当唯一出处 ——
     * 商店里存着的值只可能是这四档之一或者空。
     */
    val THINKING_LEVELS = listOf("off", "low", "high", "max")

    private var prefs: SharedPreferences? = null

    /**
     * 由 Application 在进程启动时调用一次。
     *
     * 之所以要一个 Application 而不是靠懒加载：读这些值的地方（状态快照、
     * DeepSeekClient）拿不到 Context，而它们都在 Activity 之后才跑，
     * 所以"进程起来时注入一次"最省事，也不用层层传 Context。
     */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** DeepSeek API Key。没填过就是空串 */
    var apiKey: String
        get() = read(KEY_API)
        set(value) = write(KEY_API, value.trim())

    /** 模型名覆盖。空串表示用 ai.properties 里的默认值 */
    var model: String
        get() = read(KEY_MODEL)
        set(value) = write(KEY_MODEL, value.trim())

    /**
     * 概念解释的思考强度覆盖。空串表示用默认值。
     *
     * 存进来的值一律按 [THINKING_LEVELS] 过滤：读的时候发现不是四档之一
     * 就当作没设过。宁可悄悄回落到默认，也好过把一个认不出的值发给服务端。
     */
    var thinking: String
        get() = read(KEY_THINKING).lowercase().takeIf { it in THINKING_LEVELS }.orEmpty()
        set(value) = write(KEY_THINKING, value.trim().lowercase())

    /**
     * 对话理解的思考强度覆盖。空串表示用默认值（默认是 high）。
     *
     * **和 [thinking] 分开存，不是重复。** 这两个场景的取舍正好相反：
     * 解释是一次框选里的两个来回，用户站在那儿等，off 换来的速度是净收益；
     * 对话是他主动进来聊的，多等几秒在预期之内，而答偏一轮的代价是
     * 后面好几轮都在纠正。共用一个值的话，调其中一边必然委屈另一边。
     *
     * 校验规则与 [thinking] 完全一致 —— 两边都从 [THINKING_LEVELS] 里取。
     */
    var chatThinking: String
        get() = read(KEY_CHAT_THINKING).lowercase().takeIf { it in THINKING_LEVELS }.orEmpty()
        set(value) = write(KEY_CHAT_THINKING, value.trim().lowercase())

    val modelIsCustom: Boolean get() = model.isNotEmpty()

    val thinkingIsCustom: Boolean get() = thinking.isNotEmpty()

    val chatThinkingIsCustom: Boolean get() = chatThinking.isNotEmpty()

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /**
     * 给界面看的脱敏形式。
     *
     * 只留头尾各几个字符：够你认出"填的是哪一个 key"，又不足以让别人
     * 从一张截图里把它抄走。
     */
    val maskedKey: String
        get() {
            val key = apiKey
            return when {
                key.isEmpty() -> ""
                key.length <= 10 -> "•".repeat(8)
                else -> "${key.take(6)}…${key.takeLast(4)}"
            }
        }

    /**
     * 读不到时返回空串而不是抛异常：@Preview 和单元测试里不会跑 Application，
     * 那些场合构造一次状态快照不该崩。写的那一侧就不含糊了 —— 见 [write]。
     */
    private fun read(key: String): String = prefs?.getString(key, null)?.trim().orEmpty()

    private fun write(key: String, value: String) {
        val store = requireNotNull(prefs) {
            "AiUserSettings.init() 还没被调用 —— 检查清单里的 android:name 有没有指向 ExplainDotApp"
        }
        store.edit { putString(key, value) }
    }
}
