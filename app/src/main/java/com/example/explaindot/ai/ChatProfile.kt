package com.example.explaindot.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 用户自己写的两段文字，用来塑造「对话理解」的回答方式：知识背景、对话理解技能。
 *
 * ## 为什么不放进 [AiUserSettings]
 *
 * 那个文件里全是凭据（API Key），所以整份被排除在系统备份之外 —— 见
 * backup_rules.xml。这两段文字不是凭据，是用户一个字一个字敲进去的，
 * **换手机时应该跟着云备份走**。混进那个文件就等于顺手把它们也丢给了不会备份的命运，
 * 而用户不会预期"我写的背景资料因为旁边存着一把钥匙而消失"。
 *
 * 所以单独一个文件。它里面没有任何秘密，因此不需要排除规则，
 * 也就不必维护一份"哪些键是秘密"的清单 —— 那种清单漏一项就是一次静默泄露。
 *
 * ## 为什么有长度上限
 *
 * 这两段会被**每一轮对话**原样发给模型，而且它们在 system 段 ——
 * 没有 KV 缓存之外的折扣。用户完全可能想贴一整篇自述进来，
 * 但那样每问一句都把这些字重新付一次费，而收益在超过某个长度后就不再增加。
 *
 * [MAX_LENGTH] 是硬上限，界面在输入时就挡住（而不是保存时才报错）——
 * 用户能看到字数字着涨，而不是写完一大段才发现存不下去。
 */
object ChatProfile {

    /**
     * 偏好文件名。
     *
     * **故意不出现在备份排除名单里**，也别加进去 —— 这里没有凭据，
     * 而这两段文字正是最该被备份的东西。见类注释。
     */
    const val PREFS_NAME = "chat_profile"

    private const val KEY_BACKGROUND = "background"
    private const val KEY_SKILL = "skill"

    /**
     * 每段的上限（字符）。
     *
     * 4000 字中文约 3000 token 上下 —— 已经明显多于"说清自己是谁、
     * 想让对方怎么讲话"所需要的量，又不至于让每轮请求因为它而变贵。
     * 到了这个数还嫌不够，多半是该拆成两条要求写得更集中，而不是继续加长。
     */
    const val MAX_LENGTH = 4000

    private var prefs: SharedPreferences? = null

    /** 由 Application 在进程启动时调用一次。理由同 [AiUserSettings.init] */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 知识背景：用户自述的学识、职业、熟悉什么。
     *
     * 用途是让模型自己调深浅，而不是靠它在每一轮里猜。用户问「这个递归怎么写」
     * 和问「递归是什么东西」，同一个模型该给两种回答 —— 差别不在问题上，
     * 在问的人身上。
     */
    var background: String
        get() = read(KEY_BACKGROUND)
        set(value) = write(KEY_BACKGROUND, value)

    /**
     * 对话理解技能：用户对回答方式的要求（讲多细、要不要举例、先给结论还是先讲推导）。
     *
     * 和 [background] 分开存，因为它们是两件事：一个是"我是谁"，
     * 一个是"请你怎么说话"。合成一个框的话，用户改其中一半时得先把另一半抄出来。
     */
    var skill: String
        get() = read(KEY_SKILL)
        set(value) = write(KEY_SKILL, value)

    val hasBackground: Boolean get() = background.isNotEmpty()

    val hasSkill: Boolean get() = skill.isNotEmpty()

    /**
     * 界面上的单行预览。空串表示没填过。
     *
     * 换行折成空格、截到 [PREVIEW_LENGTH]：这两段动辄几百字，
     * 卡片那一行放不下，而用户需要的是"确认填过"，不是"在这儿读完它"。
     */
    val backgroundPreview: String get() = preview(background)

    val skillPreview: String get() = preview(skill)

    private fun preview(text: String): String {
        if (text.isEmpty()) return ""
        val flat = text.replace('\n', ' ').replace(Regex(" +"), " ").trim()
        return if (flat.length <= PREVIEW_LENGTH) flat else flat.take(PREVIEW_LENGTH) + "…"
    }

    /**
     * 读不到时返回空串，不抛异常 —— 理由同 [AiUserSettings.read]：
     * @Preview 和单元测试里不会跑 Application。写的那一侧不含糊。
     */
    private fun read(key: String): String = prefs?.getString(key, null)?.trim().orEmpty()

    private fun write(key: String, value: String) {
        val store = requireNotNull(prefs) {
            "ChatProfile.init() 还没被调用 —— 检查清单里的 android:name 有没有指向 ExplainDotApp"
        }
        store.edit { putString(key, value.trim().take(MAX_LENGTH)) }
    }

    private const val PREVIEW_LENGTH = 40
}
