package com.example.explaindot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 浅色 —— 「白与光」。
 *
 * 所有取值来自 [Color.kt] 那份调色板，这里只做装配。颜色值一个都不写在这儿：
 * 配色是一件事，装配是另一件事，混在一起的话改一处颜色得读三十行。
 *
 * `surfaceContainer` 那五档是给 Material 组件内部用的（卡片、对话框、
 * 下拉菜单、填充式输入框各取一档）。**必须显式给值** —— 不给的话它们会
 * 回落到 M3 的基线色（偏紫的灰），然后从卡片和对话框的角上漏出来，
 * 在一套冷蓝配色里格外显眼。这是那种「看半天找不出哪里不对」的漏色。
 */
private val LightScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandOnPrimary,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = BrandOnPrimaryContainer,

    // secondary 这一族当前只用在解释标签的底和知识库选中行上，
    // 也就是图标那支青的落点。组件若拿它当默认强调色，也是同族的
    secondary = BrandTertiary,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = BrandSecondaryContainer,
    onSecondaryContainer = BrandOnSecondaryContainer,

    // tertiary 是「第三种强调」的位置，当前没有直接用。
    // 注意它**不能**当第二种链接色 —— 和主色只差 50°，都读成蓝，拉不开。
    // 第二色链接另外写死在 [LinkNewLight]，原因见那里
    tertiary = BrandTertiary,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = BrandSecondaryContainer,
    onTertiaryContainer = BrandOnSecondaryContainer,

    background = BrandBackground,
    onBackground = BrandText,
    surface = BrandSurface,
    onSurface = BrandText,
    surfaceVariant = BrandSurfaceVariant,
    onSurfaceVariant = BrandTextMuted,

    // 容器阶。**这里和 M3 的默认走向相反，是有意的。**
    //
    // M3 的惯例是「档越高、色越深」，于是 Card（用的是 surfaceContainerHighest）
    // 会落在比页面底更深的一档上。实测就是这样：卡片 #E8ECF3 压在页面 #F5F7FB 上，
    // **只差 1.08:1** —— 卡片看着像沉进页面里，整页糊成一整块浅灰蓝。
    // 设置页本来就是一堆卡片，那一整页就会显得又闷又廉价。
    //
    // 所以要的是「浮起来」：卡片和对话框给纯白，页面底偏灰。
    // 面比底亮，卡片才是卡片 —— 这也是让蓝色和青色的小元素看得清的前提
    // （它们原来全压在浅灰上，本来就没什么对比可谈）。
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFD),
    surfaceContainer = Color(0xFFF7F9FC),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFFFFFFF),

    outline = BrandOutline,
    outlineVariant = BrandOutlineVariant,

    error = BrandError,
    onError = Color(0xFFFFFFFF),
    errorContainer = BrandErrorContainer,
    onErrorContainer = BrandOnErrorContainer,

    // 反色面板（Snackbar 一类）。当前没用到，给了值免得它回落到基线紫
    inverseSurface = BrandText,
    inverseOnSurface = BrandSurface,
    inversePrimary = NightPrimary,
    scrim = Color(0xFF000000)
)

/** 深色 —— 「夜」。和浅色不是同一套的镜像，是各自调过的，见 [Color.kt] 的说明 */
private val DarkScheme = darkColorScheme(
    primary = NightPrimary,
    onPrimary = NightOnPrimary,
    primaryContainer = NightPrimaryContainer,
    onPrimaryContainer = NightOnPrimaryContainer,

    secondary = NightTertiary,
    onSecondary = Color(0xFF00312C),
    secondaryContainer = NightSecondaryContainer,
    onSecondaryContainer = NightOnSecondaryContainer,

    tertiary = NightTertiary,
    onTertiary = Color(0xFF00312C),
    tertiaryContainer = NightSecondaryContainer,
    onTertiaryContainer = NightOnSecondaryContainer,

    background = NightBackground,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightTextMuted,

    surfaceContainerLowest = Color(0xFF0B0D11),
    surfaceContainerLow = Color(0xFF12151A),
    surfaceContainer = Color(0xFF1B1F26),
    surfaceContainerHigh = Color(0xFF232830),
    surfaceContainerHighest = Color(0xFF2C323B),

    outline = NightOutline,
    outlineVariant = NightOutlineVariant,

    error = NightError,
    onError = Color(0xFF42100D),
    errorContainer = NightErrorContainer,
    onErrorContainer = NightOnErrorContainer,

    inverseSurface = NightText,
    inverseOnSurface = NightBackground,
    inversePrimary = BrandPrimary,
    scrim = Color(0xFF000000)
)

/**
 * 全 App 唯一的主题入口。
 *
 * **拿掉了动态配色（Material You），这是有意的。**
 *
 * 原来这里在 Android 12+ 上走 `dynamicLightColorScheme` —— 整套配色由用户的
 * 壁纸推导。好处是 App 和系统「像一家人」，代价是：App 长什么样取决于
 * 用户换没换壁纸，同一版程序在两个人手机上可以完全不像。
 * 这恰恰和「协调统一」相反 —— 协调统一不是靠某一套颜色好看，
 * 是靠**每一样东西都来自同一套颜色**。
 *
 * 这一版的配色还有个额外理由：整套颜色的来源是**应用图标**，
 * 而图标是固定的。跟着壁纸走的话，界面和图标随时可能分家。
 *
 * 想换回动态配色的话，把下面换回 dynamic*ColorScheme 即可 —— 但那样
 * 第二色链接（[LinkNewLight]）和圆点强调色（[AccentOverlayArgb]）
 * 会重新变成整套配色里仅有的两块固定色，看着会突兀。
 *
 * [darkTheme] 仍然跟着系统走：深浅是用户对光线的偏好，不该由我们替他决定。
 */
@Composable
fun ExplainDotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography,
        content = content
    )
}
