package com.example.explaindot.ui

import android.content.Context
import com.example.explaindot.ai.AiConfig
import com.example.explaindot.ai.AiUserSettings
import com.example.explaindot.ai.ChatProfile
import com.example.explaindot.capture.AccessibilityShotService
import com.example.explaindot.overlay.OverlayService
import com.example.explaindot.permission.OverlayPermission

/**
 * 一次性的状态快照。
 *
 * 真相一半在系统里（悬浮窗权限、服务进程、无障碍开关），一半在用户的配置里
 * （[AiUserSettings] 的 key/模型/思考强度、[ChatProfile] 的两段文字）。
 * 这里只负责在某一刻把它们读出来打包。
 * 不做缓存、不做本地状态 —— 页面每次 onResume 重新读一遍，用户从系统设置页
 * 回来时看到的一定是真实状态，不会出现「显示已授权但其实没授权」。
 *
 * **所有字段必须放在主构造器里，不能写成类体属性。** data class 的 equals
 * 只比较主构造器属性，写在类体里的字段不参与 —— 而 `status` 是
 * `mutableStateOf`，它靠 equals 判断"变没变"。放进类体的话，用户只改了
 * 思考强度、其余都没动时，新旧两个 AppStatus 会被判定为相等，界面就不重组了：
 * 设置真的存进去了，卡片上却还显示旧值。
 *
 * 最后几项给了默认值，只为让 @Preview 里能少写几个参数。
 */
data class AppStatus(
    val overlayGranted: Boolean,
    val dotRunning: Boolean,
    val a11yCapture: Boolean,

    val aiConfigured: Boolean = false,
    val aiModel: String = "",
    /** 实际生效的**解释**思考强度：off / low / high / max */
    val aiThinking: String = "",
    /**
     * 实际生效的**对话**思考强度。
     *
     * 和 [aiThinking] 分开两项，因为它们各读各的配置（见 AiConfig.chatThinkingMode）。
     * 合成一项的话，设置页上那个"对话思考强度"会显示成解释那一档的值 ——
     * 而那正是用户刚刚改过、最想核对的地方。
     */
    val aiChatThinking: String = "",
    /** 用户填的 key 的脱敏形式，只够认出「填的是哪一个」。空串表示没填 */
    val aiMaskedKey: String = "",

    /**
     * 用户写的两段对话素材，**单行预览形式**（换行已折成空格、截到 40 字）。
     *
     * 卡片那一行要的是"确认填过没有"，不是读完它 —— 全文在对话框里看。
     * 存预览而不是全文还有个副作用是好的：用户只改了第 41 个字时预览不变，
     * 这个快照就不算变化、不触发多余的重组。
     */
    val chatBackgroundPreview: String = "",
    val chatSkillPreview: String = ""
) {

    /**
     * 还没做完的事，按「先一次性配置、再每次会话」的顺序排。
     *
     * 顺序就是 [Blocker] 的枚举顺序，这里不另写一遍 —— 两份顺序迟早会飘开。
     * 「哪一项算没做完」用穷尽的 when 表达，枚举里新增一项时编译不过。
     *
     * 首页只在非空时才显示那行提示 —— 一切正常的时候首页上只有分析结果，
     * 没有任何状态信息。这是这次改版的全部要点。
     *
     * 屏幕取图也在这一列里，因为它是唯一的取图通路：没开就等于截不到图。
     * 注意判据是系统开关（[a11yCapture]）而不是服务实例 —— 服务被系统重启的
     * 间隙实例会短暂为 null，那时候喊"未开启"是在说谎。
     */
    val blockers: List<Blocker>
        get() = Blocker.entries.filter { isBlocking(it) }

    private fun isBlocking(blocker: Blocker): Boolean = when (blocker) {
        Blocker.Ai -> !aiConfigured
        Blocker.Overlay -> !overlayGranted
        Blocker.Capture -> !a11yCapture
        Blocker.Dot -> !dotRunning
    }

    enum class Blocker(val label: String) {
        // 一次性的配置，顺序跟设置页里从上到下一致
        Ai("AI 未配置"),
        Overlay("悬浮窗未授权"),
        Capture("屏幕取图未开启"),

        // 每次会话（重启、被强行停止之后）都要重新做一次
        Dot("圆点未开启");

        /**
         * 这一项该怎么解决。
         *
         * 跟枚举写在一起、并且用穷尽的 when：新增一项时这里编译不过，逼着人把
         * 说法补上。这不是洁癖 —— API Key 和悬浮窗就是这么漏掉的：
         * 首页那时只给一个「去设置」按钮，却没有一行字说缺的是哪一项，
         * 用户照着引导走完、框了一段，才在最后撞上「还没填 API Key」。
         *
         * 配套的另一半在 MainScreen 的用法引导里：那儿直接把 [blockers]
         * 映射成 [fix] 列出来，所以只要这里不缺项，那份清单就不会缺项。
         */
        val fix: String
            get() = when (this) {
                Ai -> "在设置里填上 DeepSeek API Key —— 没有它问不了模型"
                Overlay -> "在设置里给「悬浮窗」授权 —— 圆点要浮在其他应用上面"
                Capture -> "在设置里开启「屏幕取图」—— 开一次长期有效"
                Dot -> "回到这一页，开启圆点"
            }
    }

    companion object {
        fun read(context: Context): AppStatus = AppStatus(
            overlayGranted = OverlayPermission.isGranted(context),
            dotRunning = OverlayService.isRunning,
            // 查系统设置而不是服务实例：服务被系统重启的间隙实例会短暂为 null，
            // 但用户那边的开关其实还开着，界面不该闪一下变回「未开启」
            a11yCapture = AccessibilityShotService.isEnabled(context),
            aiConfigured = AiConfig.isConfigured,
            aiModel = AiConfig.model,
            aiThinking = AiConfig.thinkingMode,
            aiChatThinking = AiConfig.chatThinkingMode,
            aiMaskedKey = AiUserSettings.maskedKey,
            chatBackgroundPreview = ChatProfile.backgroundPreview,
            chatSkillPreview = ChatProfile.skillPreview
        )

        /**
         * 全是初始值的占位快照。
         *
         * Activity 的成员初始化跑在构造函数里，那时 [android.content.Context] 还没挂上，
         * 任何 `读 Context` 的调用都会拿到 null 的 base 并抛 NPE。所以状态字段
         * 只能先用这个占位，等 onCreate 里能安全读 Context 了再用 [read] 覆盖。
         */
        val NONE = AppStatus(
            overlayGranted = false,
            dotRunning = false,
            a11yCapture = false
        )
    }
}
