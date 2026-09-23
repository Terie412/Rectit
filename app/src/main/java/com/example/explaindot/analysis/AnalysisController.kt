package com.example.explaindot.analysis

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.explaindot.ai.Concept
import com.example.explaindot.ai.DeepSeekClient
import com.example.explaindot.ai.DeepSeekException
import com.example.explaindot.ai.RefineDecision
import com.example.explaindot.chat.ChatSession
import com.example.explaindot.knowledge.ConceptMatcher
import com.example.explaindot.knowledge.KnowledgeBase
import com.example.explaindot.knowledge.KnowledgeStore
import com.example.explaindot.knowledge.LinkStack
import com.example.explaindot.knowledge.SenseParser
import com.example.explaindot.knowledge.TermNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 跳转栈里的一层。
 *
 * [context] 是「进这一层时，用户正在读的那段文字」—— 也就是包含那个链接的那条
 * 解释正文。它只为一件事存在：「重新解释」时要把这段文字发给模型，
 * 让它判断库里已有的解释和用户读的东西对不上时该怎么办。
 *
 * 从概念列表直接点进来的一层，[context] 为 null：那时用户还没读过任何解释，
 * 没有「正在读的东西」可言。这是合法情形，prompt 里有对应的说法。
 */
data class ConceptFrame(val term: String, val context: String?)

/**
 * 流程状态。
 *
 * 密封接口而不是几个 boolean 拼起来，是为了让「不可能出现的组合」在类型层面
 * 就无法表达 —— 比如「正在扫描的同时又在看解释」。
 */
sealed interface AnalysisStage {

    /**
     * 还没开始。两种情况会停在这：刚打开 App、屏幕上摆着上次那张图，
     * 或者框选完但第一问还没发出去。它和 [Scanning] 的区别是「没在等任何东西」。
     */
    data object Idle : AnalysisStage

    /** 正在把截图发给模型，等它列出概念 */
    data object Scanning : AnalysisStage

    /** 概念列表。列表本身在 controller 的 concepts 里 */
    data object ConceptList : AnalysisStage

    /**
     * 正在看某个概念。**具体是哪个不在这里** —— 读 [AnalysisController.currentFrame]。
     *
     * 之所以不写成 `Viewing(term)`：那样「当前看的是哪个词」就有了两处来源
     * （stage 和栈顶），而它们会在返回、清栈、重解释这几条路径上走散。
     * 现在栈是唯一真相，stage 只回答「在哪一屏」。
     */
    data object Viewing : AnalysisStage

    /** 第一问挂了。重试要重试这一步 */
    data class ScanFailed(val message: String) : AnalysisStage
}

/**
 * 当前这个概念要显示的内容。
 *
 * 三层含义其实是同一件事的三个阶段：库里有了就是 [Stored]，
 * 正在问模型就是 [Loading]/[Writing]，问失败了是 [Failed]。
 * 用密封接口而不是「一个可空字符串 + 一个可空错误 + 一个 loading 布尔」，
 * 是为了让「同时既在写又有错误」这种状态压根造不出来。
 */
sealed interface TermContent {

    /** 库里没有，请求已发出但还没有第一个字 */
    data object Loading : TermContent

    /** 正在流式写入。[text] 是到目前为止收到的全部内容 */
    data class Writing(val text: String) : TermContent

    /**
     * 库里的成品。[entries] 可能为空 —— 那表示用户把某个标签的解释删光了，
     * 或者上一次写库失败。界面要能处理这种情况（提示还没有解释 + 重新问）。
     */
    data class Stored(val entries: List<KnowledgeStore.Entry>) : TermContent

    data class Failed(val message: String) : TermContent
}

