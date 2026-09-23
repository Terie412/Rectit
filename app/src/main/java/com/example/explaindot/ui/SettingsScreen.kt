package com.example.explaindot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.explaindot.ai.ChatProfile
import com.example.explaindot.knowledge.KnowledgeStore
import com.example.explaindot.ui.theme.ExplainDotTheme

/**
 * 设置页 —— 二级页面。
 *
 * 这些内容原本是首页的正文：四张状态卡、怎么用、国产 ROM 的保活说明。
 * 它们的问题不是不重要，而是「看完一次就不再需要」，于是每一屏都在
 * 用同一批文字盖住真正每天要看的东西（分析结果）。整体搬到这一页。
 *
 * 页面不做本地状态，所有开关的真相都在系统里（悬浮窗权限、前台服务进程），
 * Activity 每次 onResume 重新读一遍传进来。用户去系统设置授权再切回来时，
 * 显示的一定是真实状态，不会出现「显示已授权但其实没授权」这种自欺欺人。
 */
@Composable
fun SettingsScreen(
    status: AppStatus,
    onBack: () -> Unit,
    onEditAiKey: () -> Unit,
    onClearAiKey: () -> Unit,
    onEditAiModel: () -> Unit,
    onEditAiThinking: () -> Unit,
    /**
     * 「对话理解」那三项。给了默认值，只为让 @Preview 不用为了一个新功能补一串空 lambda。
     *
     * 和上面那三项（key / 模型 / 解释思考强度）分开，不只是因为参数多：
     * 上面那些决定"能不能用、多快"，这三项是"聊起来是什么样"。
     */
    onEditChatThinking: () -> Unit = {},
    onEditChatBackground: () -> Unit = {},
    onEditChatSkill: () -> Unit = {},
    onGrantOverlay: () -> Unit,
    onToggleDot: () -> Unit,
    onOpenA11ySettings: () -> Unit,
    /**
     * 本地知识库的用量。
     *
     * **这张页面上只剩一处用它**：同步的确认框要说清「本地现有的 33 个概念 /
     * 34 条解释会被删掉」—— 那两个动作都是整份覆盖、不留备份，那句话是它们
     * 唯一的保险，所以它必须留在这儿。
     *
     * 而这个数字本身的展示属于知识库页（见 [KnowledgeScreen]）。null 表示还没
     * 读出来（或者读失败了），那时确认框退回用「内容」这个泛称。
     */
    knowledge: KnowledgeStore.Stats? = null,
    /**
     * 远程同步的状态。null 表示不显示这张卡（@Preview 不传）。
     *
     * 这几个参数有默认值，是为了让那个 @Preview 不用为了一个新功能
     * 补上一整套空 lambda。
     */
    sync: SyncUiState? = null,
    /** 保存同步配置。挂起是因为它要联网核对，返回 null 表示成功，否则是失败原因 */
    onConfigureSync: suspend (Map<String, String>) -> String? = { null },
    onSyncPush: () -> Unit = {},
    onSyncPull: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // 标题在左、动作在右，跟首页的 Header 完全同构 ——
            // 两页的右上角是同一个物理位置，一进一出都落在指头刚点过的地方。
            // 内边距也跟着首页走（start 20 / end 8）：右边留窄一点是因为
            // TextButton 自带内边距，写 20 会让它视觉上比左边空一截。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                RowAction(text = "返回", onClick = onBack)
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {

                Spacer(Modifier.height(8.dp))

                // 放在最前面：这一项没配好，后面几步全做完也是白做。
                // 也是这一页唯一能直接编辑东西的卡片，其余几张都只是"跳去系统设置"
                AiCard(
                    configured = status.aiConfigured,
                    model = status.aiModel,
                    maskedKey = status.aiMaskedKey,
                    thinking = status.aiThinking,
                    onEditKey = onEditAiKey,
                    onEditModel = onEditAiModel,
                    onEditThinking = onEditAiThinking
                )

                Spacer(Modifier.height(12.dp))

                // 紧跟 AI 服务卡：那两张是同一件事的两半 —— 上面那张让请求发得出去，
                // 这张决定对话理解聊成什么样。放在权限那几张之前，
                // 是因为权限是一次性配置，不需要反复回来
                ChatProfileCard(
                    thinking = status.aiChatThinking,
                    backgroundPreview = status.chatBackgroundPreview,
                    skillPreview = status.chatSkillPreview,
                    onEditThinking = onEditChatThinking,
                    onEditBackground = onEditChatBackground,
                    onEditSkill = onEditChatSkill
                )

                Spacer(Modifier.height(12.dp))

                StatusCard(
                    title = "悬浮窗权限",
                    description = if (status.overlayGranted) {
                        "已授权，圆点可以显示在其他应用之上。"
                    } else {
                        "没有这个权限，圆点画不出来。它会跳到系统设置页，需要你手动打开开关。"
                    },
                    granted = status.overlayGranted,
                    actionLabel = if (status.overlayGranted) null else "去授权",
                    onAction = onGrantOverlay
                )

                Spacer(Modifier.height(12.dp))

                StatusCard(
                    title = "圆点状态",
                    description = if (status.dotRunning) {
                        "圆点正在运行。回到桌面或切到任何应用，它都在。"
                    } else {
                        "圆点没在运行。开启后会常驻一个通知，可以从通知里关掉。"
                    },
                    granted = status.dotRunning,
                    actionLabel = if (status.dotRunning) "关闭圆点" else "开启圆点",
                    onAction = onToggleDot,
                    enabled = status.overlayGranted
                )

                Spacer(Modifier.height(12.dp))

                // 唯一的取图通路，也是这一页唯一必开的一项。配好之后截屏这件事就结束了
                //
                // 说明只留一句「框选依赖它」。早先这里解释了一大段它跟电源键截屏同源、
                // 微信里那些变糊的内容能拿到原图 —— 那是**实现细节**，用户不需要知道
                // 我们怎么截的，只需要知道「没有它框选就没用」。
                StatusCard(
                    title = "屏幕取图",
                    description = if (status.a11yCapture) {
                        "框选解释依赖它，已就绪。"
                    } else {
                        "框选解释依赖它。没有它，长按圆点也截不到图。"
                    },
                    granted = status.a11yCapture,
                    actionLabel = if (status.a11yCapture) null else "去开启",
                    onAction = onOpenA11ySettings
                )

                // 已知情况只保留「强杀之后授权会被摘掉」这条。
                //
                // 另一条（凭什么要这个权限、我们只用它截你框住的那块）删掉了 ——
                // 那是对权限的辩解，而上面那句已经说清了它做什么。
                // 这一条不同：它是**真会踩到的，而且现象是「莫名就不能用了」**，
                // 不说清楚只能靠猜。措辞按实测来：强杀进程（应用更新、手动强行停止、
                // 清理工具杀后台）之后系统会摘掉这条授权 —— 已经在本机复现过两次。
                if (status.a11yCapture) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "一个已知情况：应用被系统强制停止后，这条授权会被摘掉 —— " +
                            "装新版、手动「强行停止」、或者清理工具杀后台都会触发。" +
                            "如果哪天框选突然没反应了，先回这里看一眼是不是变回了「未开启」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!status.overlayGranted) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "先授权，才能开启圆点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(28.dp))
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))

                // 远程同步。
                //
                // 「本地攒了多少」那张卡不在这里了 —— 它搬去了知识库页
                // （见 [KnowledgeScreen] 的统计行）。那不是一项配置，是知识库自己的
                // 数据预览，摆在设置页里等于把一个页面的内容寄存在另一个页面；
                // 而设置页该回答的是「怎么让它工作」，不是「里面有什么」。
                if (sync != null) {
                    SyncCard(
                        sync = sync,
                        local = knowledge,
                        onConfigure = onConfigureSync,
                        onPush = onSyncPush,
                        onPull = onSyncPull
                    )
                    Spacer(Modifier.height(28.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(20.dp))
                }

                Text(
                    text = "怎么用",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(12.dp))
                listOf(
                    "按住拖动 —— 把圆点挪到不挡字的地方",
                    "长按 —— 进度环绕满一圈，震一下，进入框选",
                    "接着滑动 —— 划出要解释的范围，松手后还能调",
                    "点 ✓ —— 自动挑出这一块里看不懂的概念",
                    "点其中一个 —— 它再针对原文展开解释"
                ).forEach { line ->
                    Row(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text("·", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(10.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))

                Text(
                    text = "如果圆点会莫名消失",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "国产系统（小米、华为、OPPO、vivo）默认会杀掉后台的前台服务。" +
                        "需要把这个应用加进「自启动白名单」，并把省电策略改成「无限制」，" +
                        "圆点才能长期活着。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "小米还要额外开一项：「后台弹出界面」。不给的话，框选之后的结果页" +
                        "会弹不出来（点了像没反应）。路径：应用管理 → 权限管理 → 后台弹出界面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "这一页下面这些内容看一次就够了，平时不用回来 —— " +
                        "真正会反复用到的是最上面那张 AI 服务卡。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/**
 * AI 服务卡。整页唯一能直接改东西的卡片之一。
 *
 * 它跟其余几张卡的区别值得强调：别的卡点一下是"跳到系统设置去授权"，
 * 这张卡就在这一页里把事情做完 —— 这三项都是 App 自己管的，不需要出去。
 *
 * 三项都是运行期可改，理由各不相同：
 *   - Key：只有用户有，编译期根本猜不到
 *   - 模型名：官方会下线旧名字，硬编码在包里意味着每次都得重新发一版
 *   - 思考强度：拿时间换准确度的个人取舍，取决于当下读的是什么书
 *
 * 这里那一项标的是**解释**思考强度，和下一张卡的对话思考强度是两个独立的开关 ——
 * 标签上不写清"解释"的话，用户在对话那边调完再回来，会以为自己调的是同一个。
 */
@Composable
private fun AiCard(
    configured: Boolean,
    model: String,
    maskedKey: String,
    thinking: String,
    onEditKey: () -> Unit,
    onEditModel: () -> Unit,
    onEditThinking: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (configured) "●" else "○",
                    color = if (configured) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "AI 服务",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            SettingRow(
                label = "API Key",
                value = if (configured) maskedKey else "未填写，框选后用不了",
                valueIsWarning = !configured,
                actions = listOf(
                    (if (configured) "更换" else "填写") to onEditKey
                )
            )

            HorizontalDivider()

            SettingRow(
                label = "模型",
                value = model,
                actions = listOf("修改" to onEditModel)
            )

            HorizontalDivider()

            SettingRow(
                label = "解释思考强度",
                value = thinkingLabel(thinking),
                actions = listOf("修改" to onEditThinking)
            )
        }
    }
}

/**
 * 「对话理解」卡：它自己的思考强度，加上用户手写的两段素材。
 *
 * ## 为什么单独一张卡，而不是并进 AI 服务卡
 *
 * AI 服务卡回答的是"请求能不能发出去、用什么模型发"——那三项不配好，整个 App
 * 都不能用。这三项不同：它们全都只作用于首页那个「对话理解」按钮，
 * 不填也能用，只是聊得糙一点。混在一张卡里的话，"哪些是必须配的"
 * 这件事就看不出来了。
 *
 * ## 两段素材为什么是"填一次就不用管"的东西
 *
 * 它们不是开关，是资料。用户在第一次用对话理解、发现它讲得太浅或太啰嗦时
 * 回来写一次，之后基本不动 —— 所以卡片上只显示一行预览，
 * 编辑放在对话框里（那两段动辄几百字，摊在卡片上会把这一页撑得很长）。
 *
 * 说明里那句「不影响框选解释」是要紧的：用户很可能会在这里写"我是零基础，
 * 请讲得浅白些"，然后奇怪为什么框选出来的解释还是那么绕。
 * 这是两个入口、两套 prompt，各自读各自的设置。
 */
@Composable
private fun ChatProfileCard(
    thinking: String,
    backgroundPreview: String,
    skillPreview: String,
    onEditThinking: () -> Unit,
    onEditBackground: () -> Unit,
    onEditSkill: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 这张卡没有"配好了没有"这回事，所以圆点按"填过没有"来点 ——
                    // 两段都空着时是空心，至少给一点反馈
                    text = if (backgroundPreview.isNotEmpty() || skillPreview.isNotEmpty()) "●" else "○",
                    color = if (backgroundPreview.isNotEmpty() || skillPreview.isNotEmpty()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "对话理解",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            SettingRow(
                label = "对话思考强度",
                value = thinkingLabel(thinking),
                actions = listOf("修改" to onEditThinking)
            )

            HorizontalDivider()

            SettingRow(
                label = "知识背景",
                value = backgroundPreview.ifEmpty { "未填写" },
                valueIsWarning = backgroundPreview.isEmpty(),
                actions = listOf((if (backgroundPreview.isEmpty()) "填写" else "修改") to onEditBackground)
            )

            HorizontalDivider()

            SettingRow(
                label = "对话理解技能",
                value = skillPreview.ifEmpty { "未填写" },
                valueIsWarning = skillPreview.isEmpty(),
                actions = listOf((if (skillPreview.isEmpty()) "填写" else "修改") to onEditSkill)
            )

            Spacer(Modifier.height(6.dp))
            Text(
                text = "只作用于首页的「对话理解」，不影响框选解释。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 设置卡里的一行：左边标签、右边动作，值另起一行放在下面。
 *
 * 值单独一行不是为了好看 —— 挤在一行里，"API Key sk-0aab…f968 [更换]"
 * 在窄屏上必然换行，而换行位置会随值的长短乱跳。
 *
 * `internal` 而不是 private：远程同步那张卡也在用同一行（见 [SyncCard]），
 * 两处各写一遍的话，行高和内边距迟早会分家。
 */
@Composable
internal fun SettingRow(
    label: String,
    value: String,
    actions: List<Pair<String, () -> Unit>>,
    valueIsWarning: Boolean = false
) {    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            actions.forEach { (text, action) ->
                TextButton(onClick = action) { Text(text) }
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (valueIsWarning) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun thinkingLabel(mode: String): String = when (mode) {
    "off" -> "off · 关闭思考，最快"
    "low" -> "low · 轻度思考"
    "high" -> "high · 官方默认强度"
    "max" -> "max · 最强，最慢"
    else -> mode
}

/**
 * 填 / 换 / 清 API Key 的对话框。
 *
 * 用一个对话框而不是内嵌输入框：这是一次性的写入动作，填完就该收起来。
 * 嵌在卡片里会让那张卡常年占着一行输入框和半个屏幕的键盘。
 *
 * 默认遮住字符 —— 这个页面很可能会被人从旁边看到，而 key 抄走就能直接花钱。
 * 留一个「显示」开关，是因为粘贴完总要核对一下有没有多带空格。
 *
 * 「清除」放在这个对话框里而不是卡片上：它跟"换一个 key"是同一件事的两种做法，
 * 摆在外面只会让卡片多一个随时可能被误点的按钮。
 */
@Composable
fun AiKeyDialog(
    initialKey: String,
    configured: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit
) {
    var text by remember { mutableStateOf(initialKey) }
    var revealed by remember { mutableStateOf(false) }
    val trimmed = text.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DeepSeek API Key") },
        text = {
            Column {
                Text(
                    text = "去 platform.deepseek.com 的 API Keys 页面建一个，复制过来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("sk-…") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (revealed) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { revealed = !revealed }) {
                            Text(if (revealed) "隐藏" else "显示")
                        }
                    }
                )

                // 只提示、不拦截：中转服务发的 key 可能不长这样，
                // 拿格式把人挡在外面比放一个错字进来更烦人
                if (trimmed.isNotEmpty() && !trimmed.startsWith("sk-")) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "DeepSeek 的 Key 一般以 sk- 开头，确认一下没粘错。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "保存后立刻生效，不用重新编译。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (configured) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onClear,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                    ) {
                        Text("清除这台手机上的 Key")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(trimmed) }, enabled = trimmed.isNotEmpty()) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 改模型名的对话框。
 *
 * 用自由输入而不是下拉选择：模型 ID 是会变的东西 —— 官方刚下线过一个视觉模型，
 * 写死的下拉列表迟早变成一份过期的清单，而"换个名字"本来就不该需要改代码。
 * 默认值当占位符显示出来，用户照着抄就行。
 *
 * 唯一的坑要写在明面上：图片输入依赖多模态模型，换成一个纯文本的，
 * 框选之后模型什么也看不出来 —— 那种失败很难自己诊断出来。
 */
@Composable
fun AiModelDialog(
    current: String,
    defaultModel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(current) }
    val trimmed = text.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型名") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(defaultModel) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "必须填支持图片输入的模型。换成一个纯文本模型的话，" +
                        "框选之后什么概念都识别不出来 —— 而且报错不会告诉你是这个原因。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "留空就回到默认的 $defaultModel。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (trimmed != defaultModel) {
                    TextButton(
                        onClick = { onSave(defaultModel) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                    ) {
                        Text("恢复默认")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(trimmed) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 思考强度是哪一路的。
 *
 * 做成枚举而不是让调用方传标题和说明：两路的档位集合相同、但**每一档的代价不一样**
 * （同一个 low，在解释里是"多等三五秒"，在对话里可能意味着"这一轮追问要等八秒"）。
 * 把文案留在这个文件里，改的时候两路能一起看到。
 */
enum class ThinkingTarget { Explain, Chat }

/**
 * 选思考强度的对话框。
 *
 * 四档是官方语义里的封闭集合，所以这里不做输入框，直接列出来选。
 * 点一下立即生效并关闭 —— 这是个随时可以改回来的开关，
 * 多加一步"确定"只是让人多确认一次自己刚点的东西。
 *
 * 每档都标了大致代价，因为这个选择本质上是在拿时间换准确度：
 * 关掉一两秒出结果，开着可能等到九秒，而多数词并不需要那几秒。
 *
 * 底部那句提示两路不同，因为**副作用不同**：换解释档位会让已经解释过的概念
 * 下次重新问一遍；对话没有存下来的东西，换档只影响之后的问题。不写清这一点的话，
 * 用户在对话那边改完会以为旧回答也要重来（或者反过来，在解释那边改完
 * 发现旧解释没变，以为没生效）。
 */
@Composable
fun AiThinkingDialog(
    target: ThinkingTarget,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val title: String
    val choices: List<Pair<String, String>>
    val note: String

    when (target) {
        ThinkingTarget.Explain -> {
            title = "解释思考强度"
            choices = EXPLAIN_THINKING_CHOICES
            note = "换档位之后，之前解释过的概念会重新问一次模型 —— " +
                "不同档位给的是不同的答案，不能拿旧的那份顶替。"
        }

        ThinkingTarget.Chat -> {
            title = "对话思考强度"
            choices = CHAT_THINKING_CHOICES
            note = "只影响之后的问题。对话不留档，已经聊过的那几轮不会重来。"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // 顺序和 AiUserSettings.THINKING_LEVELS 一致：由快到慢
                choices.forEach { (level, description) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(level) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = level == current, onClick = { onPick(level) })
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text(
                                text = level,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 解释那一路的档位说明。"查词"、"解释"这些词是照着它的用途写的 */
private val EXPLAIN_THINKING_CHOICES = listOf(
    "off" to "关闭思考 —— 一两秒出结果，查词够用",
    "low" to "轻度思考 —— 首字延迟多三五秒，解释更透一点",
    "high" to "官方默认强度 —— 四到九秒，等待时长不稳定",
    "max" to "最强 —— 最慢，只在特别绕的概念上值得"
)

/**
 * 对话那一路的。措辞不能照抄上面那份 —— 那边的"查词够用"到这里没有意义，
 * 而这里真正要权衡的是**追问接不接得住**：一轮答偏，后面几轮都在纠正它。
 */
private val CHAT_THINKING_CHOICES = listOf(
    "off" to "关闭思考 —— 最快，简单的问题够用；一追问就容易答偏",
    "low" to "轻度思考 —— 首字慢三五秒，一般的追问接得住",
    "high" to "官方默认强度（默认）—— 四到九秒，复杂追问明显更稳",
    "max" to "最强 —— 最慢，只在特别绕的问题上值得"
)

/**
 * 编辑一段长文本的对话框。「知识背景」和「对话理解技能」共用。
 *
 * ## 为什么是多行 + 固定高度
 *
 * 这两段动辄几百字，单行输入框等于让人在一条缝里写字。但也不能让它随内容
 * 无限长高 —— 那样对话框会随着打字一直往上蹦。所以给一个固定的行数区间：
 * 写短了不塌，写长了框内自己滚。
 *
 * 外层还套了 verticalScroll：说明文字 + 输入框 + 字数在矮屏上可能放不下，
 * AlertDialog 的正文区不会自己滚，不套的话底部会被切掉。
 *
 * ## 到了上限为什么是"打不进去"而不是"保存时报错"
 *
 * 让字停在那儿，用户当场就看到计数不再涨 —— 比写完两千字、
 * 按保存才被告知"太长了"要好。上限的理由（每轮请求都要原样带上它，
 * 见 [ChatProfile.MAX_LENGTH]）写在说明里，免得看起来像个随意的限制。
 */
@Composable
fun ChatTextDialog(
    title: String,
    description: String,
    placeholder: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val length = text.length
    val nearLimit = length >= ChatProfile.MAX_LENGTH * 9 / 10

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { next ->
                        // 到上限就不再往后接。粘贴超长内容会被砍掉，
                        // 但下面的计数会立刻显示实际长度，不算静默
                        if (next.length <= ChatProfile.MAX_LENGTH) text = next
                    },
                    placeholder = { Text(placeholder) },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "$length / ${ChatProfile.MAX_LENGTH} 字",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (nearLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                if (nearLimit) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "每轮对话都会把它原样发给模型，太长的话每次提问都在为它付钱，"
                            + "而且超过一定长度后效果不再变好。压到几条最要紧的就行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (initial.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { text = "" },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                    ) {
                        Text("清空")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun StatusCard(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
    enabled: Boolean = true
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (granted) "●" else "○",
                    color = if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (actionLabel != null) {
                Spacer(Modifier.height(14.dp))
                if (enabled) {
                    Button(onClick = onAction) { Text(actionLabel) }
                } else {
                    OutlinedButton(onClick = onAction, enabled = false) { Text(actionLabel) }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    ExplainDotTheme {
        SettingsScreen(
            status = AppStatus(
                overlayGranted = false,
                dotRunning = false,
                a11yCapture = false
            ),
            onBack = {},
            onEditAiKey = {},
            onClearAiKey = {},
            onEditAiModel = {},
            onEditAiThinking = {},
            onGrantOverlay = {},
            onToggleDot = {},
            onOpenA11ySettings = {}
        )
    }
}
