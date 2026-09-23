package com.example.explaindot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.explaindot.knowledge.KnowledgeStore
import com.example.explaindot.sync.ConfigField
import com.example.explaindot.sync.KnowledgeSync
import com.example.explaindot.sync.SyncSettings
import com.example.explaindot.sync.SyncTargets
import kotlinx.coroutines.launch

/**
 * 设置页里的「远程同步」。
 *
 * 单独一个文件而不是塞进 [SettingsScreen]：那张卡自带一个五字段的配置对话框、
 * 两个确认对话框，加起来一百多行，混进去之后设置页就更难读了。
 *
 * ## 这个 Composable 自己拿着"哪个对话框开着"
 *
 * 设置页其他地方是「没有本地状态、全靠传进来的快照」——那条规则针对的是**数据**，
 * 它让页面不持有真相。而"对话框开没开"是一次性的界面状态，没有第二个地方需要知道；
 * 交给这张卡自己记，设置页的签名就不用再多五个回调。
 *
 * 数据仍然是传进来的（[sync]、[local]），动作也是传出去的（[onConfigure] 等）。
 */
@Composable
internal fun SyncCard(
    sync: SyncUiState,
    local: KnowledgeStore.Stats?,
    /**
     * 保存配置。**是个挂起动作，而且要联网** —— 适配器会拿令牌去 GitHub
     * 问出账号名和默认分支，顺带验证令牌和仓库（见 `SyncTarget.resolve`）。
     *
     * 返回 null 表示成功；返回一句话表示失败，那句话直接显示在对话框里。
     * 之所以让它返回消息而不是抛异常：对话框要的是"显示什么"，
     * 而把异常翻成人话的逻辑属于调用方（那边才知道是"保存"还是"同步"失败了）。
     */
    onConfigure: suspend (Map<String, String>) -> String?,
    onPush: () -> Unit,
    onPull: () -> Unit
) {
    var dialog by remember { mutableStateOf<SyncDialog?>(null) }

    // 确认框里要说清"要动的是多少"。统计还没读出来（首次进页面）就退化成"内容"
    val localLabel = local?.let { "${it.concepts} 个概念 / ${it.tags} 条解释" } ?: "内容"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (sync.configured) "●" else "○",
                    color = if (sync.configured) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "远程同步",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(8.dp))

            // 平台名就是这一行的标签，值显示"东西存在哪儿"。
            // 现在只有一个平台，所以不做平台选择器 —— 摆一个只有一项的下拉框
            // 只会让人以为还能选别的
            SettingRow(
                label = sync.label,
                value = sync.summary,
                valueIsWarning = !sync.configured,
                actions = listOf("配置" to { dialog = SyncDialog.Config })
            )

            HorizontalDivider()

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 上传是主按钮、下载是描边按钮：两个动作都会覆盖东西，
                // 但上传覆盖的是远端（有 git 历史兜着），下载覆盖的是本地
                // （删掉就真没了）。视觉上分个轻重，别让人随手点错那个更狠的
                Button(
                    onClick = { dialog = SyncDialog.ConfirmPush },
                    enabled = sync.configured && !sync.running
                ) { Text("强制上传") }

                OutlinedButton(
                    onClick = { dialog = SyncDialog.ConfirmPull },
                    enabled = sync.configured && !sync.running
                ) { Text("强制下载") }
            }

            if (sync.running) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = sync.runningLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (sync.message != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = sync.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sync.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (!sync.configured) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "填一个 GitHub 仓库，本地知识库就能整份传上去，" +
                        "换台手机再整份拉下来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    when (dialog) {
        SyncDialog.Config -> SyncConfigDialog(
            fields = sync.fields,
            values = sync.values,
            onDismiss = { dialog = null },
            // 保存不再是"写进本地就完事"：它要联网把账号名和分支补全
            // （见 SyncTarget.resolve），所以可能失败。失败时说清原因、
            // 而且**不关对话框** —— 关掉了用户就得重新点开、重新看那两句话
            onSave = onConfigure,
            onSaved = { dialog = null }
        )

        SyncDialog.ConfirmPush -> ConfirmDialog(
            title = "强制上传？",
            message = "把本地的 $localLabel 整份覆盖到远端。" +
                "远端现有的内容会被替换掉，这一步不做合并。",
            confirm = "上传",
            onDismiss = { dialog = null },
            onConfirm = { onPush(); dialog = null }
        )

        SyncDialog.ConfirmPull -> ConfirmDialog(
            title = "强制下载？",
            message = "用远端的内容整份覆盖本地。本地现有的 $localLabel 会被删掉，" +
                "远端里没有的不会留下。",
            confirm = "下载",
            onDismiss = { dialog = null },
            onConfirm = { onPull(); dialog = null }
        )

        null -> Unit
    }
}