/**
 * 分析流程的状态机，管着「截图 → 挑概念 → 解释 → 跳转」这一条链路。
 *
 * **放在 analysis 包而不是 ui 包。** 这个类是「用例层 + 状态持有者」，
 * 它编排 DeepSeek 请求、读写知识库、维护跳转栈 —— 没有一行是渲染代码。
 * 曾经它挂在 ui 包里，害处不是编译上出了问题，而是包名在说谎：
 * 找业务逻辑的人不会往 ui 里看，找渲染代码的人又会在这里扑空。
 * 移到 analysis 之后，包结构本身就是那张架构图。
 *
 * 它仍然是全项目唯一「业务类依赖 UI 框架」的地方（`mutableStateOf` 来自
 * Compose runtime），这是有意的：界面直接读这些属性重组，不经过任何中间层。
 * 代价可以接受 —— 状态本来就只服务那一套 Composable，没有第二个消费者。
 *
 * 生命周期是「整个进程」，不是某个 Activity —— 这一点也是刻意的。
 * 这条链路上有两件很贵的资产：用户等的那个答案，和已经花掉的那次请求。
 * 而下面这些情况都会销毁 Activity：跳去设置页看权限、在别的 App 里读书、
 * 转屏。状态如果挂在 Activity 上，以上每一种都会把答案清空、逼用户重问一次。
 *
 * 这个类不碰任何需要 Context 的 Android 组件（知识库通过 [KnowledgeBase]
 * 这个单例拿，见那里的注释），挂在进程上不会漏 Context，所以用单例最省事。
 *
 * **为什么解释的缓存没了。** 原先这里有一份内存缓存，键是
 * 「第几张图 + 模型 + 思考强度 + 词」。现在解释是上下文无关的、存进 SQLite
 * 反复用，那份缓存整个多余了：换图、换模型都不该让一条正确的解释失效。
 * 于是「缓存命中」这件事从「内存里有没有」变成了「库里有没有」，
 * 判据统一在 [KnowledgeStore] 一处。
 */
class AnalysisController {

    // 进程级的协程作用域：请求不随 Activity 一起取消 —— 用户框完就走了，
    // 答案还是该算完。取消只在「换了一张图」「换了一个概念」时发生
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val client = DeepSeekClient()
    private val store get() = KnowledgeBase.store

    private var scanJob: Job? = null
    private var termJob: Job? = null
    private var refineJob: Job? = null
    private var image: File? = null

    /**
     * 跳转历史。栈顶就是当前正在看的概念。
     *
     * 允许同一个概念在栈里出现多次 —— 从 A→B→C→A 之后按三次返回要依次回到
     * C、B、A。见 [LinkStack] 的注释。
     */
    private val stack = LinkStack<ConceptFrame>()

    var stage by mutableStateOf<AnalysisStage>(AnalysisStage.Idle)
        private set

    var concepts by mutableStateOf<List<Concept>>(emptyList())
        private set

    var termContent by mutableStateOf<TermContent>(TermContent.Loading)
        private set

    /**
     * 栈的可观察镜像。每次改动栈都同步一次 —— 界面靠读它重组。
     *
     * 不直接把 [LinkStack] 做成 Compose 状态：那个类要被单元测试（它的语义
     * 值得单独测），不该拖上 Compose 的依赖。
     */
    var trail by mutableStateOf<List<ConceptFrame>>(emptyList())
        private set

    /** 「重新解释」正在进行。界面据此禁用按钮，避免连点攒出几次请求 */
    var refining by mutableStateOf(false)
        private set

    /** 上一次「重新解释」的结果，用来给用户一句反馈。null 表示还没试过 */
    var lastRefine by mutableStateOf<RefineDecision?>(null)
        private set

    /**
     * 词表匹配器。解释正文里哪些位置能跳，全靠它。
     *
     * 它是 Compose 状态：界面渲染每一条解释时都要读它算链接，
     * 换成普通字段就没有人通知重组了。库里写入或删除后重建一份。
     */
    var matcher by mutableStateOf(ConceptMatcher(emptyList()))
        private set

    /**
     * 当前这个概念的解释里，模型提到的「读者可能也不懂的其他词」。
     *
     * 界面拿它把那些词画成另一种颜色的链接（见
     * [com.example.explaindot.ui.MarkdownLite]）。**库里已有的词不在这里面** ——
     * 过滤放在渲染时做，因为词表会随着用户不断解释新问过的词而变化，
     * 存进来的时候滤掉的话，一个后来被解释了的词会永远留在「新词」那一档。
     *
     * **为什么只活在内存里、不落库。** 这一份是「刚问过模型」的副产品，
     * 不是知识本身。落库要有迁移、还要定义它和某条解释的从属关系，
     * 而它解决的问题（这次会话里顺着往下读）在进程结束时就结束了。
     * 换来的是一条更重要的保证：**只有真的为某个词发过请求，才可能出现
     * 可点的「新词」**，翻缓存永远不会凭空多出模型请求。
     */
    private val suggestionsByTerm = HashMap<String, Set<String>>()

