package com.example.explaindot.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.explaindot.R
import com.example.explaindot.ui.theme.ExplainDotTheme

/**
 * 底部功能栏：设置 / 相册 / 相机 / 知识库。
 *
 * **它不是 tab 栏，所以没有选中态。** 这四件事的性质是分两类的：
 *   · 「设置」「知识库」是导航 —— 跳到各自那个二级页面
 *   · 「相册」「相机」是动作 —— 取一张图回来，人还留在这一页
 *
 * 动作没有「当前在不在它上面」这回事，硬给四个项都套上选中态，就等于
 * 对着一个不存在的问题给答案。所以这里没用 Material 的 NavigationBar：
 * 那个组件的语义就是「选中的是哪一个」，它的每个 item 都必须回答 selected ——
 * 为了绕开它而全都传 false，等于一边用一边跟它对着干。
 * 自己拼一个 Row，语义就是「四个入口」，干净。
 *
 * **图标是白色的 alpha 蒙版**（128×128，只有一种颜色），
 * 所以着色完全交给主题：`Icon` 的 tint 是按 alpha 重新上色而不是相乘，
 * 浅深两套主题下都不会出现「白图标看不见」。
 *
 * **四项都用强调色。** 这是整套配色里的一条规则：**有颜色 = 可以点**。
 * 早先这里用的是 onSurfaceVariant（中性灰），结果是四个入口和正文里的
 * 次级说明长得一模一样 —— 用户得先读过一遍才知道哪些是能按的。
 * 交给强调色之后，扫一眼就能分出「哪些是入口、哪些是内容」。
 *
 * 也没有选中态这一说（理由见上），所以四项同色不表示任何东西 ——
 * 它们本来就只是四个并列的入口。
 */
@Composable
internal fun BottomBar(
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenKnowledge: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            // 分隔线在系统导航栏那条空白之上：它是栏的顶边，
            // 不该被底部的 inset 空间推下去
            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 导航栏（手势条 / 三键）那一块要自己让出来。
                    // enableEdgeToEdge 之后应用是画到屏幕最底下的，不让的话
                    // 四项会被手势条压住 —— 而 Scaffold 只负责把栏的高度
                    // 算进内容的内边距，栏自己的 inset 得栏自己吃
                    .windowInsetsPadding(WindowInsets.navigationBars),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 顺序是用户指定的：设置，相册，相机，知识库
                BarItem(R.drawable.ic_setting, "设置", onOpenSettings, Modifier.weight(1f))
                BarItem(R.drawable.ic_album, "相册", onOpenGallery, Modifier.weight(1f))
                BarItem(R.drawable.ic_camera, "相机", onOpenCamera, Modifier.weight(1f))
                BarItem(R.drawable.ic_library, "知识库", onOpenKnowledge, Modifier.weight(1f))
            }
        }
    }
}

/**
 * 栏里的一项：图标 + 文字，整块可点。
 *
 * 触摸高度用 heightIn 兜到 56dp 而不是让内容自己撑：图标 24 + 间距 4 + 文字一行
 * 大约 44dp，比 Material 的触摸下限矮一点。撑到 56 之后四个项的命中区都够大，
 * 而视觉上仍然是一行紧凑的图标文字。
 *
 * 文字用 maxLines=1 + ellipsis：「知识库」三个字在窄屏上按 1/4 宽度分下来
 * 刚好够，但用户把系统字号调大之后就不一定了 —— 折行会把栏撑高，
 * 省略号只是少一个字的尾巴，代价小得多。
 */
@Composable
private fun BarItem(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 图标走强调色、文字走中性：图标是「可以点」这个判断的落点，
    // 文字只是它的名字。两者同色的话整条栏会变成一片蓝，反而没有重点
    val iconTint = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BottomBarPreview() {
    ExplainDotTheme {
        BottomBar(onOpenSettings = {}, onOpenGallery = {}, onOpenCamera = {}, onOpenKnowledge = {})
    }
}
