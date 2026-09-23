package com.example.explaindot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 行尾动作的文字内边距。
 *
 * 写死、不吃 Material 的默认值：那个默认值是给「左右都可能放东西」的按钮备的，
 * 用在这里会让文字离屏幕边缘比设计意图更远。
 */
private val ROW_ACTION_PADDING = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

/**
 * 页面右上角的动作 —— 「设置」（首页）、「返回」（设置页）、「去处理」（状态条）
 * 共用这一个。**必须是同一个实现**，不能各页自己写。
 *
 * 为什么不用 TextButton：它自带 **minWidth = 58dp**，比「设置」两个字加内边距的
 * 实际宽度还大，于是文字被撑开后在多余空间里居中，右边缘比长一点的标签
 * （「去处理」）缩进了 9px。按钮框是对齐的，**文字**没对齐，而人看到的是文字。
 * （试过用 width(IntrinsicSize.Min) 压掉那个 minWidth，没用 —— 它是按钮内部
 * 写死的约束，从外面盖不住。）
 *
 * 所以自己拼：Box 撑出 48dp 的触摸高度，Text 贴合自己的宽度。
 * 于是宽度 = 文字 + 两侧各 12dp，右边缘只由右边距决定，跟标签长短无关。
 *
 * 三个调用点都在各自 Row 的末尾，且那些 Row 的 end padding 都是 8dp ——
 * 这两条一致，是「三个按钮的右边缘落在同一竖线上」的前提。
 * 哪一页要改行尾内边距，记得三处一起看。
 */
@Composable
internal fun RowAction(
    text: String,
    onClick: () -> Unit,
    contentColor: Color = Color.Unspecified
) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(ROW_ACTION_PADDING),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (contentColor == Color.Unspecified) {
                MaterialTheme.colorScheme.primary
            } else {
                contentColor
            }
        )
    }
}