    /** [suggestionsByTerm] 里当前这个概念那一份。Compose 状态，界面读它重组 */
    var suggestions by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 当前正在看的概念。null 表示停在概念列表 */
    val currentFrame: ConceptFrame? get() = stack.current

    val stackSize: Int get() = stack.size

    /** 当前这张图。为 null 表示这次启动还没有可分析的内容，首页要显示引导 */
    val imageFile: File? get() = image

    init {
        // 启动时把词表读进来。放在这里而不是等界面第一次渲染：
        // 读它要碰数据库，越早开始越好，反正此时用户还在看首页
        refreshMatcher()
    }

    // ------------------------------------------------------------------ 第一问

    /**
     * 换一张图。[autoScan] 为 true 时立刻发第一问 —— 用户刚框完，本来就等着看结果；
     * 为 false 时只是把它摆出来（打开 App 看到上次那张的情况），不花那次请求的钱。
     *
     * 跳转栈跟着清空：换了一张图，之前那条阅读线索就断了。
     * **知识库不清** —— 那是用户攒下来的东西，跨图跨书都该留着。
     *
     * 图变了还要通知 [ChatSession] 作废那场对话（见那里的注释）：对话讲的是
     * 上一张图，留着它只会让用户对着新图看到一段答非所问的内容。
     * **推给它而不是让它自己判断** —— "当前是哪张图"的唯一出处在这里，
     * 那边再存一份迟早会对不上。
     */
    fun load(path: String, autoScan: Boolean) {
        scanJob?.cancel()
        termJob?.cancel()
        refineJob?.cancel()
        concepts = emptyList()
        stack.clear()
        syncTrail()
        refining = false
        lastRefine = null
        // 换图了，当前没有在看任何概念，延伸概念自然也空。
        // **不清 suggestionsByTerm** —— 那些是关于概念本身的，
        // 换本书再遇到同一个词，那份建议仍然成立
        suggestions = emptySet()
        stage = AnalysisStage.Idle
        image = File(path)
        ChatSession.shared.onImageChanged(path)
        if (autoScan) scan()
    }

    /** 第一问：这张图里有哪些可能看不懂的概念 */
    fun scan() {
        val target = image ?: return
        scanJob?.cancel()
        scanJob = scope.launch {
            stage = AnalysisStage.Scanning
            stage = try {
                concepts = client.listConcepts(target)
                AnalysisStage.ConceptList
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AnalysisStage.ScanFailed(t.toUserMessage())
            }
        }
    }

    // ------------------------------------------------------------------ 打开一个概念

    /**
     * 打开一个概念：压栈并显示它。
     *
     * [rawTerm] 会先归一化 —— 从截图里抄来的词可能带着引号、句号。
     * 归一化之后为空的话直接忽略：那种情况点下去什么也不该发生，
     * 而不是压一个空名字进栈。
     *
     * [context] 是包含这个链接的那段解释正文，供「重新解释」用。
     * 从概念列表直接点进来时为 null。
     */
    fun openConcept(rawTerm: String, context: String? = null) {
        val term = TermNormalizer.normalize(rawTerm)
        if (term.isEmpty()) return

        stack.push(ConceptFrame(term, context?.takeIf { it.isNotBlank() }))
        syncTrail()
        lastRefine = null
        stage = AnalysisStage.Viewing
        showTerm(term)
    }

