package com.example.explaindot.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import com.example.explaindot.ai.AiConfig
import com.example.explaindot.ai.AiUserSettings
import com.example.explaindot.ai.ChatProfile
import com.example.explaindot.capture.AccessibilityShotService
import com.example.explaindot.knowledge.KnowledgeBase
import com.example.explaindot.knowledge.KnowledgeStore
import com.example.explaindot.permission.OverlayPermission
import com.example.explaindot.sync.KnowledgeSync
import com.example.explaindot.sync.SyncSettings
import com.example.explaindot.sync.SyncTargets
import com.example.explaindot.sync.toUserMessage
import com.example.explaindot.ui.theme.ExplainDotTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页的宿主。所有「去授权 / 开关」的动作都在这里，首页那边一个都不留。
 *
 * 这里做的事情比看上去少：悬浮窗、无障碍、自启动这些都是系统权限，
 * 应用不能自己开关，只能把用户送到对应的系统页面 —— 所以除 AI 那三项之外，
 * 这个 Activity 一个系统 API 都不用调。
 */
class SettingsActivity : ComponentActivity() {

    // 只能是占位值：成员初始化跑在构造函数里，那时读 Context 会 NPE。
    // 真实状态在 onCreate 里读一次补上，见 AppStatus.NONE 的注释
    private var status by mutableStateOf(AppStatus.NONE)

    /** 当前打开的编辑目标。都是"改完立刻生效"的写入动作，所以用对话框 */
    private var editor by mutableStateOf<Editor?>(null)

    /**
     * 本地知识库的用量。null 表示还没读出来。
     *
     * 异步读：它要查数据库（三条 PRAGMA/COUNT），放在 onResume 里同步做
     * 会在切页时卡一下。读不出来就一直 null，界面不显示那张卡 ——
     * 比显示一个假的「0 个概念」诚实。
     */
    private var knowledge by mutableStateOf<KnowledgeStore.Stats?>(null)

    /** 读知识库统计用。随 Activity 一起结束，不会漏 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 用户在对话框里填过的值，给对话框预填。**必须在 Activity 里当 Compose 状态拿着**。
     *
     * 它存在 SharedPreferences 里，而那不是 Compose 状态 —— 直接写进去的话，
     * 卡片上那句「东西存在哪儿」会一直显示旧值，直到下次进出这个页面。
     * 所以这里存一份，保存后刷新它。
     *
     * ## 为什么要 `neverEqualPolicy`
     *
     * 保存之后这个 map 的内容**很可能一个字都没变**（用户没动任何输入框），
     * 但适配器那边**不归用户填**的值（账号名、分支）变了，卡片必须重读配置。
     * 而 `mutableStateOf` 默认按**结构相等**判断变化 —— 内容一样就不重组，
     * 于是卡片卡在旧状态上。
     *
     * 真机上就是这么踩到的：保存成功、`github_branch=main` 也确实写进了配置文件，
     * 对话框正常关闭，卡片却还挂着「还没配置」。**只有真机能发现这个问题** ——
     * 单测里没有 Compose 的重组，看得见配置写没写对、看不见界面有没有跟上。
     */
    private var syncValues by mutableStateOf(syncValuesNow(), neverEqualPolicy())

    /**
     * 这一页能打开的全部对话框。
     *
     * 前三个是 AI 服务的运行期参数，后三个属于「对话理解」——
     * 后三项存的是用户自己写的提示词素材，不是服务配置，所以这个枚举
     * 不再叫 AiEditor。名字准不准在这里有实际影响：将来加一项时，
     * 会有人照着枚举名去找该往哪儿放。
     */
    private enum class Editor {
        Key, Model, Thinking,
        ChatThinking, ChatBackground, ChatSkill
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refresh()
        refreshKnowledge()

