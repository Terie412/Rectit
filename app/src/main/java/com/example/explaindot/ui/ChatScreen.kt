package com.example.explaindot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.explaindot.R
import com.example.explaindot.chat.ChatMessage
import com.example.explaindot.chat.ChatSession

/**
 * 对话理解一张图 —— 二级页面。
 *
 * 三块：上面是标题栏，中间是消息列表，下面是输入框。
 *
 * ## 为什么消息列表要自己滚
 *
 * 这是一个**流式**的界面：回答一个字一个字地长出来，而用户的眼睛盯着末尾。
 * 不跟着滚的话，回答超过一屏之后他就得自己往下拖 —— 而那是模型正在写的位置，
 * 拖过去正好错过正在出现的那几个字。
 *
 * 所以每次内容变化都滚到底。用 `scrollToItem` 而不是带动画的那个：
 * 流式时每一帧都在变，带动画会在动画没走完时被下一次打断，看起来是抖的。
 *
 * ## 为什么开场要自动发
 *
 * 用户点「对话理解」进来，期待的是模型开口讲这张图，而不是面对一个空白的
 * 输入框想"我该问什么"。所以第一次进来自动问一句（[ChatSession.ensureOpened]），
 * 而那条指令**不在界面上显示** —— 见 [ChatMessage.hidden]。
 */
@Composable
internal fun ChatScreen(
    session: ChatSession,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 第一次进来自动开场。key 用 Unit：一个页面只做一次；
    // 已经有历史的话 ensureOpened 自己会跳过（用户只是去了趟别处又回来）
    LaunchedEffect(Unit) {
        session.ensureOpened()
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // imePadding：键盘弹起来时整块内容上移，输入框不被挡住。
                // 这一页的主要动作就是打字，输入框被挡等于功能不可用
                .imePadding()
        ) {
            // 标题在左、动作在右，和首页、设置页、知识库页同构
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "对话理解",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                RowAction(text = "返回", onClick = onBack)
            }

            HorizontalDivider()

            MessageList(
                session = session,
                modifier = Modifier.weight(1f)
            )

            HorizontalDivider()

            InputBar(session = session)
        }
    }
}

/**
 * 消息列表。
 *
 * 只渲染 [ChatSession.visibleMessages]，加上正在流式接收的那条 ——
 * 后者还没进历史（它写完了才是一条消息），但必须现在就看到。
 */
@Composable
private fun MessageList(session: ChatSession, modifier: Modifier = Modifier) {
    val messages = session.visibleMessages
    val streaming = session.streaming
    val listState = rememberLazyListState()

    // 条数变了（新消息）或者流式内容长了（正在写）都要跟到底部。
    // 用 streaming?.length 当 key：它在流式时每一帧都变，于是每帧都滚一下，
    // 正好把"正在出现的那几个字"钉在视野里
    LaunchedEffect(messages.size, streaming?.length, session.error) {
        // 括号不能省：`a + if (x) 1 else 0 + if (y) 1 else 0` 会被解析成
        // `a + if (x) 1 else (0 + if (y) 1 else 0)`，流式那条就被漏掉了
        val count = messages.size +
            (if (streaming != null) 1 else 0) +
            (if (session.error != null) 1 else 0)
        if (count > 0) {
            runCatching { listState.scrollToItem(count - 1) }
        }
    }

    if (messages.isEmpty() && streaming == null && session.error == null) {
        // 开场那条请求刚发出去、还没收到第一个字。转个圈比一片空白诚实
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "正在看图…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(messages.size) { index ->
            Bubble(messages[index])
        }

        // 正在写的那条。它还没完成，所以不在 messages 里
        if (streaming != null) {
            item(key = "streaming") {
                Bubble(ChatMessage(ChatMessage.Role.Assistant, streaming), streaming = true)
            }
        }

        session.error?.let { reason ->
            item(key = "error") {
                ErrorBlock(
                    message = reason,
                    canRetry = session.retryable != null,
                    onRetry = { session.retry() }
                )
            }
        }
    }
}

