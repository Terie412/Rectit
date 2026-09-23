package com.example.explaindot.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.explaindot.R
import com.example.explaindot.knowledge.ConceptIndex
import com.example.explaindot.knowledge.ConceptMatcher
import com.example.explaindot.knowledge.KnowledgeBase
import com.example.explaindot.knowledge.KnowledgeStore
import com.example.explaindot.knowledge.LetterGroup
import com.example.explaindot.knowledge.PinyinOrdering
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 左边列表的宽度。
 *
 * 132dp 是个折中：概念名多为 2 到 6 个字，bodyMedium 下一行能放 7 个字左右，
 * 再长就省略号（用户点开就能看全）。多给列表一点、解释区就少一点 ——
 * 而解释区才是要读长文的地方，所以列表这边刻意压到刚好够认字。
 */
private val LIST_WIDTH = 132.dp

/** 当前路径的存档器。`trail` 是纯字符串列表，直接存就好 */
private val TrailSaver = listSaver<List<String>, String>(
    save = { it },
    restore = { it }
)

/**
 * 收起的分组存档器。
 *
 * `Set` 本身不是 Bundle 能装的东西，所以转成 List 存 ——
 * 和 [TrailSaver] 一个套路。字母顺序无所谓，它只是个成员集合。
 */
private val CollapsedSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() }
)

/**
 * 本地知识库浏览页 —— 二级页面。
 *
 * 三块：左边按拼音首字母分组的概念列表、上面一个搜索框、右边所选概念的全部解释。
 *
 * 窄屏上分栏是迫不得已（解释区只有两百多 dp），所以解释区左上角有一个
 * 「收起左栏」的开关：要好好读一条解释时把列表收掉，正文占满整宽，读完再展开。
 *
 * **它只动列表和正文那一行。** 上面的搜索框和计数行不参与收放 ——
 * 那是「找东西」的工具，和「读东西」的区域是两件事；跟着一起收会让人
 * 以为搜索也没了，而这页最常用的动作恰恰是搜。
 *
 * **状态放在这个 Composable 里，而不是 Activity。** 这一页有一堆需要跨转屏
 * 保留的东西（搜索词、选中项、跳转路径、左栏收没收起），写在 Activity 里就得自己
 * 实现 onSaveInstanceState；用 rememberSaveable 则自动搞定，而且状态和用它的
 * 界面挨在一起，读起来不用两头跳。
 *
 * [onLibraryChanged] 在这一页删掉解释之后调用 —— 主流程那份词表（决定哪些词
 * 能点）必须跟着更新，否则刚删掉的概念在主页面里仍然带链接，点进去却是空的。
 */
