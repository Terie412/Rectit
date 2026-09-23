"""新配色（图标同源）的最终核算。选定值全部过 WCAG 之后才写进 Color.kt。"""

import colorsys


def srgb_to_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def lum(hexs):
    h = hexs.lstrip("#")
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return 0.2126 * srgb_to_lin(r) + 0.7152 * srgb_to_lin(g) + 0.0722 * srgb_to_lin(b)


def contrast(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def hsv(hexs):
    h = hexs.lstrip("#")
    r, g, b = int(h[0:2], 16) / 255, int(h[2:4], 16) / 255, int(h[4:6], 16) / 255
    hh, ss, vv = colorsys.rgb_to_hsv(r, g, b)
    return hh * 360, ss * 100, vv * 100


# ------------------------------------------------------------------ 选定的浅色

L = dict(
    background="#F5F7FB", surface="#FFFFFF", surfaceVariant="#ECEFF6",
    ink="#12161D", inkMuted="#575F6E", outline="#79818F", outlineVariant="#C6CFDC",
    primary="#3A5CD4", onPrimary="#FFFFFF",
    primaryContainer="#E1E8FC", onPrimaryContainer="#1A2A5E",
    secondaryContainer="#D5F3F0", onSecondaryContainer="#0C4F4B",
    tertiary="#116A63",
    error="#B3261E", errorContainer="#FBE4E2", onErrorContainer="#5C1714",
    linkNew="#B45309",
)

# ------------------------------------------------------------------ 选定的深色

D = dict(
    background="#0F1216", surface="#171B21", surfaceVariant="#232833",
    text="#E7EAF1", textMuted="#A3ABB8", outline="#767E8C", outlineVariant="#3D4552",
    primary="#7C9CF5", onPrimary="#10204A",
    primaryContainer="#2A3C6E", onPrimaryContainer="#DCE3FA",
    secondaryContainer="#17433F", onSecondaryContainer="#BEE9E5",
    tertiary="#63C7BD",
    error="#F2A6A1", errorContainer="#57231F", onErrorContainer="#F7DEDB",
    linkNew="#EF9F27",
)

fail = []


def check(label, fg, bg, need):
    c = contrast(fg, bg)
    ok = c >= need
    if not ok:
        fail.append(label)
    h, s, v = hsv(fg)
    print(f"  {'OK ' if ok else '!! '}{label:<34} {fg} on {bg}   {c:5.2f}:1  需{need}   色相{h:3.0f}° 饱和{s:3.0f}%")


print("=== 浅色 ===")
check("正文 / 面", L["ink"], L["surface"], 7.0)
check("正文 / 页底", L["ink"], L["background"], 7.0)
check("次级文字 / 面", L["inkMuted"], L["surface"], 4.5)
check("次级文字 / 填充", L["inkMuted"], L["surfaceVariant"], 4.5)
check("强调色（链接）/ 面", L["primary"], L["surface"], 4.5)
check("强调色（链接）/ 页底", L["primary"], L["background"], 4.5)
check("白字 / 强调色（按钮）", L["onPrimary"], L["primary"], 4.5)
check("标签字 / 标签底", L["onSecondaryContainer"], L["secondaryContainer"], 4.5)
check("缓存标记字 / 底", L["onPrimaryContainer"], L["primaryContainer"], 4.5)
check("分隔线 / 面（非文字，需 1.5）", L["outlineVariant"], L["surface"], 1.5)
check("描边 / 面（非文字，需 3）", L["outline"], L["surface"], 3.0)
check("错误色 / 面", L["error"], L["surface"], 4.5)
check("错误字 / 底", L["onErrorContainer"], L["errorContainer"], 4.5)
check("第二色链接（琥珀）/ 面", L["linkNew"], L["surface"], 4.5)

print()
print("=== 深色 ===")
check("正文 / 面", D["text"], D["surface"], 7.0)
check("正文 / 页底", D["text"], D["background"], 7.0)
check("次级文字 / 面", D["textMuted"], D["surface"], 4.5)
check("次级文字 / 填充", D["textMuted"], D["surfaceVariant"], 4.5)
check("强调色 / 面", D["primary"], D["surface"], 4.5)
check("强调色 / 页底", D["primary"], D["background"], 4.5)
check("深字 / 强调色（按钮）", D["onPrimary"], D["primary"], 4.5)
check("标签字 / 标签底", D["onSecondaryContainer"], D["secondaryContainer"], 4.5)
check("缓存标记字 / 底", D["onPrimaryContainer"], D["primaryContainer"], 4.5)
check("分隔线 / 面", D["outlineVariant"], D["surface"], 1.5)
check("描边 / 面", D["outline"], D["surface"], 3.0)
check("错误色 / 面", D["error"], D["surface"], 4.5)
check("第二色链接 / 面", D["linkNew"], D["surface"], 4.5)

print()
print("=== 两种链接色是否拉得开 ===")
for tag, a, b in [("浅色", L["primary"], L["linkNew"]), ("深色", D["primary"], D["linkNew"])]:
    d = abs(hsv(a)[0] - hsv(b)[0])
    d = min(d, 360 - d)
    la, lb = hsv(a)[1], hsv(b)[1]
    print(f"  {tag}：色相相差 {d:3.0f}°（每支需 >30°），饱和度 {la:.0f}% vs {lb:.0f}%")

print()
print("=== 和图标锚点的关系 ===")
for n, c in [("图标紫", "#824FEB"), ("图标蓝", "#4872EA"), ("图标青", "#18DED2")]:
    h, s, v = hsv(c)
    print(f"  {n} {c}  色相{h:3.0f}°")
for n, c in [("浅色强调色", L["primary"]), ("标签底色（青）", L["secondaryContainer"]),
             ("深色强调色", D["primary"]), ("圆点强调色", "#4A80F0")]:
    h, s, v = hsv(c)
    cw = contrast(c, "#FFFFFF")
    print(f"  {n:<16} {c}  色相{h:3.0f}° 饱和{s:3.0f}%")

print()
print("=" * 60)
print("全部通过 ✓" if not fail else f"未通过：{fail}")