/** 这张卡自己管着哪一个对话框开着 */
private enum class SyncDialog { Config, ConfirmPush, ConfirmPull }

/**
 * 设置页要显示的同步状态。
 *
 * 在 [SettingsScreen] 的签名之外单独拼一个，是因为这一批值来自三个地方
 * （适配器、[SyncSettings]、[KnowledgeSync] 的状态），全塞进 SettingsScreen
 * 的参数列表会让它再长一截。由 [SettingsActivity] 调 [read] 拼好传进来。
 *
 * 它是 public 的，和 [SettingsScreen] 其他参数的类型（`AppStatus`、
 * `KnowledgeStore.Stats`）一致 —— 混着来会让那个函数的签名不再是
 * "从外面看得懂的东西"。
 */
data class SyncUiState(
    val label: String,
    val summary: String,
    val configured: Boolean,
    val fields: List<ConfigField>,
    /** 用户填过的值，key → 原文。给配置对话框预填 */
    val values: Map<String, String>,
    val running: Boolean,
    val runningLabel: String,
    /** 最近一次的结果；null 表示还没跑过 */
    val message: String?,
    val failed: Boolean
) {
    companion object {

        /**
         * 从当前状态拼一份。
         *
         * [values] 由调用方传进来，而**不是在这里现读 SharedPreferences**：
         * SharedPreferences 不是 Compose 状态，现读的话配置改完界面不会更新
         * （卡片上那句"东西存在哪儿"会一直显示旧的）。Activity 把它当成一份
         * Compose 状态拿着，保存时更新，于是这里跟着重组。
         *
         * 其余几项现读没问题：`KnowledgeSync.state` 本身就是 Compose 状态，
         * 读它就顺便订阅了"正在上传 / 上次结果"的变化。
         */
        fun read(values: Map<String, String>): SyncUiState {
            val target = SyncTargets.primary
            val state = KnowledgeSync.state
            val working = state as? KnowledgeSync.State.Working
            val result = state as? KnowledgeSync.State.Result

            return SyncUiState(
                label = target.label,
                summary = target.summary,
                configured = target.isConfigured,
                fields = target.fields,
                values = values,
                running = working != null,
                runningLabel = when (working?.upload) {
                    true -> "正在上传…"
                    false -> "正在下载…"
                    null -> ""
                },
                message = result?.message,
                failed = result?.ok == false
            )
        }
    }
}

/**
 * 连接配置。**只有两个输入框：令牌和仓库名。**
 *
 * 账号名和分支不在这里 —— 它们由适配器在保存时问出来（见 `SyncTarget.resolve`），
 * 文件路径更是写死在适配器里的常量。留下的这两项是**真的只有用户知道**的东西。
 *
 * ## 保存是异步的，而且可能失败
 *
 * 这一点和 [AiKeyDialog] 不同：那边写进本地就完事，这边保存要联网（问 GitHub
 * 账号名和默认分支，顺带验证令牌和仓库）。所以按钮按下之后先显示「正在核对…」，
 * 失败时把原因留在对话框里**而不是关掉它** —— 关掉了用户得重新点开，
 * 那两句说明还得重看一遍。
 *
 * 必填项没填全时「保存」是灰的：这次保存注定失败（适配器得先拿到这两项才能
 * 去问 GitHub），让他按下去只是白等一个网络来回。
 *
 * 为什么还是一整套配置一个对话框、而不是一项一个：AI 那三项是随时会改的单个值，
 * 一次改一个很自然；而这是一整套连接参数，填完基本不动 —— 拆成两次对话框
 * 只是把"一次配置"变成两次操作。
 */
