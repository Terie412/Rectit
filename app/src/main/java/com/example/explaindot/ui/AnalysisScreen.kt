package com.example.explaindot.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.explaindot.ai.Concept
import com.example.explaindot.ai.RefineDecision
import com.example.explaindot.analysis.AnalysisStage
import com.example.explaindot.analysis.TermContent
import com.example.explaindot.knowledge.ConceptMatcher
import com.example.explaindot.knowledge.KnowledgeStore
import java.io.File

/**
 * 分析结果的展示层。
 *
 * 这一整个文件从原来的 CapturePreviewActivity 里搬出来 —— 它本来就是
 * 「结果页」的全部内容，现在首页直接用它，所以不能再挂在某个 Activity 上，
 * 只能是纯 Composable。
 */

/**
 * 框选内容的缩略图。
 *
 * 留着它不是装饰：一眼看到「框住了什么」，才能判断后面挑出来的概念
 * 为什么是这几个。解码放在 remember 里，重组时不会反复读盘。
 *
 * [version] 这个参数看着多余，其实是必需的：截图固定写在 latest.jpg，
 * 每框一次都是同一个路径 —— 只拿 path 当 remember 的 key，第二次框选
 * 缩略图会继续显示上一张。调用方每换一张图就换一个 version，把它顶掉。
 */
@Composable
fun CaptureThumbnail(
    path: String,
    version: Long,
    sizeLabel: String,
    weightLabel: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {}
) {
    val image: ImageBitmap? = remember(path, version) {
        runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(76.dp)
                .height(54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "框选区域的截图",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = "读图失败",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "框选内容",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$sizeLabel · $weightLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        trailing()
    }
}

/**
 * 分析主体。各状态各有一块面板，互斥呈现。
 *
 * 参数偏多，但都是有话要说的：讲解页现在同时可能显示「库里的多条解释」
 * 和「正在流式写入的那条」，还要能跳转、重新解释、删除。
 * 用一堆默认值把它压短，只会把「谁负责什么」藏起来。
 */
@Composable
fun AnalysisPane(
    stage: AnalysisStage,
    concepts: List<Concept>,
    termContent: TermContent,
    currentTerm: String?,
    trail: List<String>,
    library: ConceptMatcher,
    suggested: Set<String>,
    refining: Boolean,
    lastRefine: RefineDecision?,
    onScan: () -> Unit,
    onPickConcept: (Concept) -> Unit,
    onFollowLink: (term: String, context: String) -> Unit,
    onBack: () -> Unit,
    onBackToList: () -> Unit,
    onRefine: () -> Unit,
    onDeleteEntry: (term: String, tag: String) -> Unit,
    onRetryTerm: () -> Unit,
    /** 去「对话理解」页跟模型聊这张图 */
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (stage) {
            AnalysisStage.Idle -> IdlePanel(onScan = onScan)

            AnalysisStage.Scanning -> BusyPanel(text = "正在看这张图…")

            AnalysisStage.ConceptList -> ConceptListPanel(
                concepts = concepts,
                library = library,
                onPick = onPickConcept,
                onRescan = onScan,
                onChat = onOpenChat
            )

            AnalysisStage.Viewing -> TermPanel(
                term = currentTerm.orEmpty(),
                content = termContent,
                trail = trail,
                library = library,
                suggested = suggested,
                refining = refining,
                lastRefine = lastRefine,
                onFollowLink = onFollowLink,
                onBack = onBack,
                onBackToList = onBackToList,
                onRefine = onRefine,
                onDeleteEntry = onDeleteEntry,
                onRetry = onRetryTerm
            )

            is AnalysisStage.ScanFailed -> FailedPanel(
                message = stage.message,
                onRetry = onScan
            )
        }
    }
}

// ----------------------------------------------------------------------------------- 各状态面板

