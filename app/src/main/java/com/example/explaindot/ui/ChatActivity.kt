package com.example.explaindot.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.explaindot.chat.ChatSession
import com.example.explaindot.ui.theme.ExplainDotTheme

/**
 * 对话理解页的宿主。
 *
 * 和 [KnowledgeActivity] 一样薄：会话状态全在 [ChatSession] 里，那是个比页面
 * 活得久的进程级单例 —— 用户中途跳去别的 App 看一眼再回来，聊到一半的内容
 * 不该丢。所以这个 Activity 不持有任何状态，只负责把它递进去。
 *
 * 它**不做「图换了就清空」那件事**：那个判断需要知道当前是哪张图，
 * 而那份状态的唯一出处是 [com.example.explaindot.analysis.AnalysisController]，
 * 由它在换图时推过来（见那里的 `load`）。
 */
class ChatActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ExplainDotTheme {
                ChatScreen(
                    session = ChatSession.shared,
                    onBack = { finish() }
                )
            }
        }
    }
}
