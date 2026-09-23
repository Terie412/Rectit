package com.example.explaindot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.explaindot.knowledge.ConceptMatcher
import com.example.explaindot.knowledge.KnowledgeStore
import com.example.explaindot.knowledge.StyledText
import com.example.explaindot.knowledge.buildStyledText
import com.example.explaindot.ui.theme.LinkNewDark
import com.example.explaindot.ui.theme.LinkNewLight
import com.example.explaindot.ui.theme.TagColor

/**
 * 解释正文的渲染 —— **主流程的解释页和知识库浏览页共用这一份**。
 *
 * 抽出来的理由不是「少写几行」，而是这两个页面对同一个概念必须显示得**一模一样**：
 * 同样大小的字、同样的行距、同样的链接颜色、同样的标签样式。
 * 各写一份的话，改了一边忘了另一边，同一条解释在两个入口下长得不一样，
 * 用户会以为是两条不同的内容。
 *
 * 这个文件里全是 `internal`，只给 ui 包内部用。
 */

/**
 * 义项标签。「哲学术语」「物理学的场」这类，标出这条解释适用于什么场景。
 *
 * **底色和字色由标签文字本身推出来**，见 [TagColor]：同一个义项在任何地方、
 * 任何设备上都是同一个颜色，而且颜色不落库、不进快照 ——
 * 改了算法，历史上所有标签一起改，不存在「旧数据色对不上」这回事。
 */
