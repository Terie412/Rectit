"""
把 27 个真实标签按最终参数渲染成徽章，对比两种参数化，浅深各一套。

要对比的是「怎么定这个颜色」这一层，不是色相：
  A 固定 HSL 明度：底 hsl(h, 82%, 76%)
  B 固定相对亮度：底二分反解，让 lum = 0.50

固定明度的问题在于不同色相「重量」不一样 —— 黄系发灰、蓝系发闷。
对比度只由亮度决定，所以固定亮度等于把对比度和视觉重量一起统一了。

用法：python tools/tag_color_preview.py
"""

import os
import sqlite3

from PIL import Image, ImageDraw, ImageFont

from tag_color import fnv1a, spread, hsl, lum, contrast, at_luminance

STEPS = 12          # 色相量化档数；0 表示连续取 360°

# A 方案：固定 HSL 明度
A_LIGHT_BG = (0.82, 0.76)
A_LIGHT_FG = (0.45, 0.22)
A_DARK_BG = (0.64, 0.21)
A_DARK_FG = (0.55, 0.86)

# B 方案：固定相对亮度
B_LIGHT_BG = (0.82, 0.50)
B_LIGHT_FG = (0.45, 0.045)
B_DARK_BG = (0.62, 0.045)
B_DARK_FG = (0.50, 0.55)

PAGE_LIGHT = (0xF5, 0xF7, 0xFB)
PAGE_DARK = (0x0F, 0x12, 0x16)

DB = os.path.join(
    os.environ.get("TEMP", r"C:\Users\pc\AppData\Local\Temp"), "eddb3", "knowledge.db"
)


def hue_of(tag, steps):
    h = spread(fnv1a(tag))
    return (h % steps) * (360 // steps) if steps else h % 360


def colors_for(hue, dark, method):
    if method == "A":
        sb, lb = A_DARK_BG if dark else A_LIGHT_BG
        sf, lf = A_DARK_FG if dark else A_LIGHT_FG
        return hsl(hue, sb, lb), hsl(hue, sf, lf)
    sb, lb = B_DARK_BG if dark else B_LIGHT_BG
    sf, lf = B_DARK_FG if dark else B_LIGHT_FG
    return at_luminance(hue, sb, lb), at_luminance(hue, sf, lf)


def board(tags, dark, title, font, title_font, method):
    pad, gap_x, gap_y = 20, 8, 8
    page = PAGE_DARK if dark else PAGE_LIGHT
    fg_text = (0xE7, 0xEA, 0xF1) if dark else (0x12, 0x16, 0x1D)

    probe = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    rows, cur, cur_w = [], [], 0
    for t in tags:
        tb = probe.textbbox((0, 0), t, font=font)
        w = tb[2] - tb[0] + 18
        if cur and cur_w + gap_x + w > 760:
            rows.append(cur)
            cur, cur_w = [], 0
        cur.append((t, w))
        cur_w += w + (gap_x if cur_w else 0)
    if cur:
        rows.append(cur)

    hh = pad * 2 + 34 + len(rows) * (28 + gap_y)
    img = Image.new("RGB", (800, hh), page)
    d = ImageDraw.Draw(img)
    d.text((pad, pad), title, font=title_font, fill=fg_text)

    y = pad + 34
    for row in rows:
        x = pad
        for t, w in row:
            bg, fg = colors_for(hue_of(t, STEPS), dark, method)
            tb = d.textbbox((0, 0), t, font=font)
            bw = tb[2] - tb[0] + 18
            bh = tb[3] - tb[1] + 12
            d.rounded_rectangle([x, y, x + bw, y + bh], radius=bh // 2, fill=bg)
            d.text((x + 9, y + 5 - tb[1] + 1), t, font=font, fill=fg)
            x += w + gap_x
        y += 28 + gap_y
    return img


def main():
    try:
        db = sqlite3.connect(DB)
        tags = [r[0] for r in db.execute("SELECT DISTINCT tag FROM concepts ORDER BY tag")]
    except Exception as e:
        print("读不到库（%s），用示例标签" % e)
        tags = ["物理概念", "数学概念", "经济学概念", "热力学量"]

    try:
        font = ImageFont.truetype("msyh.ttc", 21)
        title_font = ImageFont.truetype("msyh.ttc", 22)
    except Exception:
        font = title_font = ImageFont.load_default()

    boards = [
        board(tags, False, "浅色 · 最终方案（固定相对亮度，色相 12 档）", font, title_font, "B"),
        board(tags, True, "深色 · 最终方案", font, title_font, "B"),
        board(tags, False, "对照：如果固定 HSL 明度（黄系会发灰）", font, title_font, "A"),
        board(tags, True, "对照：如果固定 HSL 明度", font, title_font, "A"),
    ]

    gap = 16
    W = max(b.width for b in boards)
    H = sum(b.height for b in boards) + gap * (len(boards) + 1)
    sheet = Image.new("RGB", (W, H), (246, 246, 248))
    y = gap
    for b in boards:
        sheet.paste(b, (0, y))
        y += b.height + gap

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tag_colors_preview.png")
    sheet.save(out)
    print("已生成", out, sheet.size)
    print()

    for name, method, dark in [
        ("浅色 A 固定明度", "A", False), ("浅色 B 固定亮度", "B", False),
        ("深色 A 固定明度", "A", True), ("深色 B 固定亮度", "B", True),
    ]:
        page = PAGE_DARK if dark else PAGE_LIGHT
        ws = wa = ps = pa = None
        for t in tags:
            bg, fg = colors_for(hue_of(t, STEPS), dark, method)
            c, c2 = contrast(fg, bg), contrast(bg, page)
            if ws is None or c < ws:
                ws, wa = c, t
            if ps is None or c2 < ps:
                ps, pa = c2, t
        print("  %-14s 字/底最差 %5.2f:1（%s）   底/页面最差 %5.2f:1（%s）"
              % (name, ws, wa, ps, pa))


if __name__ == "__main__":
    main()
