package com.example.explaindot.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 远程同步的配置。
 *
 * **和 [com.example.explaindot.ai.AiUserSettings] 的差别值得说一句。**
 * 那边是固定几个字段的强类型属性（apiKey / model / thinking），因为 AI 服务
 * 只有一个，字段不会变。这边是**按 key 存取的通用仓库**，因为配置项由各个
 * 平台适配器自己声明（见 [SyncTarget.fields]）—— GitHub 要的是
 * token/owner/repo/分支/路径，以后 OneDrive 要的是另一套。
 * 各平台把自己的 key 写进这个仓库，界面按声明渲染，两边都不需要知道别人。
 *
 * **值落在应用私有的 SharedPreferences 里。** 里面有一个 GitHub token，
 * 所以这个文件名同时出现在 backup_rules.xml 和 data_extraction_rules.xml 的
 * 排除列表里 —— 改这里必须一起改，否则 token 会被系统悄悄备份到云端。
 * 和 AI Key 同一个理由：凭据不该离开这台手机。
 */
object SyncSettings {

    /**
     * 偏好文件名。
     *
     * 这个名字同时出现在 backup_rules.xml 和 data_extraction_rules.xml 里，
     * 改这里必须一起改。
     */
    const val PREFS_NAME = "sync_user_settings"

    private var prefs: SharedPreferences? = null

    /** 由 Application 在进程启动时调用一次。理由同 [AiUserSettings.init] —— 读它的地方拿不到 Context */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 读一个配置项。没设过就是空串。
     *
     * 空串就是空串，**没有"默认值"这一层** —— 早先有过（分支和文件路径留空
     * 就用 `main` 和 `knowledge.json`），但那两个框现在都没了，
     * 剩下的项都是必填。少一层兜底就少一处"值到底从哪来"要追。
     */
    fun get(key: String): String = prefs?.getString(key, null)?.trim().orEmpty()

    fun set(key: String, value: String) {
        val store = requireNotNull(prefs) {
            "SyncSettings.init() 还没被调用 —— 检查清单里的 android:name 有没有指向 ExplainDotApp"
        }
        store.edit { putString(key, value.trim()) }
    }
}
