package com.example.explaindot.chat

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.explaindot.ai.DeepSeekClient
import com.example.explaindot.ai.DeepSeekException
import com.example.explaindot.ai.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 「对话理解这张图」的会话状态。
 *
 * ## 生命周期跟着"当前这张图"
 *
 * 整场对话**只在内存里**，没有落盘 —— 用户要的就是"临时的"。
 * 而它的生死跟着图走：新图进来（[onImageChanged]）旧对话立刻作废。
 *
 * 这个类**自己不做这个判断**，是外面调进来的。理由：判断"图变没变"需要知道
 * 当前图是什么，而那份状态的唯一出处是 AnalysisController。让它推过来，
 * 比这里再存一份、然后两处慢慢对不上要好。
 *
 * 挂在进程上而不是 Activity 上，和 [com.example.explaindot.analysis.AnalysisController]
 * 同一个理由：用户可能中途跳去别的 App 看一眼再回来，回答不该因此丢掉。
 * 它不碰任何需要 Context 的组件，所以挂进程上不漏 Context。
 *
 * ## 状态为什么摆成这样
 *
 * [history] 是**已完成**的消息，[streaming] 是正在写的那条 —— 两者分开而不是
 * 把半截回答也塞进 history：那样每收到一个字就要重建整个列表，
 * 而且"这条到底写完了没有"要额外记一个标志位，两处状态迟早走散。
 *
 * [error] 和 [retryable] 搭配：失败时那条用户消息留在历史里、并把引用记在
 * [retryable]，于是重试是"把同样的问题再发一次"，而不是让用户重新打一遍。
 */
