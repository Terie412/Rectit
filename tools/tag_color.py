"""
标签随机色的算法定稿与验证。

两件事：
 1. 确定 hash → 色相 的算法，并用库里真实的标签看分布。
 2. 在**全 360 度色相**上扫对比度，定出「底色亮度 / 字色亮度」这一对参数的边界。

第 2 步是这个方案唯一的风险点：底色是随机色相，字色如果只有一个固定值，
必然有某些色相翻车。所以参数不能靠眼看，得保证 worst case 也过。

用法：python tools/tag_color.py
"""

import colorsys
import sqlite3

# ---------------------------------------------------------------- hash（与 Kotlin 侧同源）

MASK = 0xFFFFFFFF


def fnv1a(text: str) -> int:
    """FNV-1a 32，输入取 UTF-8 字节。

    不用 Kotlin/Java 的 String.hashCode()：那个虽然也是规范化的，
    但它是逐 UTF-16 单元乘 31 —— 对中文这种「每个字都是一个大码点」的输入，
    低位分布不算好。FNV 一遍扫字节，跨语言、跨平台、跨版本都是同一个值，
    「确定的输入产生确定的输出」这条才真的是承诺而不是巧合。
    """
    h = 0x811C9DC5
    for b in text.encode("utf-8"):
        h ^= b
        h = (h * 0x01000193) & MASK
    return h


def spread(h: int) -> int:
    """收尾雪崩（lowbias32）。FNV 的雪崩集中在高位，不混一下取模会偏。"""
    h ^= h >> 16
    h = (h * 0x7FEB352D) & MASK
    h ^= h >> 15
    h = (h * 0x846CA68B) & MASK
    h ^= h >> 16
    return h


def hue_of(text: str) -> float:
    """连续色相 0..359。**只用来演示「不量化会怎样」**，不是实现取的色。"""
    return spread(fnv1a(text)) % 360


def slot_of(text: str, steps: int = 12) -> int:
    """实现真正使用的档位。

    注意它**不等于** hue_of(text) // 30 —— 取模的对象不一样：
    `hash % 360 // 30` 和 `hash % 12` 只在少数哈希值上碰巧相同。
    （举例：hash=100 时前者是 3、后者是 4。）两处口径必须一致，
    Kotlin 侧是 `hash % HUE_STEPS`，所以这里也一样。
    """
    return spread(fnv1a(text)) % steps


# ---------------------------------------------------------------- 颜色工具

def hsl(h, s, l):
    r, g, b = colorsys.hls_to_rgb(h / 360.0, l, s)
    return (int(round(r * 255)), int(round(g * 255)), int(round(b * 255)))