    /**
     * 显示某个概念。
     *
     * **先查库**：库里已经有就一次请求都不发。这是整个知识库存在的理由，
     * 也是它唯一能被用户感知到的地方 —— 快、而且是瞬时的。
     */
    private fun showTerm(term: String) {
        termJob?.cancel()
        termContent = TermContent.Loading

        // 换概念了，延伸概念跟着换。**缓存命中也查一查这份表** ——
        // 这个词如果刚在这一次会话里被问过，那份建议就还有效，
        // 不该因为第二次是从库里读的就消失（那会让同一屏忽有忽无）
        suggestions = suggestionsByTerm[term].orEmpty()

        termJob = scope.launch {
            // 读库失败就当"库里没有"，直接去问模型。
            // 不能让它抛出去 —— 那会崩掉整个 App，而用户的诉求只是看这个词的意思
            val stored = runCatching {
                withContext(Dispatchers.IO) { store.entries(term) }
            }.getOrElse { t ->
                Log.w(TAG, "读知识库失败，改为直接问模型：$term", t)
                emptyList()
            }
            if (stored.isNotEmpty()) {
                Log.i(TAG, "库命中：$term（${stored.size} 条，未发请求）")
                termContent = TermContent.Stored(stored)
                return@launch
            }
            requestExplanation(term)
        }
    }

    /**
     * 当前这个词该用什么当语境。
     *
     * 两种来源，取决于用户是怎么进到这个词的：
     *   · 从解释里的链接跳进来 —— 他正在读的是那条解释，用它的正文
     *   · 从概念列表点进来 —— 他正在读的是那张截图，用图
     *
     * 返回 (文字语境, 图)，**两者最多只有一个非空** ——
     * 同时给的话模型会不知道该以哪个为准，反而更容易选错义项。
     *
     * 语境的唯一用途是让模型「判断用户要的是哪个义项」。
     * 它不参与「怎么写」—— 解释内容始终要求脱离语境，见 [Prompts.EXPLAIN_SYSTEM]。
     */
    private fun contextForCurrentTerm(): Pair<String, File?> {
        val source = stack.current?.context
        return if (!source.isNullOrBlank()) source to null else "" to image
    }

    /**
     * 问模型要解释，边收边显示，收完切分入库。
     *
     * 入库在**切分之后**：模型回的是整篇 Markdown（`## 标签` 分节），
     * 要拆成一条条「标签 + 正文」才能按标签定位、替换、删除。
     */
    private suspend fun requestExplanation(term: String) {
        val (context, imageFile) = contextForCurrentTerm()
        val buffer = StringBuilder()
        try {
            client.explainStream(term, context, imageFile).collect { chunk ->
                buffer.append(chunk)
                // 显示用掐掉标记之后的部分：模型会在这段解释末尾附一行
                // 「相关概念」，那是给程序看的，不该在流式过程中露出来。
                // 见到半个标记也照样掐（explanationOnly 只看分隔符），
                // 所以不会闪一下再消失
                termContent = TermContent.Writing(SenseParser.explanationOnly(buffer.toString()))
            }

            val full = buffer.toString()
            val senses = SenseParser.parse(full)
            if (senses.isEmpty()) {
                // 一个字都没回来。不写库 —— 空条目会让「库命中」把它当成
                // 一份有效答案，之后永远显示空白
                termContent = TermContent.Failed("模型这次没有返回内容。重试一次通常就好。")
                return
            }

            // 入库失败也要把答案显示出来 —— 用户等了几秒，不能因为存不下就一个字不给。
            // 退化路径：把刚拿到的这几条直接当结果显示，只是下次还得重问一遍
            val written = runCatching {
                withContext(Dispatchers.IO) {
                    senses.forEach { store.put(term, it.tag, it.body) }
                    store.entries(term)
                }
            }.getOrElse { t ->
                Log.w(TAG, "入库失败，本次只显示不缓存：$term", t)
                senses.map { KnowledgeStore.Entry(term, it.tag, it.body) }
            }
            refreshMatcher()

            // 建议放在**回答成功之后**才记下：解析失败或空返回时不该留下
            // 一份「这个词有新概念可点」的记录，那会让一次失败的请求
            // 在界面上留下一个能点进新请求的入口
            val suggested = SenseParser.suggestedTerms(full).toSet() - term
            if (suggested.isNotEmpty()) {
                suggestionsByTerm[term] = suggested
                suggestions = suggested
                Log.i(TAG, "延伸概念：$term → ${suggested.joinToString("、")}")
            }

            Log.i(TAG, "解释入库：$term → ${senses.size} 条")
            termContent = TermContent.Stored(written)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 写了一半就断的**不入库**：半截解释比没有更糟 ——
            // 它会立刻变成「库命中」，之后每次打开这个词都看到一份残缺的东西
            Log.w(TAG, "解释失败：$term", t)
            termContent = TermContent.Failed(t.toUserMessage())
        }
    }