@Composable
internal fun TypeBadge(text: String) {
    val dark = isSystemInDarkTheme()
    Surface(
        color = TagColor.background(text, dark),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = TagColor.foreground(text, dark),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * 一条解释：标签行 + 正文。
 *
 * [onDelete] 为 null 时不显示删除按钮 —— 用可空参数而不是「传个空 lambda」，
 * 因为空 lambda 会渲染出一个按了没反应的按钮，那比没有按钮更糟。
 *
 * [term] 有两个用途，都必须传：正文里要排除掉「正在看的这个词」本身
 * （点进去还是同一页，是死路），删除时要按 (term, tag) 定位到那一行。
 *
 * [suggested] 是模型建议的延伸概念，默认空 —— 知识库浏览页就不传（它只是
 * 在翻缓存，不该让里面的绿色链接引出新的模型请求，见 [MarkdownLite]）。
 */
@Composable
internal fun ExplanationEntry(
    term: String,
    entry: KnowledgeStore.Entry,
    library: ConceptMatcher,
    onFollowLink: (term: String, context: String) -> Unit,
    onDelete: (() -> Unit)? = null,
    suggested: Set<String> = emptySet()
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TypeBadge(entry.tag)
        Spacer(Modifier.weight(1f))
        if (onDelete != null) {
            TextButton(onClick = onDelete) {
                Text("删除", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    Spacer(Modifier.height(4.dp))

    MarkdownLite(
        text = entry.body,
        matcher = library,
        context = entry.body,
        exclude = term,
        onFollowLink = onFollowLink,
        suggested = suggested
    )
}

// ----------------------------------------------------------------------------------- Markdown

private sealed interface Block {
    val text: String

    data class Heading(override val text: String) : Block
    data class Bullet(override val text: String) : Block
    data class Paragraph(override val text: String) : Block
}

private val ORDERED_ITEM_PATTERN = Regex("^\\d+[.、)]\\s+(.*)$")

/**
 * 极简 Markdown 渲染，只认模型实际会用到的几种记号：
 * `#` 标题、`-` 列表、`**粗体**`、空行分段。**外加本地算出来的可跳转概念**。
 *
 * 不引第三方 markdown 库的理由：解释内容是我们自己用 prompt 约束出来的，
 * 记号种类完全可控，为这点需求拉个库进来不划算，而且版本兼容要多留一份心。
 *
 * **[context] 会随链接点击一起交出去。** 它有两个消费者：
 * 主流程拿它当「用户在当前读什么」，供「重新解释」判断该补哪个义项；
 * 知识库页不用它，只是照着签名传下去。用整条正文而不是被点中的那一小段 ——
 * 模型要判断「已有的解释和用户读的东西对不对得上」，需要的正是那整段内容。
 *
 * **两种链接是两种颜色的。** 库里已有解释的词是主色（点下去立刻出结果），
 * 模型建议、库里还没有的词是第三色（点下去要现问一次模型）。用户看得见
 * 这个差别，才会明白两者代价不同。
 *
 * [suggested] **默认是空的，而且知识库页刻意不传。** 那一页翻的全是缓存，
 * 一屏里可能同时摆着几条解释，每条都撒一把「点一下就去问模型」的绿词，
 * 一次误触就是一次真实请求。这个功能只属于「刚为某个词问过一次模型」
 * 的那个场合 —— 见 [com.example.explaindot.analysis.AnalysisController] 里
 * 那份按概念名缓存的建议表。
 *
 * **链接是在这里逐块算的，不是在整篇上算的。** 这一点很要紧：
 * 每一块会渲染成独立的 Text，坐标必须落在它自己的字符串上。
 * 在整篇上算完再按块切，位置会整体偏移，链接就会标到旁边的字上。
 */
@Composable
internal fun MarkdownLite(
    text: String,
    matcher: ConceptMatcher,
    context: String,
    exclude: String?,
    onFollowLink: (term: String, context: String) -> Unit,
    suggested: Set<String> = emptySet()
) {
    val blocks = remember(text) { parseBlocks(text) }
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp)

    val knownLinkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline
        )
    )
    val newLinkStyles = TextLinkStyles(
        style = SpanStyle(
            // 固定的暖色，不走主题的 tertiary —— 它是 primary 的近亲色，
            // 开动态配色后会落到同一个色相上，两种链接就分不出来了。
            // 详见 ui/theme/Color.kt 里那段实测记录
            color = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
                LinkNewDark
            } else {
                LinkNewLight
            },
            textDecoration = TextDecoration.Underline
        )
    )

    // 库里已经有的词走已知那一路，别在这里重复标成「新词」。
    // 两套词表都在这里建好再交给 buildStyledText —— 它只负责算位置，
    // 不负责判断谁该排在谁前面
    val suggestedMatcher = remember(suggested, matcher) {
        ConceptMatcher(suggested.filterNot { matcher.contains(it) })
    }

    Column {
        blocks.forEach { block ->
            val styled = remember(block, matcher, exclude, suggestedMatcher) {
                buildStyledText(block.text, matcher, exclude, suggestedMatcher)
            }
            val annotated = remember(styled, knownLinkStyles, newLinkStyles) {
                styled.toAnnotated(
                    knownStyles = knownLinkStyles,
                    newStyles = newLinkStyles,
                    onClick = { term -> onFollowLink(term, context) }
                )
            }

            when (block) {
                is Block.Heading -> {
                    Text(
                        text = annotated,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                }

                is Block.Bullet -> {
                    Row(modifier = Modifier.padding(start = 2.dp)) {
                        Text(text = "·", style = bodyStyle)
                        Spacer(Modifier.width(8.dp))
                        Text(text = annotated, style = bodyStyle)
                    }
                    Spacer(Modifier.height(5.dp))
                }

                is Block.Paragraph -> {
                    Text(text = annotated, style = bodyStyle)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

/**
 * 把算好的加粗范围和链接范围套到显示文本上。
 *
 * 两件事在同一个 builder 里做，是因为它们共用 [StyledText] 那一套坐标 ——
 * 分两趟处理就要各自维护游标，迟早会错位。
 *
 * 链接的样式按 [LinkSpan.known] 分两种。**这个判断只在这里做一次**：
 * 位置和归属都在 [buildStyledText] 里定好了，这里再算一遍等于把同一件事
 * 实现两遍，两边迟早会不一致。
 */
private fun StyledText.toAnnotated(
    knownStyles: TextLinkStyles,
    newStyles: TextLinkStyles,
    onClick: (String) -> Unit
): AnnotatedString = buildAnnotatedString {
    append(text)

    bold.forEach { span ->
        addStyle(SpanStyle(fontWeight = FontWeight.Bold), span.start, span.end)
    }

    links.forEach { span ->
        addLink(
            LinkAnnotation.Clickable(
                tag = span.term,
                styles = if (span.known) knownStyles else newStyles,
                linkInteractionListener = LinkInteractionListener { onClick(span.term) }
            ),
            span.start,
            span.end
        )
    }
}

private fun parseBlocks(raw: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = StringBuilder()

    fun flush() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += Block.Paragraph(text)
        paragraph.setLength(0)
    }

    // 流式输出时可能停在半个记号上，比如只有 "**" 没有收尾。渲染不出来也不会崩，
    // 下一帧补全就好了，所以这里不需要为「不完整」做特殊处理
    raw.lines().forEach { rawLine ->
        val line = rawLine.trim()

        when {
            line.isEmpty() -> flush()

            // 一整行只有分隔符，是 Markdown 的水平线，丢掉
            line.length >= 3 && line.all { it == '-' || it == '*' || it == '_' } -> flush()

            line.startsWith("#") -> {
                flush()
                val title = line.trimStart('#').trim()
                if (title.isNotEmpty()) blocks += Block.Heading(title)
            }

            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> {
                flush()
                blocks += Block.Bullet(line.drop(2).trim())
            }

            ORDERED_ITEM_PATTERN.matches(line) -> {
                flush()
                blocks += Block.Bullet(ORDERED_ITEM_PATTERN.matchEntire(line)!!.groupValues[1].trim())
            }

            else -> {
                // 段内换行保留原样。中文行强行拼成一行反而更难读
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
    }
    flush()

    return blocks
}