@Composable
private fun IdlePanel(onScan: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "这是上次框选的内容",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "当时没有存档分析结果，要看的话得再问一次模型。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Text("识别这一张")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BusyPanel(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConceptListPanel(
    concepts: List<Concept>,
    library: ConceptMatcher,
    onPick: (Concept) -> Unit,
    onRescan: () -> Unit,
    onChat: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(14.dp))

        if (concepts.isEmpty()) {
            // 这不是错误，是模型判断这页没有需要解释的词。说得平常一点
            Text(
                text = "这页没有需要解释的概念",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "模型看了一圈，没找到会造成理解障碍的词。也可能是框选区选得太小、" +
                    "或者字太糊 —— 重新框一次大一点的范围试试。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // 有几个已经在库里，值得单独说一句 —— 那是用户攒下来的东西在起作用，
            // 也是「不用反复花 token」这件事唯一能被看见的地方
            val known = concepts.count { it.term in library }

            Text(
                text = "点一个看解释",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (known > 0) {
                    "共 ${concepts.size} 个 · 其中 $known 个已有解释，点开不用等"
                } else {
                    "共 ${concepts.size} 个"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            concepts.forEach { concept ->
                ConceptCard(
                    concept = concept,
                    cached = concept.term in library,
                    onClick = { onPick(concept) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(12.dp))

        // 两个动作并排，而不是上下堆两行。
        //
        // 它们回答的是同一个层面的问题 ——「这张图我还想再要点什么」：
        // 左边是「换一批概念」，右边是「不挑概念了，直接聊」。
        // 上下堆成两行会把一个并列关系说成主次关系，而且多占一行竖向空间。
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onRescan,
                modifier = Modifier.weight(1f)
            ) {
                Text("重新识别")
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = onChat,
                modifier = Modifier.weight(1f)
            ) {
                Text("对话理解")
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConceptCard(concept: Concept, cached: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = concept.term,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (concept.type.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    TypeBadge(concept.type)
                }
                if (cached) {
                    Spacer(Modifier.width(6.dp))
                    CachedBadge()
                }
            }
            if (concept.hint.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = concept.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 「本地已有解释」的标记。用主色以示区别：它代表一个好消息，不是分类信息 */
@Composable
private fun CachedBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = "已有",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * 看某个概念。这是整个 App 的主屏。
 *
 * 三种形态共用一个壳：库里的成品（可能多条）、正在流式写入、以及失败。
 * 底部的操作行跟着变 —— 但只要栈深超过 1，就一定有「回到概念列表」这个出口。
 */
@Composable
private fun TermPanel(
    term: String,
    content: TermContent,
    trail: List<String>,
    library: ConceptMatcher,
    suggested: Set<String>,
    refining: Boolean,
    lastRefine: RefineDecision?,
    onFollowLink: (term: String, context: String) -> Unit,
    onBack: () -> Unit,
    onBackToList: () -> Unit,
    onRefine: () -> Unit,
    onDeleteEntry: (term: String, tag: String) -> Unit,
    onRetry: () -> Unit
) {
    val scrollState = rememberScrollState()

    // 流式写入时跟着滚到底，写到一半停住不动会让人以为卡死了。
    // 写完就不管了，让用户自己翻 —— 那时候再抢滚动条就成了骚扰
    val streamingText = (content as? TermContent.Writing)?.text
    LaunchedEffect(streamingText) {
        if (streamingText != null) scrollState.scrollTo(scrollState.maxValue)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(14.dp))

            // 路径摆在标题上面。层层点进来是这个 App 的主玩法，
            // 而"我为什么会看到这个词"只有这条路径能回答 ——
            // 单看标题会让人忘记自己从哪儿跳过来的
            Breadcrumb(trail)

            Text(
                text = term,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(14.dp))

            when (content) {
                is TermContent.Loading -> BusyRow("正在问模型…")

                is TermContent.Writing -> {
                    MarkdownLite(
                        text = content.text,
                        matcher = library,
                        context = content.text,
                        exclude = term,
                        onFollowLink = onFollowLink,
                        // 流式过程中这份建议还没解析出来 —— 标记在正文最后。
                        // 所以此刻传下去的是「这个词之前有没有留下过建议」，
                        // 首次解释时它必然为空。等写完那一帧链接会自己出现，
                        // 不会中途换一批颜色
                        suggested = suggested
                    )
                    Text(
                        text = "▍",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                is TermContent.Stored -> StoredEntries(
                    term = term,
                    entries = content.entries,
                    library = library,
                    suggested = suggested,
                    onFollowLink = onFollowLink,
                    onDeleteEntry = onDeleteEntry,
                    onRetry = onRetry
                )

                is TermContent.Failed -> {
                    Text(
                        text = "没成功",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(text = content.message, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        RefineFeedback(lastRefine)

        HorizontalDivider()

        TermActions(
            content = content,
            stackSize = trail.size,
            refining = refining,
            onBack = onBack,
            onBackToList = onBackToList,
            onRefine = onRefine,
            onRetry = onRetry
        )
    }
}

/**
 * 「怎么走到这个词的」。
 *
 * 只在真的跳转过（栈里不止一个）时才显示 —— 从概念列表直接点进来时，
 * 上面那句「还没有分析过内容」式的路径信息毫无意义，纯属占地方。
 *
 * 超过四层就从左边截断：再往前的路径对"我为什么会看到这个词"已经帮不上忙，
 * 而完整路径在手机上会换行、把标题挤下去。栈本身不截断（返回仍然一层层走），
 * 这里只是显示上收窄。
 */
@Composable
private fun Breadcrumb(trail: List<String>) {
    if (trail.size <= 1) return

    val shown = if (trail.size > MAX_BREADCRUMB) {
        listOf("…") + trail.takeLast(MAX_BREADCRUMB - 1)
    } else {
        trail
    }

    Text(
        text = shown.joinToString("  ›  "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(4.dp))
}

private const val MAX_BREADCRUMB = 4

/**
 * 库里的成品。一条解释一个卡片，标签顶在最上面。
 *
 * 一屏可能显示多条（同一个概念的不同适用场景）。这是有意的：
 * 用户点进来往往不知道自己要找的是哪个义项，摆在一起让他自己对号入座，
 * 比默认只显示第一条、再让他翻要快。
 */
@Composable
private fun StoredEntries(
    term: String,
    entries: List<KnowledgeStore.Entry>,
    library: ConceptMatcher,
    suggested: Set<String>,
    onFollowLink: (term: String, context: String) -> Unit,
    onDeleteEntry: (term: String, tag: String) -> Unit,
    onRetry: () -> Unit
) {
    if (entries.isEmpty()) {
        // 用户把这条解释删光了。不自动补一条 —— 那是他的选择
        Text(
            text = "这个概念还没有解释",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "本地没有它的解释（可能刚被你删掉）。问一次模型就会存下来。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("问一次模型")
        }
        return
    }

    entries.forEachIndexed { index, entry ->
        if (index > 0) Spacer(Modifier.height(18.dp))

        // 逐标签删除：用户要能只丢掉对不上的那一条，而不是把整个概念清掉重来。
        // 标签行的样式和正文渲染都在 ExplanationRender.kt —— 知识库页用的是同一份
        ExplanationEntry(
            term = term,
            entry = entry,
            library = library,
            suggested = suggested,
            onFollowLink = onFollowLink,
            onDelete = { onDeleteEntry(term, entry.tag) }
        )
    }
}

/** 底部操作行。三个按钮的可用性跟着当前内容变，不给人按了没反应的按钮 */
@Composable
private fun TermActions(
    content: TermContent,
    stackSize: Int,
    refining: Boolean,
    onBack: () -> Unit,
    onBackToList: () -> Unit,
    onRefine: () -> Unit,
    onRetry: () -> Unit
) {
    val hasStored = content is TermContent.Stored && content.entries.isNotEmpty()

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(if (stackSize > 1) "返回上一层" else "换一个概念")
            }

            // 只有库里有解释时才谈得上「重新解释」—— 那是拿已有内容
            // 和当前上下文对照的动作，没内容可对照时不显示
            if (hasStored) {
                OutlinedButton(
                    onClick = onRefine,
                    enabled = !refining,
                    modifier = Modifier.weight(1f)
                ) {
                    if (refining) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (refining) "重新解释中" else "重新解释")
                }
            }
        }

        if (content is TermContent.Failed) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("重试")
            }
        }

        // 栈深了才给这个出口。一层的时候「返回上一层」就是「换一个概念」，
        // 再放一个「回到概念列表」是同一件事的两个按钮
        if (stackSize > 1) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onBackToList, modifier = Modifier.fillMaxWidth()) {
                Text("回到概念列表", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * 「重新解释」之后给一句结果反馈。
 *
 * 模型判断「不用改」时，界面上什么都不会变 —— 没有这句话，用户会以为按钮坏了。
 */
@Composable
private fun RefineFeedback(decision: RefineDecision?) {
    val text = when (decision) {
        null -> return
        is RefineDecision.Keep -> "模型觉得已有的解释够用，没有改动。"
        is RefineDecision.Add -> "已补一条新解释。"
        is RefineDecision.Replace -> "已替换「${decision.targetTag}」那条解释。"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun BusyRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FailedPanel(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            text = "没成功",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(10.dp))
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("重试")
        }
    }
}

/** 没解码成 Bitmap 也要能显示尺寸：只读文件头，不占内存 */
internal fun measureImage(file: File): Pair<String, String> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }
    val size = if (options.outWidth > 0) "${options.outWidth}×${options.outHeight} px" else "?"
    return size to "${file.length() / 1024} KB"
}