    // ------------------------------------------------------------------ 重新解释

    /**
     * 重新解释当前概念：把当前语境和库里已有的全部解释发给模型，
     * 让它决定补一条还是换一条。
     *
     * 这里的语境和首次解释用的是同一套（见 [contextForCurrentTerm]）：
     * 从链接跳进来时是那段来源文字，从概念列表进来时是那张截图。
     * 一致的语境来源很重要 —— 模型在两次请求里看到同一个「用户当前在读什么」，
     * 才能对「该补哪个义项」给出一致的判断。
     */
    fun refine() {
        val frame = stack.current ?: return
        if (refining) return
        if (refineJob?.isActive == true) return

        val existing = (termContent as? TermContent.Stored)?.entries.orEmpty()
        refining = true
        lastRefine = null

        refineJob = scope.launch {
            try {
                val (context, imageFile) = contextForCurrentTerm()
                val decision = client.refine(
                    term = frame.term,
                    context = context,
                    existing = existing.map { it.tag to it.body },
                    imageFile = imageFile
                )
                Log.i(TAG, "重新解释：${frame.term} → $decision")

                withContext(Dispatchers.IO) { applyRefine(frame.term, decision, existing) }
                refreshMatcher()
                lastRefine = decision
                // 重读一遍：applyRefine 已经写过库，这里拿到的是落库后的样子。
                // 不自己拼装列表 —— 让数据库说它现在有什么
                termContent = TermContent.Stored(
                    withContext(Dispatchers.IO) { store.entries(frame.term) }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "重新解释失败：${frame.term}", t)
                termContent = TermContent.Failed(t.toUserMessage())
            } finally {
                refining = false
            }
        }
    }

    /**
     * 把模型的判断落成一次写库。
     *
     * replace 要处理一个真实的坑：模型回给我们的 [RefineDecision.Replace.targetTag]
     * 是它从提示词里抄的，而提示词里的标签来自数据库 —— 中间只要有一个空格
     * 或者大小写的差异，直接用字符串去更新就会**一行都改不到**，
     * 而用户看到的是「点了重新解释，什么都没变」。
     * 所以先在已有的标签里做一次模糊匹配（忽略空白和大小写），
     * 找不到就退化成新增 —— 至少用户的动作产生了可见的结果。
     */
    private fun applyRefine(
        term: String,
        decision: RefineDecision,
        existing: List<KnowledgeStore.Entry>
    ) {
        when (decision) {
            is RefineDecision.Keep -> Unit

            is RefineDecision.Add ->
                store.put(term, TermNormalizer.normalize(decision.tag), decision.body)

            is RefineDecision.Replace -> {
                val target = existing.firstOrNull { SenseParser.sameTag(it.tag, decision.targetTag) }
                val tag = target?.tag ?: TermNormalizer.normalize(decision.targetTag)
                if (tag.isEmpty()) return
                store.put(term, tag, decision.body)
            }
        }
    }

    // ------------------------------------------------------------------ 删除