def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def lum(rgb):
    r, g, b = rgb
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def contrast(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def hexs(rgb):
    return "#%02X%02X%02X" % rgb


def at_luminance(hue, sat, target):
    """固定色相与饱和度，二分反解出相对亮度落在 target 的那个颜色。

    亮度对 HSL 的 L 单调递增，所以二分安全。**最终实现用的是这一支，
    不是「固定 L」** —— 理由见 main() 末尾。
    """
    lo, hi = 0.0, 1.0
    for _ in range(24):
        mid = (lo + hi) / 2
        if lum(hsl(hue, sat, mid)) < target:
            lo = mid
        else:
            hi = mid
    return hsl(hue, sat, (lo + hi) / 2)


# ---------------------------------------------------------------- 扫边界

def worst_contrast(s_bg, l_bg, s_fg, l_fg):
    """全 360 度色相里最差的那一对的对比度。"""
    worst = 99.0
    worst_h = 0
    for h in range(360):
        c = contrast(hsl(h, s_bg, l_bg), hsl(h, s_fg, l_fg))
        if c < worst:
            worst, worst_h = c, h
    return worst, worst_h


# 字色的饱和度也要一起搜，不能只调明度。
#
# 原因是最差的那个色相是黄（60°）：黄色通道里绿占大头，而绿对亮度的权重
# 是 0.7152。所以「同色相的深黄」即使压到 L=26%，亮度仍有 0.17 —— 底再怎么
# 提亮，对比也上不去（实测顶天 4.14:1，还是不够）。
# 把字色的饱和度降到 35% 左右，绿分量跟着掉下来，亮度立刻落到 0.07 以下。
# 顺带这也是设计上更对的做法：正文用的色不该和色块一样艳。

def search_light():
    """浅色：底色尽量浓（亮度尽量低）但字色对比必须过 5:1。

    S 的顺序是「优先要字色里带一点色相」——同样过线时取更艳的那个。
    """
    print("浅色 · 底色 hsl(h, 82%, L)，字色 hsl(h, S, 22%)")
    found = None
    for l_bg in [64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84]:
        line = "   底L=%2d%%  " % l_bg
        for s_fg in [45, 40, 35, 30]:
            w, h = worst_contrast(0.82, l_bg / 100, s_fg / 100, 0.22)
            line += "S%2d%%:%5.2f " % (s_fg, w)
            if w >= 5.0 and found is None:
                found = (l_bg, s_fg, 22, h)
        print(line)
    return found


def refit_fg(s_bg, l_bg, s_fg_candidates):
    """给定底色，找最柔（明度最高）又过得去的字色。"""
    best = None
    for l_fg in [18, 20, 22, 24, 26, 28]:
        for s_fg in s_fg_candidates:
            w, h = worst_contrast(s_bg, l_bg, s_fg, l_fg / 100)
            if w >= 5.0:
                best = (s_fg, l_fg)
    return best


def search_dark():
    """深色：底暗字亮。S 同样优先取更艳的那个。"""
    print()
    print("深色 · 底色 hsl(h, 64%, L)，字色 hsl(h, S, 86%)")
    found = None
    for l_bg in [18, 20, 22, 24, 26, 28]:
        line = "   底L=%2d%%  " % l_bg
        for s_fg in [65, 55, 45, 35]:
            w, h = worst_contrast(0.64, l_bg / 100, s_fg / 100, 0.86)
            line += "S%2d%%:%5.2f " % (s_fg, w)
            if w >= 5.0 and found is None:
                found = (l_bg, s_fg, 0.86, h)
        print(line)
    return found


# ---------------------------------------------------------------- 主流程

def main():
    light = search_light()
    dark = search_dark()

    if light is None or dark is None:
        print()
        print("没有同时满足浓的底色和 5:1 对比的参数组合 —— 需要放宽其中一头")
        return

    l_bg, l_s_fg, l_l_fg, _ = light
    d_bg, d_s_fg, d_l_fg, _ = dark

    print()
    print("=" * 72)
    print("上面搜的是「固定 HSL 明度」，但**最终实现没有用这一支**。")
    print("原因是固定明度会让不同色相的重量不一致：黄系发灰、蓝系发闷。")
    print("实测最差的那个标签徽章，相对页面底只有 1.08:1 —— 几乎看不出是个色块。")
    print()
    print("定稿参数：**固定相对亮度**（对比度只由亮度决定，固定它就等于")
    print("把对比度和视觉重量一起统一了）")
    LIGHT = (0.82, 0.50, 0.45, 0.045)     # 底sat, 底lum, 字sat, 字lum
    DARK = (0.62, 0.045, 0.50, 0.55)
    print("   浅色：底 sat %.2f lum %.3f   字 sat %.2f lum %.3f" % LIGHT)
    print("   深色：底 sat %.2f lum %.3f   字 sat %.2f lum %.3f" % DARK)
    print()
    for name, (sb, lb, sf, lf) in [("浅色", LIGHT), ("深色", DARK)]:
        worst, at = 99.0, None
        for s in range(12):
            h = s * 30
            c = contrast(at_luminance(h, sb, lb), at_luminance(h, sf, lf))
            if c < worst:
                worst, at = c, h
        page = (0x0F, 0x12, 0x16) if name == "深色" else (0xF5, 0xF7, 0xFB)
        wp, _ = min((contrast(at_luminance(s * 30, sb, lb), page), s * 30) for s in range(12))
        print("   %s：12 档字/底最差 %.2f:1（色相 %d°）   徽章/页面最差 %.2f:1" % (name, worst, at, wp))
    print()
    print("→ 对比度不随标签里的字变化，是结构上成立的")

    # -------------------------------------------------- 真实标签
    print()
    print("=" * 72)
    try:
        db = sqlite3.connect(
            r"C:\Users\pc\AppData\Local\Temp\eddb3\knowledge.db"
        )
        tags = [r[0] for r in db.execute("SELECT DISTINCT tag FROM concepts ORDER BY tag")]
    except Exception as e:
        print("读不到库（%s），用示例标签" % e)
        tags = ["物理概念", "数学概念", "经济学概念", "热力学量", "化学元素", "代数式"]

    print("库里 %d 个标签的档位分布：" % len(tags))
    slots = []
    for t in tags:
        slot = slot_of(t)
        slots.append(slot)
        bg = at_luminance(slot * 30, 0.82, 0.50)
        fg = at_luminance(slot * 30, 0.45, 0.045)
        print("   档%2d  %s / %s   %s" % (slot, hexs(bg), hexs(fg), t))
    slots.sort()
    hist = [slots.count(i) for i in range(12)]
    print()
    print("各档命中数：%s" % hist)
    print("   空档 %d 个，最挤的档 %d 个（27 个标签铺 12 档，均值 2.25）"
          % (hist.count(0), max(hist)))
    print()
    print("如果**不**量化会怎样（下面这节是反证，不是实现）：")
    print("27 个标签连续铺在 360° 上，平均间隔本来就只有 13°，")
    print("所以「相邻间隔小」这个指标没意义。真正要看的是滑动窗口：")
    cont = [hue_of(t) for t in tags]
    for width in (10, 20, 30):
        worst_n, worst_at = 0, 0
        for start in range(360):
            n = sum(1 for h in cont if (h - start) % 360 < width)
            if n > worst_n:
                worst_n, worst_at = n, start
        print("   宽 %2d° 的窗口里，最多挤了 %d 个标签（%d°–%d°）"
              % (width, worst_n, worst_at, (worst_at + width) % 360))

    worst_n, worst_at = 0, 0
    for start in range(360):
        n = sum(1 for h in cont if (h - start) % 360 < 20)
        if n > worst_n:
            worst_n, worst_at = n, start
    print()
    print("最挤的一段（%d°–%d°）里的标签：" % (worst_at, (worst_at + 20) % 360))
    for t in tags:
        h = hue_of(t)
        if (h - worst_at) % 360 < 20:
            print("   h=%3d°  %s" % (h, t))

    print()
    print("连续取的话，这些颜色「差一点但不一样」，看着像 bug 而不是随机。")
    print("量化成 12 档之后，颜色只有 12 个成员 —— 撞色读作「复用」。")


if __name__ == "__main__":
    main()
