package com.example.explaindot.knowledge

import android.content.Context
import java.io.File

/**
 * 本地知识库的单例入口。
 *
 * 存在的理由和 [com.example.explaindot.ai.AiUserSettings] 一样：
 * 读知识库的地方有两个（分析控制器、设置页的统计），它们都拿不到 Context，
 * 也不该为了拿一个 Context 而层层传参。进程启动时注入一次，之后随便用。
 *
 * **为什么不让 AnalysisController 自己持有。** 那个类刻意不碰任何需要 Context
 * 的 Android 组件（它挂在进程上，见它的类注释），把 Context 塞进去等于
 * 破坏那条边界。这个对象只负责「保住一个 Context 引用」，不做别的事。
 *
 * 传进来的是 applicationContext，所以不会漏 Activity。
 */
object KnowledgeBase {

    @Volatile
    private var instance: KnowledgeStore? = null

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (instance == null) {
            synchronized(this) {
                if (instance == null) {
                    val app = context.applicationContext
                    // 传进来的是 applicationContext，所以不会漏 Activity
                    instance = KnowledgeStore(app)
                    appContext = app
                }
            }
        }
    }

    /**
     * 拿知识库。没初始化过就抛 —— 不自动兜底 new 一个。
     *
     * 兜底看着更"稳"，实则是把「[init] 没被调用」这个错误藏起来，
     * 变成运行时某处莫名失效。启动时就炸掉反而好查。
     * 挂接点在 [com.example.explaindot.ExplainDotApp.onCreate]。
     */
    val store: KnowledgeStore
        get() = requireNotNull(instance) {
            "KnowledgeBase.init() 还没被调用 —— 检查 ExplainDotApp 是否挂上了 AndroidManifest 的 android:name"
        }

    /**
     * 放临时文件的地方，给同步用 —— 导出的库副本、待校验的下载件。
     *
     * 挂在这里而不是让调用方自己搞一个 Context：这个对象本来就握着
     * applicationContext，白拿一个不难；而同步那边（[com.example.explaindot.sync.KnowledgeSync]）
     * 是个纯编排对象，为它多开一条 Context 依赖不划算。
     */
    val scratchDir: File
        get() = requireNotNull(appContext) {
            "KnowledgeBase.init() 还没被调用"
        }.cacheDir
}
