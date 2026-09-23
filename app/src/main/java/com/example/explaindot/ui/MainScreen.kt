package com.example.explaindot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.explaindot.ai.Concept
import com.example.explaindot.ai.RefineDecision
import com.example.explaindot.analysis.AnalysisStage
import com.example.explaindot.analysis.TermContent
import com.example.explaindot.knowledge.ConceptMatcher
import com.example.explaindot.ui.theme.ExplainDotTheme

/**
 * 首页。
 *
 * 这一页只干一件事：把取来的那张图的分析结果摆出来。为什么这样改 ——
 * 权限状态、怎么用、国产 ROM 的坑，这些看一次就够了，天天摆在首屏
 * 属于用噪声盖住正文。它们现在都在设置页（设置页是底部栏里的一个入口）。
 *
 * 唯一保留下来的是「还没做完的事」那一行，而且只在真的有事时出现：
 * 圆点没开、悬浮窗没授权、AI 没配 key。一切正常的时候，这一页上
 * 除了分析结果什么都没有。
 *
 * **图的三个来源在这里没有区别。** 框选、相册、相机拿到的都是同一张
 * 「当前待分析图」，落到同一个文件上；这一页只管把结果摆出来，
 * 不关心它是怎么来的。见 [com.example.explaindot.image.PendingImage]。
 */
