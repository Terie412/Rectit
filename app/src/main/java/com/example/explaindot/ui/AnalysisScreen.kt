package com.example.explaindot.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 * 顶部那条：左边缩略图，右边一句摘要。
 *
 * 留着缩略图不是装饰：一眼看到「框住了什么」，才能判断后面挑出来的概念
 * 为什么是这几个。解码放在 remember 里，重组时不会反复读盘。
 *
 * [version] 这个参数看着多余，其实是必需的：截图固定写在 latest.jpg，
 * 每框一次都是同一个路径 —— 只拿 path 当 remember 的 key，第二次框选
 * 缩略图会继续显示上一张。调用方每换一张图就换一个 version，把它顶掉。
 *
 * ## 为什么不再显示分辨率和文件大小
 *
 * 早先这里写着「框选内容 / 1163×1625 px · 174 KB」。那两个数字是**排查问题用的**，
 * 不是给用户判断的东西 —— 他看缩略图就知道框住了什么，而分辨率既不帮他决定
 * 下一步做什么，也不能告诉他识别为什么失败。
 *
 * 腾出来的位置换成了这一屏真正的结论：有多少概念、几个不用等。
 * 那句话原先挂在概念列表的标题下面，现在挪上来 —— 它说的是「这张图分析出了什么」，
 * 属于这条摘要，不属于下面那个列表。
 *
 * [summary] 由调用方算好传进来（见 [captureSummary]），这个组件不认识分析状态。
 */
@Composable
fun CaptureThumbnail(
    path: String,
    version: Long,
    summary: String,
    modifier: Modifier = Modifier
) {
    val image: ImageBitmap? = remember(path, version) {
        runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
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

        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 缩略图右边那句摘要。
 *
 * 分两种情形，因为它们答的不是同一个问题：
 *
 *   - 还没出结果（没识别 / 正在识别 / 识别失败）→ 说**这张图现在什么状态**
 *   - 出了结果 → 说**分析出了什么**：几个概念、其中几个点开不用等
 *
 * 「几个已有解释」是这句话里最值钱的部分：它是用户攒下来的知识库在起作用，
 * 也是「不用反复花 token」这件事唯一能被看见的地方。
 *
 * 还没识别时不写「识别出 0 个概念」—— 那是把「没做过」说成了「做了但没找到」，
 * 两件事用户要做的事完全不同（一个是点「识别这一张」，一个是换块区域重框）。
 */
internal fun captureSummary(
    stage: AnalysisStage,
    concepts: List<Concept>,
    library: ConceptMatcher
): String = when (stage) {
    AnalysisStage.Idle -> "还没识别"
    AnalysisStage.Scanning -> "正在识别…"
    is AnalysisStage.ScanFailed -> "识别失败"

    else -> if (concepts.isEmpty()) {
        "没识别出概念"
    } else {
        val known = concepts.count { it.term in library }
        if (known > 0) {
            "识别出 ${concepts.size} 个概念 · $known 个已有解释"
        } else {
            "识别出 ${concepts.size} 个概念"
        }
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

/**
 * 概念列表。**分三段：列表自己滚、上下两块都固定。**
 *
 * ## 为什么不是整页滚动
 *
 * 早先这里是「一个 Column 套 verticalScroll」—— 概念有八个就是八张卡，
 * 一屏放不下，于是整页一起滚：两个按钮沉到最底下，用户想「重新识别」
 * 得先把列表滚到底才能点到。
 *
 * 而这两个按钮回答的是**「这张图我还想再要点什么」** —— 它们和当前这批概念
 * 是并列的，不是列表的尾巴。列表多长都不该改变它们的位置，所以固定住。
 *
 * 结构和 [TermPanel] 一致（那边也是中间滚、底部固定），
 * 两处的行为对齐之后用户不用重新学一次。
 *
 * ## 为什么顶部没有标题了
 *
 * 这里原先有「点一个看解释」加一行「共 N 个 · 其中 M 个已有解释」。
 * 前者删掉：概念卡本身就是一排可点的东西，旁边还写着「已有」的标记，
 * 「点一个看解释」是在说一件已经一目了然的事。
 *
 * 后者挪去了顶部那条摘要（见 [captureSummary]）—— 它说的是「这张图分析出了
 * 什么」，和缩略图是同一件事的两半，挂在列表上方反而像在说列表本身。
 *
 * 于是这一屏只剩列表和底部那条操作栏，中间那块也不用再垫标题的间距。
 */
@Composable
private fun ConceptListPanel(
    concepts: List<Concept>,
    library: ConceptMatcher,
    onPick: (Concept) -> Unit,
    onRescan: () -> Unit,
    onChat: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {

        // ------------------------------------------------------------------ 上：只有这块滚

        if (concepts.isEmpty()) {
            // 没有列表可滚，但这一块仍然要占住中间 ——
            // 否则底部那条会被推上来、贴着提示语，位置随内容变，就不叫固定了
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(14.dp))

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
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                // 上下各留一点：上面那条分隔线和第一张卡之间要透气，
                // 底部不留的话最后一张卡会紧贴操作栏，看着像被切掉了一截
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 14.dp,
                    bottom = 14.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // **刻意不设 key。** 设成 term 看着更讲究（重扫时能保住滚动位置），
                // 但模型偶尔会把同一个词返回两遍 —— 而 LazyColumn 遇到重复 key
                // 是直接抛 IllegalArgumentException 崩掉，不是忽略。
                // 为了一个动画效果去换一个线上崩溃的可能，不值。
                items(concepts) { concept ->
                    ConceptCard(
                        concept = concept,
                        cached = concept.term in library,
                        onClick = { onPick(concept) }
                    )
                }
            }
        }

        // ------------------------------------------------------------------ 下：固定的操作条

        // **它和上面那条列表之间必须有可见的边界。** 没有的话，
        // 「列表滚到底了」和「下面是另一块不跟着滚的区域」看起来是一回事 ——
        // 用户会继续往上滑那段列表，以为按钮也该跟着动。
        //
        // 做法照搬底部导航栏（见 [com.example.explaindot.ui.BottomBar]）：
        // surface 底 + 一条细分隔线。两个固定栏长得一样，用户不用重新认一次。
        // 底色之所以看得出区别，是因为页面正文铺在 Scaffold 的 background 上，
        // 而 background 比 surface 深一档。
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                HorizontalDivider()

                // 两个动作并排，而不是上下堆两行。
                //
                // 它们回答的是同一个层面的问题 ——「这张图我还想再要点什么」：
                // 左边是「换一批概念」，右边是「不挑概念了，直接聊」。
                // 上下堆成两行会把一个并列关系说成主次关系，而且多占一行竖向空间。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
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
            }
        }
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

/**
 * 解释页的底部操作条。三个按钮的可用性跟着当前内容变，不给人按了没反应的按钮。
 *
 * 不加底色、也不加分隔线。曾经试过底部栏那套（surface 底 + 一条细分隔线），
 * 在概念列表那一屏还没看出问题，但这里不行 —— 用户正在上下读一份正文，
 * 一条横线横在正文和按钮之间，像把文章切断了。
 *
 * 这两个页面共用同一套结构（[ConceptListPanel] 也是中间滚、底部固定），
 * 都不靠线条：固定区不跟着滚这件事，用户滑一下就知道。
 */
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
