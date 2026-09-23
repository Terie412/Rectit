package com.example.explaindot.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 义项标签的底色与字色 —— **由标签文字本身推出来**，不用查表、不落库。
 *
 * 同一个标签永远同一个颜色：换设备、重装、清数据都一样，因为它是文字的
 * 纯函数（哈希 → 色相 → 颜色），没有任何随机数或状态参与。
 *
 * ## 色相怎么来的
 *
 * FNV-1a 扫 UTF-8 字节，过一遍雪崩，再**量化成 12 档**。
 *
 * 为什么不用 `String.hashCode()`：它虽然也是规范化的，但是逐 UTF-16 单元乘 31，
 * 对中文这种「每个字都是一个大码点」的输入低位分布不好。FNV 逐字节扫，
 * 跨语言、跨平台、跨版本都是同一个值，「确定的输入产生确定的输出」才是承诺。
 *
 * 为什么量化而**不**连续取 360°：拿库里 27 个真实标签量过 ——
 * 连续取的话，20° 的窗口里最多挤了 6 个标签（186°–206°，全是浅蓝，
 * 而且和 App 自己的主色撞在一起）。27 个标签铺在 360° 上平均间隔只有 13°，
 * 这种「差一点点但又不一样」的颜色，看着像 bug，不像随机。
 * 12 档之后颜色只有 12 个成员，撞色读作「复用」。
 * 要改回连续取，把 [HUE_STEPS] 换成 0 那一支即可。
 *
 * ## 颜色怎么定的
 *
 * **固定相对亮度，二分反解明度**，而不是固定 HSL 的 L。
 * 对比度只由亮度决定，所以固定亮度等于把「对比度」和「视觉重量」一起统一了：
 * 固定 L 的话，黄系会发灰、蓝系会发闷（实测黄系徽章相对页面只有 1.08:1，
 * 几乎看不出是个色块；固定亮度后是 1.77:1）。
 *
 * 字色也不是「底色的深色版」，而是**同色相、低饱和**的一支：
 * 纯度高的一支当文字太艳，而且黄系压不下去 —— 绿通道对亮度的权重是 0.7152，
 * 深黄在 L=26% 时亮度还有 0.17，底再怎么提亮对比也上不去（顶天 4.14:1）。
 * 把饱和度降到 0.45 左右，绿分量跟着掉，亮度立刻落到 0.045 以下。
 *
 * ## 边界
 *
 * 扫过全 360 度色相（tools/tag_color.py 可复算）：
 * 浅色最差 5.69:1、深色最差 6.24:1，都过 4.5:1；徽章相对页面 1.77:1 / 1.69:1。
 * 也就是说**不管标签里写的是什么字**，这个对比度都是结构上成立的，不靠运气。
 */
internal object TagColor {

    /** 色相档数。12 档 = 每档 30° */
    private const val HUE_STEPS = 12

    // 浅色
    private const val LIGHT_BG_SAT = 0.82f
    private const val LIGHT_BG_LUM = 0.50
    private const val LIGHT_FG_SAT = 0.45f
    private const val LIGHT_FG_LUM = 0.045

    // 深色。底色压到很暗（深色下高饱和必须暗，否则刺眼），字色提亮
    private const val DARK_BG_SAT = 0.62f
    private const val DARK_BG_LUM = 0.045
    private const val DARK_FG_SAT = 0.50f
    private const val DARK_FG_LUM = 0.55

    /** 二分反解的迭代次数。24 次之后 L 的精度远超过 8 位色深 */
    private const val BISECT_STEPS = 24

    /** 标签文字 → 色相档位（0..11）。纯函数 */
    fun slotOf(text: String): Int = (hash(text) % HUE_STEPS).toInt()

    fun background(text: String, dark: Boolean): Color =
        solve(hueOf(text), if (dark) DARK_BG_SAT else LIGHT_BG_SAT,
            if (dark) DARK_BG_LUM else LIGHT_BG_LUM).toColor()

    fun foreground(text: String, dark: Boolean): Color =
        solve(hueOf(text), if (dark) DARK_FG_SAT else LIGHT_FG_SAT,
            if (dark) DARK_FG_LUM else LIGHT_FG_LUM).toColor()

    private fun hueOf(text: String): Float = slotOf(text) * (360f / HUE_STEPS)

    // ------------------------------------------------------------------ 哈希

    /**
     * FNV-1a 32 位上 UTF-8 字节，再过一遍雪崩（lowbias32）。
     *
     * 全程用 Long 装 32 位无符号值：Kotlin 的 Int 是有符号的，
     * 中途一旦变成负数，后面的 `% HUE_STEPS` 会得到负数 —— 那种 bug
     * 只在「哈希值恰好高位为 1」的标签上出现，很难查。
     * 所以取模之前一直留在 Long 里，最后才转出来。
     */
    private fun hash(text: String): Long {
        var h = 0x811C9DC5L
        for (b in text.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFFL)
            h = (h * 0x01000193L) and 0xFFFFFFFFL
        }
        h = h xor (h ushr 16)
        h = (h * 0x7FEB352DL) and 0xFFFFFFFFL
        h = h xor (h ushr 15)
        h = (h * 0x846CA68BL) and 0xFFFFFFFFL
        h = h xor (h ushr 16)
        return h
    }

    // ------------------------------------------------------------------ 颜色

    /**
     * 固定色相与饱和度，二分反解出相对亮度落在 [target] 的那个颜色。
     *
     * 亮度对 HSL 的 L 是单调递增的，所以二分安全。
     * 纯函数、无缓存：一次 24 个来回的浮点运算，一个徽章几百次乘加，
     * 在重组里跑完全无感。真要省的话也只有 12 × 2 种可能，但为它引入
     * 一个可变表不值得 —— 那会让「同一个标签永远同一个颜色」多一个可被改坏的地方。
     */
    private fun solve(hue: Float, sat: Float, target: Double): Rgb {
        var lo = 0f
        var hi = 1f
        repeat(BISECT_STEPS) {
            val mid = (lo + hi) / 2f
            if (hslToRgb(hue, sat, mid).luminance() < target) lo = mid else hi = mid
        }
        return hslToRgb(hue, sat, (lo + hi) / 2f)
    }

    /** HSL → sRGB，分量都是 0..1。就是标准那套 hue2rgb，没有取巧 */
    private fun hslToRgb(hueDeg: Float, sat: Float, light: Float): Rgb {
        val h = (((hueDeg % 360f) + 360f) % 360f) / 360f
        val s = sat.coerceIn(0f, 1f)
        val l = light.coerceIn(0f, 1f)
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q

        fun channel(t0: Float): Float {
            var t = t0
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }

        return Rgb(channel(h + 1f / 3f), channel(h), channel(h - 1f / 3f))
    }

    private class Rgb(val r: Float, val g: Float, val b: Float) {
        fun toColor(): Color = Color(r, g, b)

        /** WCAG 的相对亮度。用 Double 是为了和核算脚本口径一致 */
        fun luminance(): Double {
            fun lin(c: Float): Double {
                val v = c.coerceIn(0f, 1f).toDouble()
                return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        }
    }
}
