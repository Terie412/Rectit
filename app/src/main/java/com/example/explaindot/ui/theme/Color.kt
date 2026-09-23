package com.example.explaindot.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 全套配色。方向是「从图标取色」。
 *
 * **为什么是这个方向。** 应用图标是一枚紫→蓝→青的渐变徽标，用户认可它。
 * 那么配色就不该是另起一套审美，而应该**从图标里把色相族取出来**，
 * 铺成一套克制的界面色。现在整套颜色的来源只有一个，就是那张图。
 *
 * 图标量出来的三个锚点（见 tools/check_palette.py 可复算）：
 *
 * | 锚点 | 色相 | 用在哪儿 |
 * |---|---|---|
 * | 紫 #824FEB | 260° | 未直接用。它的存在说明图标是「冷色三档」而不是单色 |
 * | 蓝 #4872EA | 224° | **主色**。取同色相、压暗到能当正文链接用 |
 * | 青 #18DED2 | 176° | **标签底色**。原色太亮（对白仅 1.69:1），只取它做浅底 |
 *
 * **主色为什么不直接用图标那个蓝。** #4872EA 对白只有 4.31:1，当正文里的链接
 * 不够（需 4.5:1）。所以保色相、压亮度取 [BrandPrimary]，色相 227°、对比 5.74:1。
 * 视觉上仍是同一支蓝，只是能读了。
 *
 * **一条规则贯穿全局：强调色 = 可以点。** 链接、底栏四个图标、字母组头、
 * 工具按钮、删除这些动作，全用同一支蓝；其余一律走中性阶。
 * 用户不用记「什么颜色代表什么」——有颜色就是能按，没颜色就是内容。
 *
 * **两处中性阶都带一点点蓝**（色相 217–222°、饱和度 6–22%）。这一点点很重要：
 * 纯灰（饱和度 0）摆在蓝主色旁边会显脏、显旧；而饱和度过 30% 又会变成
 * 「淡紫灰」，那是 Material 模板的默认味道。6–22% 是能感觉到「同一族」、
 * 又不会觉得「有色」的那一段。
 */

// ---------------------------------------------------------------------------- 浅色：白与光

/**
 * 页面底：极浅的冷灰，不是纯白。
 *
 * 纯白留给 [BrandSurface]（卡片、正文面）—— 底比面暗一档，面才能浮起来。
 * 反过来（底白、面灰）会让整页看着像没擦干净。
 */
val BrandBackground = Color(0xFFF5F7FB)
val BrandSurface = Color(0xFFFFFFFF)

/** 填充色：缩略图底、搜索框、提示条、次级按钮。比页面底再暗一点点 */
val BrandSurfaceVariant = Color(0xFFECEFF6)

val BrandText = Color(0xFF12161D)

/**
 * 次级文字与图标。**全项目 36 处 `onSurfaceVariant` 都取这一支** ——
 * 改这里等于一次性换掉所有次级文字、未被选中的图标和提示文字。
 */
val BrandTextMuted = Color(0xFF575F6E)

val BrandOutline = Color(0xFF79818F)

/**
 * 分隔线。**不是越淡越好。**
 *
 * 初版取 #DCE1EA，对白实测只有 1.31:1 —— 0.5dp 的线在这个对比度下基本看不见。
 * 调到 1.57:1，和上一版纸底上那条的感知重量相当（那条压在 #F4F1EA 上，
 * 比压在纯白上更容易被看见，所以这里要略深一点才等效）。
 *
 * 再深会变成一条实体线，把版面切成块。分隔线该是「一条缝隙」，不是「一堵墙」。
 */
val BrandOutlineVariant = Color(0xFFC6CFDC)

/** 主色。取自图标的蓝，压暗到能当正文链接（对白 5.74:1） */
val BrandPrimary = Color(0xFF3A5CD4)
val BrandOnPrimary = Color(0xFFFFFFFF)

/** 缓存标记（「已存过」）的浅蓝底 */
val BrandPrimaryContainer = Color(0xFFE1E8FC)
val BrandOnPrimaryContainer = Color(0xFF1A2A5E)

