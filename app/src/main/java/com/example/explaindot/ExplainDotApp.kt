package com.example.explaindot

import android.app.Application
import com.example.explaindot.ai.AiUserSettings
import com.example.explaindot.ai.ChatProfile
import com.example.explaindot.knowledge.KnowledgeBase
import com.example.explaindot.sync.SyncSettings

/**
 * 存在的唯一理由：把 Application 的 Context 交给几个进程级单例 ——
 * [AiUserSettings]（AI 凭据与请求参数）、[ChatProfile]（用户写的对话素材）、
 * [SyncSettings]（远程同步的配置）、[KnowledgeBase]（本地知识库）。
 *
 * 读它们的地方（状态快照、DeepSeekClient、分析控制器、设置页）都拿不到
 * Context，也不该为了拿几个字符串或一个数据库而层层传参。
 * 进程启动时注入一次，之后随便读。
 *
 * 因为要在 AndroidManifest 里挂 `android:name`，这个类不能少；
 * 少了它，app 会在第一次发请求时告诉你去检查清单。
 */
class ExplainDotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AiUserSettings.init(this)
        ChatProfile.init(this)
        SyncSettings.init(this)
        KnowledgeBase.init(this)
    }
}