@Composable
private fun SyncConfigDialog(
    fields: List<ConfigField>,
    values: Map<String, String>,
    onDismiss: () -> Unit,
    /** 保存并核对。返回 null 表示成功，否则是给用户看的失败原因 */
    onSave: suspend (Map<String, String>) -> String?,
    /** 保存成功了才调，用来关掉对话框 */
    onSaved: () -> Unit
) {
    var draft by remember { mutableStateOf(values) }
    var revealed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 现在每一项都是必填 —— "留空用默认值"那个概念跟着分支和路径一起没了
    val missing = fields.filter { draft[it.key].orEmpty().isBlank() }
    val hasSecret = fields.any { it.secret }

    AlertDialog(
        // 核对中不许关：请求已经发出去了，关掉只会让人以为没保存
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("GitHub") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "建一个私有仓库（用已有的也行），再建一个细粒度访问令牌，" +
                        "只给它这个仓库的 Contents 读写权限。\n\n" +
                        "账号名和分支不用填 —— 点保存时自动从 GitHub 取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))

                fields.forEach { field ->
                    val blank = draft[field.key].orEmpty().isBlank()
                    OutlinedTextField(
                        value = draft[field.key].orEmpty(),
                        onValueChange = { draft = draft + (field.key to it) },
                        singleLine = true,
                        enabled = !saving,
                        label = { Text(field.label) },
                        // 提示语常显在框下方，**不用 placeholder**。
                        //
                        // 真机上试出来的：Material3 的 placeholder 只在框空着
                        // **并且聚焦**时才出现。于是空着的框旁边什么字都没有 ——
                        // 而用户恰恰是看着一个空框的时候最需要那句说明。
                        // （这一点在对话里说「提示例如 my-notes」是看不出来的，
                        // 因为写代码时满脑子都是 placeholder 里的内容。）
                        //
                        // 填了就不显示：那时说明已经没有用处，留着只是噪音
                        supportingText = if (blank) {
                            { Text(field.hint) }
                        } else {
                            null
                        },
                        visualTransformation = if (field.secret && !revealed) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        trailingIcon = if (field.secret && hasSecret) {
                            {
                                TextButton(onClick = { revealed = !revealed }) {
                                    Text(if (revealed) "隐藏" else "显示")
                                }
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (missing.isNotEmpty()) {
                    Text(
                        text = "还差：${missing.joinToString("、") { it.label }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // 上次保存为什么没成。一直留在这儿，直到再试一次
                error?.let {
                    if (missing.isNotEmpty()) Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                // 没填全就注定失败（适配器得先拿到这两项才能去问 GitHub），
                // 拦住比让他等一个网络来回再被拒好
                enabled = !saving && missing.isEmpty(),
                onClick = {
                    scope.launch {
                        saving = true
                        error = null
                        val failure = onSave(draft)
                        saving = false
                        // 成功才关；失败把话留在框里，用户改完接着试
                        if (failure == null) onSaved() else error = failure
                    }
                }
            ) { Text(if (saving) "正在核对…" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        }
    )
}

/**
 * 一个动作之前的确认框。
 *
 * **两个动作都要确认，而且要把"要动多少"写进正文。**
 * 这是那两个"强制"唯一的保险：用户选了不做合并、也不留备份，
 * 那么至少在他按下去之前，得看见本地有多少条要被替换掉。
 */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
