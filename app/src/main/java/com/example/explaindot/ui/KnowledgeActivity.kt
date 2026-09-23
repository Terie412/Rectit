package com.example.explaindot.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.explaindot.analysis.AnalysisController
import com.example.explaindot.ui.theme.ExplainDotTheme

/**
 * 知识库浏览页的宿主。
 *
 * 这个 Activity 刻意做得很薄：页面自己的状态（搜索词、选中项、跳转路径、
 * 展开与否）全部由 [KnowledgeScreen] 用 rememberSaveable 管着 ——
 * 那些都是界面的状态，写到这里反而要自己实现 onSaveInstanceState，
 * 而且状态和用它的界面被拆到两个文件里。
 *
 * **它只负责传两个外部依赖进去**：
 *   · 词表（决定解释正文里哪些词能点）—— 来自进程级的主流程控制器，全局只有一份
 *   · 「我改了库」的通知回执 —— 在这一页删掉解释之后，得让主流程重建词表
 *
 * 词表和知识库都在进程单例里活得比这个页面久，所以页面反复进出不用担心重复读库。
 */
class KnowledgeActivity : ComponentActivity() {

    private val analysis get() = AnalysisController.shared

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ExplainDotTheme {
                KnowledgeScreen(
                    // 读它会让这个 Composable 订阅到词表变化 ——
                    // 删掉解释后词表重建，这一页的链接样式会自己跟着更新
                    library = analysis.matcher,
                    onBack = { finish() },
                    onLibraryChanged = { analysis.refreshLibrary() }
                )
            }
        }
    }
}