    /**
     * 删掉当前概念的某一条解释。
     *
     * 删空之后不自动重新问模型 —— 那是用户的选择，界面会显示「没有解释了」
     * 并给一个重新问的入口。自动补一条等于把用户刚删掉的东西又拿回来。
     */
    fun deleteEntry(term: String, tag: String) {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    store.deleteTag(term, tag)
                    store.entries(term)
                }
            }
            result.onSuccess { left ->
                refreshMatcher()
                lastRefine = null
                termContent = TermContent.Stored(left)
            }.onFailure { t ->
                // 失败时**不动界面**：保持原样，用户再点一次就行。
                // 这时候如果摆一个"已删除"的假象，反而会让他以为删掉了
                Log.w(TAG, "删除失败：$term / $tag", t)
            }
        }
    }

    /** 库里没有解释时（或刚把它删空），重新问一次模型 */
    fun retryExplanation() {
        val term = stack.current?.term ?: return
        termJob?.cancel()
        termContent = TermContent.Loading
        termJob = scope.launch { requestExplanation(term) }
    }

    // ------------------------------------------------------------------ 导航

    /**
     * 返回上一层。已经在栈底时回到概念列表。
     *
     * 取消正在写的那条流：用户已经离开了这个词，让它写完也只是白烧 token ——
     * 而且它写完之后会去改 [termContent]，把用户已经切过去的那个词的界面污染掉。
     * （早先这里为了"让它写完进缓存"故意不取消，那是在内存缓存时代的选择；
     *  现在解释一入库就永久有效，没必要为一个用户已经不看的东西付钱。）
     */
    fun goBack() {
        termJob?.cancel()
        refineJob?.cancel()
        refining = false
        lastRefine = null

        if (!stack.pop() || stack.isEmpty) {
            stack.clear()
            syncTrail()
            stage = AnalysisStage.ConceptList
            return
        }

        syncTrail()
        val frame = stack.current ?: return
        stage = AnalysisStage.Viewing
        showTerm(frame.term)
    }

    /**
     * 直接回到概念列表，不管栈有多深。
     *
     * 深栈时（比如已经钻了五六层）逐层返回太累，需要一个出口。
     * 它和 [goBack] 的区别只是「弹一次」和「弹到底」。
     */
    fun backToList() {
        termJob?.cancel()
        refineJob?.cancel()
        refining = false
        lastRefine = null
        stack.clear()
        syncTrail()
        stage = AnalysisStage.ConceptList
    }

    // ------------------------------------------------------------------ 内部

    private fun syncTrail() {
        trail = stack.trail
    }

    /**
     * 库里被别处改动过（知识库页删了解释），重建词表。
     *
     * 开这个口子是为了让「哪些词能点」这件事保持单一真相：知识库页和主流程
     * 用同一个 matcher，那边删完一条就喊这里一声，这边立刻反映到链接上。
     * 不喊的话，主页面里刚被删掉的概念仍然带链接，点进去是空的。
     */
    fun refreshLibrary() = refreshMatcher()

    /**
     * 重建词表匹配器。
     *
     * 读全表是 IO，所以整段在 IO 线程上做；赋值回主线程，
     * 免得 Compose 在别处读到一份半成品。
     *
     * **失败时退化成空词表，绝不往上抛。** 这一条是拿一次真实闪退换来的：
     * 数据库读异常原本会一路冒到协程外面，在 Android 上直接崩掉 App ——
     * 而用户看到的只是"这个应用打不开了"，完全想不到是知识库的问题。
     * 知识库是增强（有链接、有缓存），它坏了应该退化成"没有链接"，
     * 而不是让整个应用变成砖。
     */
    private fun refreshMatcher() {
        scope.launch {
            val terms = runCatching {
                withContext(Dispatchers.IO) { store.allTerms() }
            }.getOrElse { t ->
                Log.w(TAG, "读词表失败，本次退化成没有链接", t)
                emptySet()
            }
            matcher = ConceptMatcher(terms)
            Log.i(TAG, "词表已更新：${terms.size} 个概念")
        }
    }

    companion object {
        private const val TAG = "Analysis"

        /**
         * 全进程一个。见类注释 —— 这份状态比任何一个 Activity 都活得久。
         */
        val shared: AnalysisController by lazy { AnalysisController() }
    }
}

/**
 * 把异常翻成用户能看懂、并且知道下一步做什么的话。
 *
 * 这一层非做不可：网络异常的原话是 "Failed to connect to /198.18.0.57:443"，
 * 对使用者毫无意义，但「连不上 api.deepseek.com，检查手机网络」他立刻能行动。
 *
 * 和 [AnalysisController] 同包而不是丢进 util：它唯一的调用方就是这个类，
 * 翻的也全是这个类发出的请求会遇到的异常。跟着调用方走，别为它单开一个包。
 */
internal fun Throwable.toUserMessage(): String = when (this) {
    is DeepSeekException -> message ?: "未知错误"

    is UnknownHostException ->
        "连不上 api.deepseek.com。\n\n检查手机网络。如果装了代理类 App，它可能把域名解析到了别的地址。"

    is SocketTimeoutException ->
        "请求超时。\n\n网络慢，或者模型这会儿比较忙，重试一次通常就好。"

    is SSLException ->
        "TLS 握手失败。\n\n手机上如果开着 VPN 或代理类 App，先关掉再试。"

    else -> "${this::class.simpleName}: ${message ?: "未知错误"}"
}