/**
 * 次要容器色 —— 图标的**青**。
 *
 * **当前没有直接用到。** 义项标签已经改成按标签文字自己推色（见 [TagColor]），
 * 知识库的选中行也回到蓝族（那才是「当前位置」该有的颜色）。
 *
 * 留着是因为 M3 组件会拿 secondary 这一族当默认强调色 ——
 * 不给值它们会回落到基线紫。取图标的青，和整套配色同族。
 *
 * 只取青做**浅底**：原色 #18DED2 对白只有 1.69:1，当文字或线条都不够，
 * 做底正好。字用 [BrandOnSecondaryContainer]（同色相压暗到 8:1）。
 */
val BrandSecondaryContainer = Color(0xFFD5F3F0)
val BrandOnSecondaryContainer = Color(0xFF0C4F4B)

/**
 * tertiary 是「第三种强调」的位置。当前没有直接用，但 M3 组件会拿它当默认色，
 * 所以给了同族的青，免得它回落到基线紫。
 *
 * **注意它不能当第二种链接色用** —— 它和主色只差 50° 左右，两者都会读成「蓝」，
 * 拉不开距离。第二色链接另见 [LinkNewLight]，原因写在那里。
 */
val BrandTertiary = Color(0xFF116A63)

val BrandError = Color(0xFFB3261E)
val BrandErrorContainer = Color(0xFFFBE4E2)
val BrandOnErrorContainer = Color(0xFF5C1714)

// ---------------------------------------------------------------------------- 深色：夜

/**
 * 深色不是把浅色反过来，两套各自单独调过对比度。
 *
 * 底色不是纯黑（#000 会让所有冷色显得脏），而是带一点蓝的近黑；
 * 强调色必须提亮（那支蓝在近黑底上会糊成黑），但色相保持在 224° ——
 * 和图标、和浅色主色都是同一族。
 */
val NightBackground = Color(0xFF0F1216)
val NightSurface = Color(0xFF171B21)
val NightSurfaceVariant = Color(0xFF232833)

val NightText = Color(0xFFE7EAF1)
val NightTextMuted = Color(0xFFA3ABB8)
val NightOutline = Color(0xFF767E8C)
val NightOutlineVariant = Color(0xFF3D4552)

val NightPrimary = Color(0xFF7C9CF5)
val NightOnPrimary = Color(0xFF10204A)
val NightPrimaryContainer = Color(0xFF2A3C6E)
val NightOnPrimaryContainer = Color(0xFFDCE3FA)

val NightSecondaryContainer = Color(0xFF17433F)
val NightOnSecondaryContainer = Color(0xFFBEE9E5)

val NightTertiary = Color(0xFF63C7BD)

val NightError = Color(0xFFF2A6A1)
val NightErrorContainer = Color(0xFF57231F)
val NightOnErrorContainer = Color(0xFFF7DEDB)

/**
 * 「模型提到、但本地还没解释过」那种链接的颜色 —— 界面里第二种链接色。
 *
 * **为什么这套冷色配色里要留一支暖色。** 因为这两支色要承担的不是好看，
 * 而是一个必须能被看见的区别：点蓝的不用花钱，点这个要现问一次模型。
 *
 * 所以它必须离主色足够远。算过三种候选：
 * - 图标的青（176°）：和主色只差 50°，两者都读成蓝，等于没区别；
 * - 图标的紫（260°）：和主色差 33°，处在临界线上，小字号下不可靠；
 * - **琥珀（26°）：差 159°**，任何底色上都拉得开。
 *
 * 而且它顺带解决了另一个问题：一套全冷的界面，所有元素都在同一个色温里，
 * 看久了发闷。一支暖色恰好是那个「唯一需要你付出点什么」的提示 ——
 * 冷暖的对比就是语义的对比，不是装饰。
 */
val LinkNewLight = Color(0xFFB45309)
val LinkNewDark = Color(0xFFEF9F27)

/**
 * 悬浮圆点和框选遮罩用的强调色（裸 ARGB，因为那两个是自绘的 View，
 * 读不到 Compose 的主题）。
 *
 * **比主题主色亮，这是有意的。** 这两样东西压在**别人家的内容**上 ——
 * 可能是白底网页，也可能是一张夜景照片，主题里那支主色在暗内容上会糊掉。
 * 所以取同色相的亮档（220°），既和 App 内部是同一支蓝，又保证在任何背景上都看得见。
 */
val AccentOverlayArgb: Int = 0xFF4A80F0.toInt()