        setContent {
            ExplainDotTheme {
                SettingsScreen(
                    status = status,
                    knowledge = knowledge,
                    onBack = { finish() },
                    onEditAiKey = { editor = Editor.Key },
                    onClearAiKey = {
                        AiUserSettings.apiKey = ""
                        closeEditor()
                    },
                    onEditAiModel = { editor = Editor.Model },
                    onEditAiThinking = { editor = Editor.Thinking },
                    onEditChatThinking = { editor = Editor.ChatThinking },
                    onEditChatBackground = { editor = Editor.ChatBackground },
                    onEditChatSkill = { editor = Editor.ChatSkill },
                    onGrantOverlay = {
                        runCatching { startActivity(OverlayPermission.settingsIntent(this)) }
                    },
                    onToggleDot = {
                        toggleDot(this, status.dotRunning)
                        // 服务是异步起来的，而 Activity 一直停在前台、onResume 不会再触发。
                        // 先乐观翻一下让开关立刻响应，300ms 后回读真实状态纠正 ——
                        // 万一服务因为权限被撤而自杀，开关会自己弹回去
                        status = status.copy(dotRunning = !status.dotRunning)
                        window.decorView.postDelayed({ refresh() }, 300L)
                    },
                    onOpenA11ySettings = { AccessibilityShotService.openSystemSettings(this) },
                    sync = SyncUiState.read(syncValues),
                    onConfigureSync = { entered ->
                        try {
                            val target = SyncTargets.primary

                            // 先补全（会联网、会失败），**成功了才写**。失败就什么都不动，
                            // 老配置原样留着 —— 写一半的状态比不写更难收拾：
                            // 新令牌配着老账号名，用户根本看不出错在哪
                            val derived = target.resolve(entered)

                            // 键取自适配器自己声明的 fields，所以将来加平台这里不用动
                            target.fields.forEach { field ->
                                SyncSettings.set(field.key, entered[field.key].orEmpty())
                            }
                            // 派生值后写：它可能顺带纠正用户填的写法
                            // （比如把「组织名/仓库名」存成裸的仓库名）
                            derived.forEach { (key, value) -> SyncSettings.set(key, value) }

                            syncValues = syncValuesNow()
                            // 上一次的结果（比如"令牌无效"）在改完配置之后就不该还挂在那儿
                            KnowledgeSync.clearResult()
                            null
                        } catch (e: CancellationException) {
                            // 协程被取消不是失败，别把它变成对话框里的一句红字
                            throw e
                        } catch (t: Throwable) {
                            // 交给对话框显示。这里不关框、也不写任何东西
                            t.toUserMessage("保存失败")
                        }
                    },
                    onSyncPush = {
                        scope.launch { KnowledgeSync.push() }
                    },
                    onSyncPull = {
                        scope.launch {
                            KnowledgeSync.pull()
                            // 拉取会把本地整表换掉，条数变了，上面那张卡要跟着更新
                            refreshKnowledge()
                        }
                    }
                )

                // 对话框挂在页面之外：它们是独立窗口，让设置页保持「纯页面」
                when (editor) {
                    Editor.Key -> AiKeyDialog(
                        // 带上当前值：换 key 的时候多半是从旧的那个改几个字符，
                        // 从空白开始等于让人重新去别处复制一遍
                        initialKey = AiUserSettings.apiKey,
                        configured = status.aiConfigured,
                        onDismiss = { editor = null },
                        onSave = { AiUserSettings.apiKey = it; closeEditor() },
                        onClear = { AiUserSettings.apiKey = ""; closeEditor() }
                    )

                    Editor.Model -> AiModelDialog(
                        // 预填当前生效的值而不是"用户改没改过" ——
                        // 想微调时不用先去别处把默认名抄一遍
                        current = status.aiModel,
                        defaultModel = AiConfig.defaultModel,
                        onDismiss = { editor = null },
                        onSave = { AiUserSettings.model = it; closeEditor() }
                    )

                    // 两个思考强度共用一个对话框，靠 target 区分标题、档位说明和底部提示
                    Editor.Thinking -> AiThinkingDialog(
                        target = ThinkingTarget.Explain,
                        current = status.aiThinking,
                        onDismiss = { editor = null },
                        onPick = { AiUserSettings.thinking = it; closeEditor() }
                    )

                    Editor.ChatThinking -> AiThinkingDialog(
                        target = ThinkingTarget.Chat,
                        current = status.aiChatThinking,
                        onDismiss = { editor = null },
                        onPick = { AiUserSettings.chatThinking = it; closeEditor() }
                    )

                    Editor.ChatBackground -> ChatTextDialog(
                        title = "知识背景",
                        description = "说说你是做什么的、熟悉哪些领域。模型据此判断该讲到多深、" +
                            "哪些词不必再解释 —— 同一个问题，问的人懂多少，该给的答案是两种。",
                        placeholder = "例如：我是做后端开发的，熟悉 Java 和数据库，" +
                            "但没接触过机器学习和经济学。",
                        initial = ChatProfile.background,
                        onDismiss = { editor = null },
                        onSave = { ChatProfile.background = it; closeEditor() }
                    )

                    Editor.ChatSkill -> ChatTextDialog(
                        title = "对话理解技能",
                        description = "你希望它怎么跟你说话：先给结论还是先讲推导、要不要举例、" +
                            "讲多细、不要做什么。写得越具体越管用 —— " +
                            "「讲得通俗点」不如「每个术语第一次出现时配一个生活里的例子」。",
                        placeholder = "例如：先给结论再讲原因；专业词第一次出现时用一句话解释；" +
                            "不要写「希望对你有帮助」这类结尾。",
                        initial = ChatProfile.skill,
                        onDismiss = { editor = null },
                        onSave = { ChatProfile.skill = it; closeEditor() }
                    )

                    null -> Unit
                }
            }
        }
    }

    /** 关掉对话框并回读状态：卡片上的值要跟着变 */
    private fun closeEditor() {
        editor = null
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页（无障碍 / 悬浮窗）回来，状态多半已经变了
        refresh()
        // 用户可能刚在解释页删掉几条解释，回来时数字要跟着变
        refreshKnowledge()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun refresh() {
        status = AppStatus.read(this)
    }

    /** 从适配器声明的字段里把用户填过的值读成一份快照 */
    private fun syncValuesNow(): Map<String, String> =
        SyncTargets.primary.fields.associate { it.key to SyncSettings.get(it.key) }

    private fun refreshKnowledge() {
        scope.launch {
            // 读失败就保持 null，界面不显示那张卡。不往上抛 ——
            // 一个统计数字读不出来，不值得把设置页打崩
            knowledge = runCatching {
                withContext(Dispatchers.IO) { KnowledgeBase.store.stats() }
            }.getOrNull()
        }
    }
}
