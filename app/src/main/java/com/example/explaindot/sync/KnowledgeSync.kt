package com.example.explaindot.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.explaindot.analysis.AnalysisController
import com.example.explaindot.knowledge.KnowledgeBase
import com.example.explaindot.knowledge.KnowledgeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「本地知识库」和「远端那份文件」之间的两个动作。就是用户说的那两种，没有第三种。
 *
 * 这个对象只做编排，每一件实事都在别处：
 *   - 远端：[SyncTarget] / [GitHubTarget]（含真 HTTP 服务的单测）
 *   - 本地：整库的导出 / 校验 / 替换，三件都在 [KnowledgeStore] 上
 *
 * 配置的保存（连同「问出账号名和默认分支」）不在这里，见 [SyncTarget.resolve] ——
 * 那是设置页的动作，和"同步"是两件事。
 *
 * ## 搬的是整个数据库文件
 *
 * 早先搬的是一份 JSON 导出。改成直接搬 SQLite 文件之后这一层薄了很多：
 * 中间那道编解码没有了，字节从库里出来、原样上传，拉下来、验过、原样换回去。
 *
 * 代价是**远端那份东西不再能读、也不能 diff 了** —— 它是一个二进制数据库。
 * 这是明确选的：要的是"两边逐字节一致"，而不是"能在仓库里看见每次改了什么"。
 *
 * 有一件事因此变得更要紧：**校验必须在动本地之前做完**（见 [pull]）。
 * JSON 那时候解码失败会抛异常、本地完好；现在换文件是破坏性的，
 * 一个坏文件能把攒了几个月的库换没。
 *
 * ## 为什么状态挂在这里
 *
 * 界面要看到「正在上传 / 上次失败说了什么」，而这两个动作是异步的。
 * 用 Compose 状态（和 [AnalysisController] 一样）让设置页直接读它，
 * 比再往 [com.example.explaindot.ui.AppStatus] 里塞四个字段干净 ——
 * AppStatus 是首页也要读的快照，同步状态对首页毫无意义。
 *
 * ## 失败一律不往上抛
 *
 * 网络、鉴权、校验、磁盘，任何一步出问题都变成界面上一句话。
 * 往上抛的话，用户看到的是"设置页崩了"，而真正的原因（令牌过期了）
 * 反而没人告诉他。
 */
object KnowledgeSync {

    /** 两个动作各自进行到哪儿了 */
    sealed interface State {
        data object Idle : State

        /** [upload] 区分方向，只为了让提示词对得上（"正在上传" / "正在下载"） */
        data class Working(val upload: Boolean) : State

        data class Result(val ok: Boolean, val message: String) : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    val target: SyncTarget get() = SyncTargets.primary

    /**
     * 本地整份覆盖远端。
     *
     * 空库也照推（会清空远端）—— 这是"强制"的一部分。挡住它需要判断
     * "用户是不是真的想这样"，而那正是确认框的活：框里会写明有多少条。
     *
     * 中间那个文件放在 cacheDir：它是随时能由库再生成一份的导数，
     * 不该占着应用自己的目录，也不该跟着备份走。
     */
    suspend fun push() {
        state = State.Working(upload = true)
        state = try {
            val bytes = withContext(Dispatchers.IO) {
                val scratch = File(KnowledgeBase.scratchDir, EXPORT_NAME)
                try {
                    store().exportTo(scratch)
                    scratch.readBytes()
                } finally {
                    // 成功失败都清掉。它在缓存目录里，但没理由指望系统
                    // 什么时候才来收 —— 一次上传留一个几百 KB 的副本不合适
                    scratch.delete()
                }
            }
            State.Result(true, target.push(bytes))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            State.Result(false, t.toUserMessage())
        }
    }

    /**
     * 远端整份覆盖本地。**本地独有的概念会被删掉。**
     *
     * 顺序是要紧的，而且是这份代码里唯一一处**不可颠倒**的顺序：
     *
     *   1. 把远端那份字节读下来
     *   2. 落到一个临时文件里，**验明它是一个本应用的、完整的库**
     *   3. 只有验过了，才准它去换本地那份
     *
     * 反过来的话，一次读不回来的同步能把攒了几个月的库换成一个别的什么文件 ——
     * 而那时旧库已经被覆盖掉了，没有任何东西能还原它。
     *
     * 换文件那一步（[KnowledgeStore.replaceWith]）自己也是"先写临时文件、
     * 再原子改名"，所以本地要么是旧库、要么是新库，不会停在中间状态。
     */
    suspend fun pull() {
        state = State.Working(upload = false)
        state = try {
            val bytes = target.pull()
            if (bytes == null) {
                // 不是错误，是"那边还没有东西"。第一次用就是这样
                State.Result(false, "远端还没有这份文件。先「强制上传」一次。")
            } else {
                val message = withContext(Dispatchers.IO) {
                    val incoming = File(KnowledgeBase.scratchDir, INCOMING_NAME)
                    try {
                        incoming.writeBytes(bytes)

                        // 第 2 步：验。它同时回答"是不是我们的库"和"表结构对不对"，
                        // 不合格会抛 IllegalArgumentException
                        store().inspect(incoming)

                        // 第 3 步：才轮到动本地
                        store().replaceWith(incoming)

                        // 换完之后重新读一次统计，用本地真实的数字说话 ——
                        // 它和卡片上那行必须是同一个来源
                        val after = store().stats()
                        "已用远端的 ${after.concepts} 个概念 / ${after.tags} 条解释 覆盖本地。"
                    } finally {
                        incoming.delete()
                    }
                }
                // 主流程的词表（决定哪些词能点）必须跟着重建，
                // 否则刚拉下来的概念在主页面里点不开 —— 那要等到重启才对
                AnalysisController.shared.refreshLibrary()
                State.Result(true, message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            State.Result(false, t.toUserMessage())
        }
    }

    /** 清掉上一次的结果（重开对话框、离开设置页时用） */
    fun clearResult() {
        if (state is State.Result) state = State.Idle
    }

    private fun store(): KnowledgeStore = KnowledgeBase.store

    private const val EXPORT_NAME = "knowledge-export.db"
    private const val INCOMING_NAME = "knowledge-incoming.db"
}