@Composable
fun KnowledgeScreen(
    library: ConceptMatcher,
    onBack: () -> Unit,
    onLibraryChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val store = KnowledgeBase.store
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }

    /**
     * 哪几个字母分组被折起来了。
     *
     * **存的是「收起的」而不是「展开的」**，默认空集就是全部展开 ——
     * 这样库里新增一个字母（用户刚解释了一个「Z」打头的词）时，
     * 它天然是展开的，不需要谁去补一条记录。反过来存「展开的」就得
     * 在概念列表变化时同步新增项，漏一次那个分组就是收着的，且看不出来。
     */
    var collapsedLetters by rememberSaveable(stateSaver = CollapsedSaver) {
        mutableStateOf(emptySet<String>())
    }

    /**
     * 跳转路径。末位是当前正在看的概念，首位是用户从列表里选的那个。
     *
     * 用 List 而不是 knowledge 包里那个 [com.example.explaindot.knowledge.LinkStack]：
     * 那个类是为「进程级、活得比页面久」的主流程写的，而这里是页内导航，
     * 用不可变 List 正好能直接配合 rememberSaveable 跨转屏保留。
     * 语义完全一样 —— 允许重复（A→B→A 时路径就是三个元素）。
     */
    var trail by rememberSaveable(stateSaver = TrailSaver) {
        mutableStateOf(emptyList<String>())
    }

    /** 删掉解释之后自增，用来触发列表和当前解释重读。用计数器而不是布尔，可以连续触发 */
    var reload by remember { mutableIntStateOf(0) }

    var concepts by remember { mutableStateOf<List<String>?>(null) }
    var entries by remember { mutableStateOf<List<KnowledgeStore.Entry>?>(null) }

    /**
     * 整个库的用量：几个概念、几条解释、占多大。
     *
     * **放在这一页而不是设置页。** 它不是配置项 —— 用户在这里改不了任何东西，
     * 它是这个库自己的数据预览，和左边那份概念列表是同一件事的两种呈现。
     * 摆在设置页里，等于把一个页面的内容寄存在另一个页面。
     *
     * 跟着 [reload] 一起重读：在这一页删掉一条解释，那三个数字要立刻跟着变。
     */
    var stats by remember { mutableStateOf<KnowledgeStore.Stats?>(null) }

    val deleteEntry = rememberDeleteAction(store) {
        reload++
        onLibraryChanged()
    }

    LaunchedEffect(reload) {
        val loaded = withContext(Dispatchers.IO) {
            // 顺手预热拼音排序器。
            //
            // 构造 Collator 要加载 ICU 的排序规则表，不便宜，而下面
            // `remember(concepts, query)` 里的分组是在**主线程同步**跑的 ——
            // 不预热的话，那笔开销会正好落在这一页的第一帧上。
            // 在这里先碰一下，单例就建好了，后面读它只是取缓存。
            // 放在 store 查询前面，保证 concepts 赋值时它一定已经就绪
            PinyinOrdering.shared
            // 两次查询放在同一个 IO 块里，一次切换线程就够
            store.allConcepts() to store.stats()
        }
        concepts = loaded.first
        stats = loaded.second
    }

    val currentTerm = trail.lastOrNull()

    LaunchedEffect(currentTerm, reload) {
        // 换概念时先置空：不留上一个概念的正文在新标题底下，那比转一下圈糟糕得多。
        // 这一步是本地数据库读，快到看不见
        entries = null
        entries = if (currentTerm == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { store.entries(currentTerm) }
        }
    }

    // 过滤和分组都在内存里做。概念名总共就一份（[concepts] 已经全读进来了），
    // 本地过滤是瞬时的，不必为了搜索再查一次库 —— 也就不用处理 LIKE 的通配符转义
    val groups = remember(concepts, query) {
        concepts?.let { ConceptIndex.group(ConceptIndex.filter(it, query), PinyinOrdering.shared) }
    }

    // 一搜索就把分组全部展开。
    //
    // 这是为了堵一个很难自己走出来的状态：用户先点了「折叠全部」，再搜一个词，
    // 计数行说「找到 2 个」而列表里一个都看不到（匹配到的分组还收着），
    // 看上去就是搜索坏了。搜了就是想看结果，折叠这个浏览辅助在这一刻该让位。
    // 代价是折叠状态会丢 —— 相比「搜不到东西」，那个损失小得多。
    LaunchedEffect(query) {
        collapsedLetters = emptySet()
    }

    /**
     * 系统返回键：先退一跳，最后才离开这一页。
     *
     * 不接这个的话，返回键会直接关掉整个页面，把跳转路径一起丢掉 ——
     * 而这一页的定位就是「能一路点下去、能一层层退回来」，那样等于白设计了。
     * 主流程的解释页有同样的处理（见 MainActivity），两处行为必须一致。
     *
     * （早先这里还管一件事：左栏收起时先展开左栏。左栏现在永远展开，那条没了。）
     */
    BackHandler(enabled = trail.size > 1) {
        trail = trail.dropLast(1)
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 标题在左、动作在右，和首页、设置页同构
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "知识库",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                RowAction(text = "返回", onClick = onBack)
            }

            when {
                concepts == null || groups == null -> LoadingPane()

                concepts!!.isEmpty() -> EmptyLibraryPane()

                else -> {
                    SearchBar(query = query, onQueryChange = { query = it })
                    CountLine(
                        shown = groups.sumOf { it.terms.size },
                        total = concepts!!.size,
                        stats = stats,
                        searching = query.isNotBlank()
                    )
                    HorizontalDivider()

                    Row(modifier = Modifier.weight(1f)) {
                        Column(
                            modifier = Modifier
                                .width(LIST_WIDTH)
                                .fillMaxHeight()
                        ) {
                            // 工具栏固定在列表上方：它是「整列怎么显示」的开关，
                            // 跟着列表一起滚的话，滑到中间想折叠全部还得先滑回去
                            ListToolbar(
                                canExpand = collapsedLetters.isNotEmpty(),
                                canCollapse = collapsedLetters.size < groups.size,
                                onExpandAll = { collapsedLetters = emptySet() },
                                onCollapseAll = { collapsedLetters = groups.map { it.letter }.toSet() }
                            )
                            HorizontalDivider()

                            ConceptListPane(
                                groups = groups,
                                selected = trail.firstOrNull(),
                                collapsed = collapsedLetters,
                                onToggleLetter = { letter ->
                                    collapsedLetters = if (letter in collapsedLetters) {
                                        collapsedLetters - letter
                                    } else {
                                        collapsedLetters + letter
                                    }
                                },
                                onPick = { term ->
                                    // 从列表选 = 换一条新的阅读线索，路径从头开始。
                                    // 折叠状态不动它 —— 能点到列表就说明字母组是展着的
                                    trail = listOf(term)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )

                        DetailPane(
                            term = currentTerm,
                            trail = trail,
                            entries = entries,
                            library = library,
                            onFollowLink = { linkTerm -> trail = trail + linkTerm },
                            onBackInTrail = { trail = trail.dropLast(1) },
                            onDeleteEntry = deleteEntry,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 删一条解释，然后让列表和当前解释都重读。
 *
 * 一步都不能省：删掉最后一个义项，这个概念就从列表里消失了 ——
 * 只刷新右边的正文会让它留成一个点不开的孤儿。
 *
 * 删失败时界面什么都不变（下一次重读会拿回原样），对用户来说就是「没删掉」，
 * 所以这里不为失败单独做提示。真正的异常边界在 [KnowledgeStore] 那一层。
 *
 * 不加 remember：`onDeleted` 是每个调用点现造的 lambda，每次重组都是新对象，
 * 拿它当 remember 的键等于每帧重建，白写。scope 本身来自 rememberCoroutineScope，
 * 它是稳定的，所以这个 lambda 便宜到不值得缓存。
 */
@Composable
private fun rememberDeleteAction(
    store: KnowledgeStore,
    onDeleted: () -> Unit
): (String, String) -> Unit {
    val scope = rememberCoroutineScope()
    return { term, tag ->
        scope.launch {
            withContext(Dispatchers.IO) { store.deleteTag(term, tag) }
            onDeleted()
        }
    }
}

// ----------------------------------------------------------------------------------- 顶栏部分

/**
 * 搜索框。
 *
 * 用填充样式（`TextField`）而不是描边样式（`OutlinedTextField`）：
 * 后者画的是 1dp 实线边框，在这套以 0.5dp 分隔线为主的界面里，
 * 它会成为整页最重的一笔，把视线从概念列表上拉走。
 *
 * 填充式改成圆角浅底、去掉下划线，视觉重量降到和一张卡片相当，
 * 符合「搜索」这个动作的分量 —— 它是找东西的工具，不是要 destacar 的内容。
 *
 * 高度保持 Material 的 56dp 默认值，不做压缩：这是最小的可靠触摸目标，
 * 而这一页的主要动作就是「点搜索框」和「点列表项」。
 */
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = {
            Text(
                text = "搜索概念名",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                // 露出一个明确的「清除」：用户搜完想回到全量列表时，
                // 不需要一个个删字符
                TextButton(onClick = { onQueryChange("") }) {
                    Text("清除", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            // 两个指示线都透明：填充式默认在底部画一条主色横线，
            // 和圆角浅底配在一起显得脏
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/**
 * 一行统计。
 *
 * 两件事共用这一行，因为它们本来就是同一件事：**这个库里有多少东西**。
 * 不搜索时说的是整个库，搜索时说的是筛出来的那部分 —— 拆成两行会把同一个
 * 数字说两遍（标题底下一次、列表上方一次），而这一页的竖向空间要留给列表。
 *
 * 加它是因为搜索之后列表会突然变短，没有数字的话用户不知道是「没这个东西」
 * 还是「库本来就是空的」。
 *
 * 概念数用 [total]（左边那份列表的长度）而不是 `stats.concepts`：两者查的是
 * 同一个 DISTINCT，但列表才是用户在下面真正看到的那个 —— 万一哪次对不上，
 * 说列表的那个不会骗人。
 */
@Composable
private fun CountLine(
    shown: Int,
    total: Int,
    stats: KnowledgeStore.Stats?,
    searching: Boolean
) {
    Text(
        text = when {
            searching && shown == 0 -> "没有匹配的概念"
            searching -> "找到 $shown 个 · 共 $total 个"
            // stats 还没读出来（或读失败）时退回只报概念数 —— 宁可少说一句，
            // 也不要为了凑满三格编一个数字出来
            stats != null ->
                "$total 个概念 · ${stats.tags} 条解释 · ${formatBytes(stats.bytes)}"
            else -> "共 $total 个概念"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
    )
}

/**
 * 字节数按 SQLite 自己的页计数算，不查文件大小。
 *
 * WAL 模式下数据可能还躺在 `-wal` 文件里，而文件系统上那个 `knowledge.db`
 * 会小一大截（实测过：主库 36 KB，真正的数据在 420 KB 的 wal 里）——
 * 照着文件大小报，用户会以为没占多少地方。
 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

// ----------------------------------------------------------------------------------- 左栏

/**
 * 列表上方那排按钮：展开全部 / 折叠全部。
 *
 * 两个都做成图标：[LIST_WIDTH] 只有 132dp，摆汉字标签会挤成两行，
 * 而这两个动作的形状（四向朝外 / 四向朝内）本身就说明了方向。
 * 文字说法交给 contentDescription，读屏用户拿得到。
 *
 * **无事可做时置灰。** 已经全展开时「展开全部」按下去什么都没发生，
 * 不置灰的话用户会以为按钮坏了或列表卡了 —— 而这两个按钮恰好是
 * 用户用来「把界面恢复正常」的，它们失去可信度比别的按钮更糟。
 */
@Composable
private fun ListToolbar(
    canExpand: Boolean,
    canCollapse: Boolean,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        ToolbarIcon(
            icon = R.drawable.ic_expand_all,
            label = "展开全部",
            enabled = canExpand,
            onClick = onExpandAll,
            modifier = Modifier.weight(1f)
        )
        ToolbarIcon(
            icon = R.drawable.ic_collapse_all,
            label = "折叠全部",
            enabled = canCollapse,
            onClick = onCollapseAll,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 工具栏里的一个图标按钮。
 *
 * 自己拼 Box，不用 IconButton ——
 * 后者的内边距是为「图标居中于方形触摸区」准备的，而这里两个按钮
 * 各占半列（66dp），居中是我们想要的，用 weight 分配宽度比套 IconButton 直接。
 *
 * 高度 48dp 是下限，不再压缩：这排按钮会被反复点，而它紧挨着下面的列表项，
 * 触摸区矮了就会误触到概念名上（那是会换阅读线索的动作）。
 */
@Composable
private fun ToolbarIcon(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 可用时用强调色、不可用降到中性并压暗。
    // 这套配色里「有颜色 = 可以点」，所以置灰在这里同时表达了两件事：
    // 不可用，以及不再是可交互的东西
    val tint = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 左侧的概念列表，按拼音首字母分组。
 *
 * [collapsed] 里的字母折叠起来 —— 只留组头，不摆概念名。
 * 折叠判定放在这里而不是把 groups 事先过滤好：调用方（以及计数行）
 * 需要的是完整的分组，折叠纯粹是这一列的显示方式。
 */
@Composable
private fun ConceptListPane(
    groups: List<LetterGroup>,
    selected: String?,
    collapsed: Set<String>,
    onToggleLetter: (String) -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (groups.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
            Text(
                text = "没有匹配的概念",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp, start = 8.dp, end = 8.dp)
            )
        }
        return
    }

    LazyColumn(modifier = modifier) {
        groups.forEach { group ->
            val isCollapsed = group.letter in collapsed

            item(key = "letter-${group.letter}") {
                LetterHeader(
                    letter = group.letter,
                    collapsed = isCollapsed,
                    isFirst = group == groups.first(),
                    onClick = { onToggleLetter(group.letter) }
                )
            }

            // 折叠时整组不摆出来。用条件包住 items 而不是渲染空列表：
            // 空 items 仍然会占一次组合，而这里一秒都不该多花
            if (!isCollapsed) {
                items(group.terms, key = { "term-$it" }) { term ->
                    ConceptRow(
                        term = term,
                        selected = term == selected,
                        onClick = { onPick(term) }
                    )
                }
            }
        }
        // 底部留白：最后一项贴着屏幕边缘不好点
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/**
 * 一个字母组的组头，整行可点。
 *
 * 展开/折叠的指示器用 `▾` / `▸` 两个字符，不引图标资源 ——
 * 这一页已经有用字符当记号的地方（路径分隔符是 `›`），保持一致；
 * 而且这两个字形在任何中文字体里都有，不会缺字。
 *
 * **指示器摆在字母右边而不是左边。** 字母的 x 位置不能动：它和列表项的文字
 * 落在同一条竖直基准线上（见下面 [start] 的注释），把指示器塞到字母前面
 * 会把它整条线推歪。摆在右边既保住了基准线，又紧挨着它管的那一组。
 *
 * 指示器给一个固定宽度：`▾` 和 `▸` 的字形宽度未必相同，不定宽的话
 * 每次切换字母会左右抖一下。
 */
@Composable
private fun LetterHeader(
    letter: String,
    collapsed: Boolean,
    isFirst: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // clickable 在 padding 之前：整行都能点。分组头是个高频动作，
            // 命中区只包住那几个字符的话，手指稍微偏一点就点空了
            .clickable(onClick = onClick)
            .padding(
                // start 用 14dp 而不是 12dp：列表项的文字落在
                // 6dp 外边距 + 8dp 内边距 = 14dp 处（见 [ConceptRow]），
                // 字母和它对齐才成一条竖直的基准线。差这 2dp 单看不明显，
                // 但整列字母会显得往左歪
                start = 14.dp,
                // 第一个字母组的上边距和右栏标题的顶部留白对齐（16dp），
                // 这样分栏时两栏的起始基线一致，不会一边高一边低
                top = if (isFirst) 16.dp else 12.dp,
                bottom = 4.dp,
                end = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = if (collapsed) "▸" else "▾",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(10.dp)
        )
    }
}

@Composable
private fun ConceptRow(term: String, selected: Boolean, onClick: () -> Unit) {
    // 选中底色用 primaryContainer（浅蓝），**不用 secondaryContainer（浅青）**。
    //
    // 浅青是义项标签（[TypeBadge]）的底色，那是另一件事。早先两处共用同一个色，
    // 于是左栏里一个被选中的概念、和右栏里一条解释的场景标签，看起来是同一类东西
    // ——而它们一个是「当前位置」，一个是「这条解释适用于什么场合」。
    //
    // 分开的依据是那套既有规则：蓝族 = 和强调色同族的状态（可点、当前、进行中），
    // 青色留给标签。这样不用新增色相，两件事也分得开了。
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val color = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .background(background, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Text(
            text = term,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ----------------------------------------------------------------------------------- 右栏

/**
 * 所选概念的解释。
 *
 * 分成上下两块：**上面是钉住的概念名 + 路径，下面独立滚动的是各条解释**。
 * 为什么要分开见固定区里那段注释。
 */
@Composable
private fun DetailPane(
    term: String?,
    trail: List<String>,
    entries: List<KnowledgeStore.Entry>?,
    library: ConceptMatcher,
    onFollowLink: (String) -> Unit,
    onBackInTrail: () -> Unit,
    onDeleteEntry: (term: String, tag: String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 两栏的文字左边缘要对齐。
    //
    // 左栏的列表项有自己的 6dp 外边距 + 8dp 内边距（为了让选中底色有呼吸空间），
    // 所以文字实际落在 x≈14dp；右栏如果从 12dp 起步，两栏的左边缘就差两个像素，
    // 扫视时那点错位会被看出来。
    val edgePadding = 14.dp

    if (term == null) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "从左边选一个概念\n看它存了哪些解释",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp)
            )
        }
        return
    }

    val scrollState = rememberScrollState()

    Column(modifier = modifier) {
        // ---------------------------------------------------------------- 固定区
        //
        // 概念名和路径**不参与滚动**。
        //
        // 理由是这一页的解释经常要读很久：读到一半忘了自己在看哪个词、
        // 或者想顺手删掉这条解释，就得先滑回顶部 —— 而这两件事恰恰是
        // 读完之后最想做的动作。把它们钉住，滑到哪儿都在。
        //
        // **只钉这两样，标签和删除按钮跟着各自那条解释滚。** 一个概念可能有
        // 多条解释（各带自己的标签），把它们全钉在上方、正文在下面滚，
        // 「哪段正文属于哪个标签」就说不清了；而绝大多数概念只有一条解释，
        // 这种情况下标签行本来就在题目下面，视觉上仍然像钉住的一样。
        Column(modifier = Modifier.padding(start = edgePadding, end = 12.dp)) {
            // 标题上方的留白。
            //
            // 原先这里是一行 48dp 的版面开关（收起/展开左栏），留白由它顺带担着；
            // 那个开关删掉之后这行得自己补回来，否则概念名会顶到分隔线上。
            // 16dp 是加开关之前原本的数，和左栏第一个字母组的顶部留白也对得上。
            Spacer(Modifier.height(16.dp))
            Text(
                text = term,
                // 概念名按 headlineSmall（24sp）排。
                //
                // 早先分栏时用的是 titleMedium（16sp），比正文（14sp）只大一点点 ——
                // 而那已经是在读一整个概念的解释了，名字却和正文差不多重，
                // 视线没有落点。现在它是这一屏的标题，不该因为左边多摆了一列就缩水。
                //
                // 分栏时解释区约 210dp 宽，24sp 一行约放得下 8 个汉字，
                // 而概念名多在 2~8 字之间，绝大多数仍然是一行。
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )

            // 路径只在真的跳转过时显示。从列表直接点进来时它毫无信息量，纯占地方
            if (trail.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = trail.joinToString("  ›  "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onBackInTrail) {
                        Text("返回上一层", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // 固定区和正文之间要有一条线：正文滑过去的时候，没有它就像是
        // 从概念名下面直接长出来的一样
        HorizontalDivider()

        // ---------------------------------------------------------------- 滚动区
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(start = edgePadding, end = 12.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            when {
                entries == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "正在读…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                entries.isEmpty() -> {
                    // 走到这里说明这个概念的义项刚被删光。「之后会重新问模型」是真的：
                    // 没有解释的概念不在词表里，下次在阅读中遇到时会被当成新概念处理
                    Text(
                        text = "这个概念的解释都删掉了。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "之后在阅读中再遇到它，会重新问一次模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> entries.forEachIndexed { index, entry ->
                    if (index > 0) Spacer(Modifier.height(18.dp))
                    ExplanationEntry(
                        term = term,
                        entry = entry,
                        library = library,
                        // 这里的链接点击不带语境之外的语义：知识库页没有在读书，
                        // 只是沿着概念之间的关系走。传正文是为了和主流程的签名一致
                        onFollowLink = { linkTerm, _ -> onFollowLink(linkTerm) },
                        onDelete = { onDeleteEntry(term, entry.tag) }
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

// ----------------------------------------------------------------------------------- 空状态

@Composable
private fun LoadingPane() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
    }
}

@Composable
private fun EmptyLibraryPane() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "知识库还是空的",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "你解释过的概念会存在这台手机上。\n" +
                "之后在别的文章里再遇到同一个词，会直接显示这份解释，不用再问模型 —— " +
                "解释里提到的其他概念也会变成可以点的链接。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