/**
 * 一条消息的气泡。
 *
 * 用户靠右、模型靠左，底色也不同 —— 两个线索而不是一个：只靠对齐的话，
 * 长回答占满整宽时就分不清谁说的了。
 *
 * 颜色沿用这套界面既有的分工（见 [com.example.explaindot.ui.theme] 的说明）：
 * 蓝族表示"和当前操作相关"，所以用户的提问（他刚做的动作）用浅蓝底；
 * 模型的内容是给别人读的正文，用中性底 [surfaceVariant]。
 */
@Composable
private fun Bubble(message: ChatMessage, streaming: Boolean = false) {
    val fromUser = message.role == ChatMessage.Role.User

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            // 气泡最宽占屏幕的 85%：留出一条边，让"这是靠右的一条"这个信息
            // 在任何长度的消息上都看得出来
            modifier = Modifier.fillMaxWidth(0.85f),
            color = if (fromUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (fromUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (streaming) {
                    Spacer(Modifier.height(6.dp))
                    TypingDots()
                }
            }
        }
    }
}

/**
 * 流式输出时末尾的三个点。
 *
 * 回答写完之后这个就不见了。它解决的是一个具体的疑问：模型想了三五秒还没吐字时，
 * 界面上是一个已经有内容的框、看不出它还在不在干活 —— 而用户下一步动作
 * （等，还是重发）完全取决于这个判断。
 */
@Composable
private fun TypingDots() {
    Text(
        text = "● ● ●",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    )
}

/**
 * 出错时的那一块。
 *
 * **不把错误做成一条气泡** —— 气泡是"对话内容"，而这不是内容，
 * 是一次没成功的动作。摆成一条消息的话，用户会以为模型说了这句。
 */
@Composable
private fun ErrorBlock(message: String, canRetry: Boolean, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        if (canRetry) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRetry) {
                Text("重试", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * 底部输入区。
 *
 * 发送按钮和输入框同一行，而不是另起一行：手机键盘弹起来之后竖向空间很紧，
 * 多一行就少一行对话。
 *
 * 发送时立刻清空输入框 —— 内容已经进了历史（用户消息是乐观追加的），
 * 留着它只会让用户以为没发出去。
 *
 * 发送键是纸飞机图标（[R.drawable.ic_send]，轮廓取自设计给的位图），
 * 不是「发送」两个字。除了省下那两个字占的横向位置，更实际的是：
 * 这一行里输入框才是主角，一个文字按钮的视觉重量跟它相当，
 * 换成一个中性色的图标之后层级才分得开。
 */
@Composable
private fun InputBar(session: ChatSession) {
    var draft by remember { mutableStateOf("") }
    val canSend = draft.isNotBlank() && !session.busy

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // end 从 8dp 收到 4dp：IconButton 自带 12dp 的内边距，
            // 加上原来的 8dp 会让图标离屏幕右边缘比离左边缘远出半截
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text("问关于这张图的任何问题")
            },
            // 不禁用输入框：回答还在写的时候用户完全可以继续打字，
            // 只是发不出去（发送按钮是灰的）。禁掉的话，他打字打到一半
            // 回答结束了，光标会莫名其妙丢失
            maxLines = 4,
            shape = RoundedCornerShape(20.dp)
        )
        Spacer(Modifier.width(4.dp))

        // IconButton 而不是 TextButton：它自带 48dp 的触摸目标（图标只有 22dp），
        // 手指点得中，而 TextButton 换成图标之后要自己补这个尺寸
        IconButton(
            onClick = {
                session.ask(draft)
                draft = ""
            },
            enabled = canSend
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_send),
                // 这个不能省。按钮上只剩一个图形，读屏用户听到的就是这句话；
                // 漏了它 TalkBack 只会念出按钮的位置，不会说它是干什么的
                contentDescription = "发送",
                modifier = Modifier.size(22.dp),
                // 和原来那行文字按钮用同一组颜色：能发是主题色，不能发是 38% 的灰。
                // 不靠 IconButton 自动给的禁用色，是为了换图标时视觉不变
                tint = if (canSend) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
        }
    }
}