@Composable
fun MainScreen(
    status: AppStatus,
    imagePath: String?,
    imageVersion: Long,
    sizeLabel: String,
    weightLabel: String,
    stage: AnalysisStage,
    concepts: List<Concept>,
    termContent: TermContent,
    currentTerm: String?,
    trail: List<String>,
    library: ConceptMatcher,
    suggested: Set<String>,
    refining: Boolean,
    lastRefine: RefineDecision?,
    onOpenSettings: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenCamera: () -> Unit,
    onToggleDot: () -> Unit,
    onScan: () -> Unit,
    /** 去「对话理解」页跟模型聊当前这张图 */
    onOpenChat: () -> Unit,
    onPickConcept: (Concept) -> Unit,
    onFollowLink: (term: String, context: String) -> Unit,
    onBack: () -> Unit,
    onBackToList: () -> Unit,
    onRefine: () -> Unit,
    onDeleteEntry: (term: String, tag: String) -> Unit,
    onRetryTerm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 底部栏交给 Scaffold 来摆：它会把栏的高度算进内容的内边距，
        // 于是分析结果不会被栏盖住最后一屏。栏自身的系统导航栏 inset
        // 由 BottomBar 自己吃，见那里的注释
        bottomBar = {
            BottomBar(
                onOpenSettings = onOpenSettings,
                onOpenGallery = onOpenGallery,
                onOpenCamera = onOpenCamera,
                onOpenKnowledge = onOpenKnowledge
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TitleBar()

            // 空状态自己就把「该做什么」讲全了，还带按钮，再顶一行提示就是重复。
            // 有内容的时候才需要它 —— 那一页上除了缩略图和分析结果什么都没有，
            // 圆点掉了、权限被撤了得有个地方说
            if (imagePath != null) {
                PendingStrip(
                    status = status,
                    onOpenSettings = onOpenSettings,
                    onToggleDot = onToggleDot
                )
            }

            HorizontalDivider()

            if (imagePath == null) {
                EmptyState(
                    status = status,
                    onToggleDot = onToggleDot,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.weight(1f)
                )
            } else {
                CaptureThumbnail(
                    path = imagePath,
                    version = imageVersion,
                    sizeLabel = sizeLabel,
                    weightLabel = weightLabel
                )
                HorizontalDivider()
                AnalysisPane(
                    stage = stage,
                    concepts = concepts,
                    termContent = termContent,
                    currentTerm = currentTerm,
                    trail = trail,
                    library = library,
                    suggested = suggested,
                    refining = refining,
                    lastRefine = lastRefine,
                    onScan = onScan,
                    onOpenChat = onOpenChat,
                    onPickConcept = onPickConcept,
                    onFollowLink = onFollowLink,
                    onBack = onBack,
                    onBackToList = onBackToList,
                    onRefine = onRefine,
                    onDeleteEntry = onDeleteEntry,
                    onRetryTerm = onRetryTerm,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 页面标题。
 *
 * 这里原先还挂着「知识库」和「设置」两个入口，现在它们都在底部栏里了 ——
 * 同一件事不该有两个入口：两处的文案、间距、点击区都得各自维护，
 * 而用户还会犹豫「上面那个和下面那个是不是一回事」。
 *
 * 只留标题，右侧空着。它的作用就只剩下告诉刚进来的人「这是哪个 App 的页面」。
 */
@Composable
private fun TitleBar() {
    Text(
        text = "框选解释",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)
    )
}

/**
 * 「还没做完的事」。
 *
 * 这一行的存在理由很实在：圆点没开的时候整个 App 什么也做不了，
 * 但如果首页彻底不提这件事，用户会以为 App 坏了。所以它必须出现 ——
 * 但也只在有事情没做完的时候出现，而且只有一行。
 *
 * 挨个坑：悬浮窗没授权是硬伤（红色），AI 没配 key 是致命的（红色）；
 * 只是圆点没开则是每次重启后的常态，用中性色，别吓人。
 */
@Composable
private fun PendingStrip(
    status: AppStatus,
    onOpenSettings: () -> Unit,
    onToggleDot: () -> Unit
) {
    val blockers = status.blockers
    if (blockers.isEmpty()) return

    val serious = blockers.any {
        it == AppStatus.Blocker.Overlay || it == AppStatus.Blocker.Ai
    }
    val container = if (serious) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (serious) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(color = container, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(onClick = onOpenSettings)
                .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = blockers.joinToString(" · ") { it.label },
                style = MaterialTheme.typography.bodySmall,
                color = content,
                modifier = Modifier.weight(1f)
            )

            // 只有「圆点没开」这一件事值得给按钮 —— 它是每天都会碰到的操作，
            // 而悬浮窗授权和 API Key 都得跳出去处理，按钮放这儿反而多余。
            //
            // 这一行本身也是可点的（等于「去设置」），下面这个按钮跟它同一个动作：
            // 前者管整行都能点，后者是给「一眼看上去该点哪儿」一个明确落点。
            if (blockers.size == 1 && blockers[0] == AppStatus.Blocker.Dot) {
                RowAction(text = "开启", onClick = onToggleDot, contentColor = content)
            } else {
                RowAction(text = "去处理", onClick = onOpenSettings, contentColor = content)
            }
        }
    }
}

/**
 * 没内容可分析时的空状态。
 *
 * 这里放的是全 App 唯一一份「怎么用」。它出现在用户第一次打开 App、
 * 或者上一次截图已经被系统清掉的时候 —— 正好是需要它的时机。
 *
 * **这份清单必须覆盖所有没做完的设置项。** 因为状态条（[PendingStrip]）被
 * `imagePath != null` 挡着，没截图的时候这里就是唯一会提醒用户的地方。
 * 清单直接由 [AppStatus.blockers] 推出来，别在这里另写条件。
 */
@Composable
private fun EmptyState(
    status: AppStatus,
    onToggleDot: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "还没有分析过内容",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(20.dp))

        // 清单 = 「还没做完的设置」+「怎么取图」。
        //
        // 前半段直接把 status.blockers 映射成说法，不在这里另写一遍条件 ——
        // 原先就是各写各的，结果漏了 AI 和悬浮窗两项。而这一页在没有图时
        // 是唯一出现的地方（状态条被 imagePath != null 挡着），于是「只缺 Key」
        // 的用户把引导走完、框了一段，才在最后撞上「还没填 API Key」。
        // 现在两边的项数由编译器保证一致，见 AppStatus.Blocker.fix。
        //
        // 后半段三条取图路径并列。圆点框选是主路（它才是「不用离开正在读的东西」
        // 那个卖点），相册和相机是这一版新加的：不用框、直接给一整张图，
        // 适合拍书页、或者事后翻到一张想弄明白的截图。
        val steps = status.blockers.map { it.fix } + listOf(
            "打开你在读的那个 App，在圆点上按住，划出看不懂的那一段",
            "或者从下边的「相册」选一张、用「相机」直接拍一张"
        )

        steps.forEachIndexed { index, line ->
            Row(modifier = Modifier.padding(vertical = 5.dp)) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(22.dp)
                )
                Text(text = line, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // 缺圆点之外的任何一项，都得去设置页；只差圆点的话在这一页就能开
        val needsSetup = status.blockers.any { it != AppStatus.Blocker.Dot }
        val needsDot = AppStatus.Blocker.Dot in status.blockers

        Spacer(Modifier.height(28.dp))

        when {
            needsSetup -> Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("去设置")
            }

            needsDot -> Button(onClick = onToggleDot, modifier = Modifier.fillMaxWidth()) {
                Text("开启圆点")
            }

            // 没有可做的动作了，但要说清接下来能干什么 —— 这一页上没有别的地方讲它，
            // 而底部那四个入口此时正好在用户眼皮底下
            else -> Text(
                text = "都配好了。圆点正在运行，也可以直接用下面的「相册」或「相机」取一张图。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenEmptyPreview() {
    ExplainDotTheme {
        MainScreen(
            status = AppStatus(
                overlayGranted = false,
                dotRunning = false,
                a11yCapture = false
            ),
            imagePath = null,
            imageVersion = 0L,
            sizeLabel = "",
            weightLabel = "",
            stage = AnalysisStage.Idle,
            concepts = emptyList(),
            termContent = TermContent.Loading,
            currentTerm = null,
            trail = emptyList(),
            library = ConceptMatcher(emptyList()),
            suggested = emptySet(),
            refining = false,
            lastRefine = null,
            onOpenSettings = {},
            onOpenKnowledge = {},
            onOpenGallery = {},
            onOpenCamera = {},
            onToggleDot = {},
            onScan = {},
            onOpenChat = {},
            onPickConcept = {},
            onFollowLink = { _, _ -> },
            onBack = {},
            onBackToList = {},
            onRefine = {},
            onDeleteEntry = { _, _ -> },
            onRetryTerm = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenConceptPreview() {
    ExplainDotTheme {
        MainScreen(
            status = AppStatus(
                overlayGranted = true,
                dotRunning = true,
                a11yCapture = true
            ),
            imagePath = null,
            imageVersion = 0L,
            sizeLabel = "",
            weightLabel = "",
            stage = AnalysisStage.ConceptList,
            concepts = emptyList(),
            termContent = TermContent.Loading,
            currentTerm = null,
            trail = emptyList(),
            library = ConceptMatcher(emptyList()),
            suggested = emptySet(),
            refining = false,
            lastRefine = null,
            onOpenSettings = {},
            onOpenKnowledge = {},
            onOpenGallery = {},
            onOpenCamera = {},
            onToggleDot = {},
            onScan = {},
            onOpenChat = {},
            onPickConcept = {},
            onFollowLink = { _, _ -> },
            onBack = {},
            onBackToList = {},
            onRefine = {},
            onDeleteEntry = { _, _ -> },
            onRetryTerm = {}
        )
    }
}