class ChatSession(
    private val client: DeepSeekClient = DeepSeekClient()
) {

    /**
     * 进程级作用域：回答不随页面关闭而取消。
     *
     * `SupervisorJob` 而不是普通 Job：一次失败的请求不该把整个作用域带走，
     * 那会让之后所有对话都发不出去，且看不出原因。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ------------------------------------------------------------------ 对外状态

    /** 已完成的消息，按时间顺序。界面只渲染其中 [ChatMessage.visible] 的那些 */
    var history by mutableStateOf<List<ChatMessage>>(emptyList())
        private set

    /** 正在流式接收的回答。null 表示当前没有在写 */
    var streaming by mutableStateOf<String?>(null)
        private set

    /** 上一次失败的原因。[retryable] 非空时界面显示"重试" */
    var error by mutableStateOf<String?>(null)
        private set

    /** 因失败而没发出去的那条用户消息。失败时重试就是重新发它 */
    var retryable by mutableStateOf<ChatMessage?>(null)
        private set

    /**
     * 正在发请求。
     *
     * **必须是 Compose 状态，不能从 `job?.isActive` 派生。** 派生的话它变了
     * 不会触发重组 —— 界面算出来是对的，但不会重画，于是"正在思考"那个指示
     * 永远不出现、输入框的禁用态也不生效。这类 bug 在单测里看不出来
     * （值是对的），只有真机上才看得见。
     */
    var busy by mutableStateOf(false)
        private set

    /** 界面要渲染的全部内容：历史里的可见消息，加上正在写的那条 */
    val visibleMessages: List<ChatMessage>
        get() = history.filter { it.visible }

    // ------------------------------------------------------------------ 内部状态

    private var job: Job? = null

    /** 摘要覆盖到 [history] 的哪个下标之前 */
    private var covered = 0

    /** 早前对话的摘要。null 表示还没压过 */
    private var summary: String? = null

    // ------------------------------------------------------------------ 图与清空

    private var imagePath: String? = null

    /** 上一次收到的代号。初值是个 [AnalysisController] 不会发出的值，见 [sameImage] */
    private var imageGeneration = NO_IMAGE_GENERATION

    /**
     * 当前图换了。旧对话（连同摘要、进行中的请求）全部作废。
     *
     * **判据是 [generation]，不是 [path]。** 原来的版本只比路径，于是一个新图
     * 进来时它认为「还是那张图」，一整场旧对话留在界面上 —— 用户对着新图
     * 看到的是关于上一张图的问答，而且没有任何线索指向原因。
     *
     * 为什么会这样：图片槽位固定写在一个文件名上（`latest.jpg`），
     * 截图、相册、相机拿到的图**路径永远是同一个值**。路径根本回答不了
     * 「图变没变」，而这件事只有 [AnalysisController] 知道 —— 所以它每次换图
     * 递增一个代号推过来。见 [sameImage] 上的注释。
     *
     * **进行中的请求要取消。** 不取消的话，旧图那个问题的回答会在几十秒后
     * 落到新对话里，和上面是同一类问题。
     */
    fun onImageChanged(path: String?, generation: Long) {
        if (sameImage(imagePath, imageGeneration, path, generation)) return
        imagePath = path
        imageGeneration = generation

        job?.cancel()
        job = null
        history = emptyList()
        streaming = null
        error = null
        retryable = null
        covered = 0
        summary = null
    }

    // ------------------------------------------------------------------ 两个动作

    /**
     * 进入页面时调用：第一次进来就替用户问一句开场。
     *
     * 已经有历史就什么都不做 —— 用户可能只是去了趟设置页又回来，
     * 不该看到模型重新自我介绍一遍。
     */
    fun ensureOpened() {
        if (history.isNotEmpty() || busy) return
        send(Prompts.CHAT_OPENING, hidden = true)
    }

    /** 用户问了一个问题 */
    fun ask(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || busy) return
        send(trimmed, hidden = false)
    }

    /** 把上一次失败的问题重发一遍 */
    fun retry() {
        val pending = retryable ?: return
        if (busy) return

        // 先把那条消息从历史里摘掉，再走一遍正常流程 ——
        // 不摘的话它会变成两条：重试会在末尾再追加一条一模一样的。
        // 按**引用**找而不是按内容：用户完全可能连问两次同一个问题，
        // 按内容找会把更早那条正常的对话也一起摘了
        val index = history.indexOfLast { it === pending }
        if (index >= 0) {
            history = history.toMutableList().apply { removeAt(index) }
        }

        error = null
        retryable = null
        send(pending.text, hidden = pending.hidden)
    }

    /**
     * 跑一轮：压缩（如果需要）→ 发请求 → 收流。
     *
     * [hidden] 的消息也进历史 —— 它对 API 来说是正常的一轮对话，
     * 只是界面不显示。见 [ChatMessage.hidden]。
     */
    private fun send(text: String, hidden: Boolean) {
        val message = ChatMessage(ChatMessage.Role.User, text, hidden = hidden)
        history = history + message
        error = null
        retryable = null

        job = scope.launch {
            busy = true
            try {
                // 压缩放在发请求之前。它多花一次调用，换来之后每一轮都小
                compressIfNeeded()

                val image = imagePath?.let { File(it) }
                val outgoing = ChatHistory.build(history, covered, summary)

                val buffer = StringBuilder()
                streaming = ""
                client.chatStream(outgoing, image).collect { delta ->
                    buffer.append(delta)
                    streaming = buffer.toString()
                }

                val answer = buffer.toString().trim()
                streaming = null

                if (answer.isEmpty()) {
                    // 空回答不是"模型没话说"，多半是这次请求被截断或者拦了。
                    // 当成失败让用户能重试 —— 悄悄显示一片空白最糟
                    fail(message, "模型这次没有返回内容。再试一次。")
                } else {
                    history = history + ChatMessage(ChatMessage.Role.Assistant, answer)
                }
            } catch (e: CancellationException) {
                // 换图或者页面被销毁。流里那半截丢掉，不算失败
                streaming = null
                throw e
            } catch (t: Throwable) {
                streaming = null
                fail(message, t.toUserMessage())
            } finally {
                busy = false
            }
        }
    }

    /**
     * 失败收尾：把那条用户消息留在一个"可以重发"的状态。
     *
     * 消息本身**不删** —— 用户打的字不该因为网络问题就丢掉。
     * 而是把引用记到 [retryable]，界面据此显示重试。
     */
    private fun fail(message: ChatMessage, reason: String) {
        error = reason
        retryable = message
    }

    // ------------------------------------------------------------------ 压缩

    /**
     * 攒够了就压一次。
     *
     * **失败不阻断。** 摘要是一次锦上添花的调用，它挂了不该让用户问不出问题 ——
     * 那样用户看到的是"网络错误"，而真正的原因是他聊得有点久。
     * 压不成就算了，直接拿完整历史去发，只是这一轮贵一点。
     */
    private suspend fun compressIfNeeded() {
        val plan = ChatHistory.planCompression(history, covered) ?: return

        val digest = ChatHistory.digest(history, plan, summary)
        val result = runCatching { client.summarize(digest) }.getOrNull()

        if (result.isNullOrBlank()) {
            Log.i(TAG, "压缩没成，这一轮用完整历史发（${history.size} 条）")
            return
        }

        summary = result
        covered = plan.to
        Log.i(TAG, "已压缩 ${plan.to - plan.from} 条，摘要 ${result.length} 字")
    }

    // ------------------------------------------------------------------ 错误翻译

    /**
     * 异常翻成用户能照着做的一句话。
     *
     * 和 [com.example.explaindot.analysis.AnalysisController] 里那份是同一个思路：
     * [DeepSeekException] 的消息本来就是照着这个目的写的，直接用；
     * 网络那几种要单独说，因为它们的解决动作完全不同（检查网络 vs 检查代理设置）。
     */
    private fun Throwable.toUserMessage(): String = when (this) {
        is DeepSeekException -> message.orEmpty().ifBlank { "对话失败，再试一次。" }
        is UnknownHostException, is SocketTimeoutException, is SSLException ->
            "连不上 DeepSeek。检查网络；如果网是通的，看一下手机上有没有装代理类应用 —— " +
                "它们的设置经常是残留的，会让请求一律超时。"
        else -> "对话失败：${message ?: this::class.simpleName}"
    }

    companion object {
        private const val TAG = "ChatSession"

        /**
         * 全进程一个。见类注释 —— 这份状态比任何一个页面都活得久，
         * 而它的生死由「当前是哪张图」决定，不是由页面决定。
         */
        val shared: ChatSession by lazy { ChatSession() }
    }
}

/** 还没收到过任何一张图时的代号。用一个递增计数器永远不会取到的值 */
internal const val NO_IMAGE_GENERATION = Long.MIN_VALUE

/**
 * 这两次收到的是不是同一张图。
 *
 * **两个字段都要比。** 只比路径就是原来那个 bug：图片槽位固定写在 `latest.jpg`，
 * 截图、相册、相机拿到的图路径永远一样 —— 于是第二个图进来时被判成「没换」，
 * 对话历史永远清不掉，用户对着新图看到的是上一张图的问答。
 *
 * 抽成纯函数只为一件事：让这个 bug 能被测出来。它的形态是**少比一个字段** ——
 * 少比字段不报错、不崩、没有任何日志，只会让旧对话静默留下，
 * 和 `accessibilityState` 那边抽出来的理由是同一个。
 */
internal fun sameImage(
    prevPath: String?,
    prevGeneration: Long,
    nextPath: String?,
    nextGeneration: Long,
): Boolean = prevPath == nextPath && prevGeneration == nextGeneration
