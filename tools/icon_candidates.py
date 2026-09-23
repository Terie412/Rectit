"""
生成图标配色候选，拼成一张对比图供挑选。

这不是构建脚本，是一次性的设计探索工具：改完颜色定了之后，
把选中的那张另存为 tools/icon-source.png，再跑 build_icon.py 出全套资源。

约束来自 build_icon.py：源图必须是**白底 + 图形**，
因为它是靠「离白距离」量内容包围盒、推单色层 alpha 的。
"""

import os

from PIL import Image, ImageDraw, ImageFont

SS = 4                      # 超采样倍数，抗锯齿
CANVAS = 1024
INK = (0x2C, 0x4A, 0x78)    # PaperPrimary 深靛蓝
INK_LIGHT = (0x5B, 0x95, 0xE0)  # AccentOverlayArgb 亮靛蓝
PAPER = (0xF4, 0xF1, 0xEA)
DARK = (0x12, 0x11, 0x10)


def new_canvas():
    return Image.new("RGB", (CANVAS * SS, CANVAS * SS), (255, 255, 255))


def save(img, name):
    out = img.resize((CANVAS, CANVAS), Image.LANCZOS)
    out.save(name)
    return out


def rounded_rect_path(box, r, steps=64):
    """圆角矩形的边界折线，从左上角起顺时针。用来画虚线。"""
    x0, y0, x1, y1 = box
    pts = []
    # 四个圆角的圆心
    corners = [
        (x1 - r, y0 + r, -90, 0),    # 右上
        (x1 - r, y1 - r, 0, 90),     # 右下
        (x0 + r, y1 - r, 90, 180),   # 左下
        (x0 + r, y0 + r, 180, 270),  # 左上
    ]
    import math
    for cx, cy, a0, a1 in corners:
        for i in range(steps + 1):
            a = math.radians(a0 + (a1 - a0) * i / steps)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def draw_dashed(draw, pts, width, color, dash, gap):
    """沿折线画虚线。dash/gap 是长度。"""
    import math
    acc = 0.0
    on = True
    remain = dash
    for i in range(len(pts) - 1):
        x0, y0 = pts[i]
        x1, y1 = pts[i + 1]
        seg = math.hypot(x1 - x0, y1 - y0)
        if seg <= 0:
            continue
        t = 0.0
        while t < seg:
            step = min(remain, seg - t)
            if on:
                ax = x0 + (x1 - x0) * (t / seg)
                ay = y0 + (y1 - y0) * (t / seg)
                bx = x0 + (x1 - x0) * ((t + step) / seg)
                by = y0 + (y1 - y0) * ((t + step) / seg)
                draw.line([ax, ay, bx, by], fill=color, width=width)
            t += step
            remain -= step
            if remain <= 1e-6:
                on = not on
                remain = dash if on else gap


# --------------------------------------------------------------------------- 候选

def cand_flat_ai():
    """保留原「Ai」字母，只把霓虹渐变换成平涂靛蓝。"""
    src = Image.open(os.path.join(os.path.dirname(__file__), "icon-source.png")).convert("RGB")
    if src.width != src.height:
        s = min(src.width, src.height)
        o = ((src.width - s) // 2, (src.height - s) // 2)
        src = src.crop((o[0], o[1], o[0] + s, o[1] + s))
    src = src.resize((CANVAS, CANVAS), Image.LANCZOS)

    px = src.load()
    out = Image.new("RGB", (CANVAS, CANVAS), (255, 255, 255))
    op = out.load()
    for y in range(CANVAS):
        for x in range(CANVAS):
            R, G, B = px[x, y]
            dist = max(255 - R, 255 - G, 255 - B)
            if dist <= 6:
                continue                       # 背景
            a = min(1.0, (dist - 6) / 60.0)    # 保留抗锯齿过渡
            op[x, y] = tuple(
                int(255 + (c - 255) * a) for c in INK
            )
    return out


def _frame_and_dot(stroke, dot_r, dashed=False, dot_color=INK, frame_color=INK):
    img = new_canvas()
    d = ImageDraw.Draw(img)
    inset, radius = 150 * SS, 150 * SS
    box = (inset, inset, CANVAS * SS - inset, CANVAS * SS - inset)
    if dashed:
        draw_dashed(d, rounded_rect_path(box, radius), stroke, frame_color,
                    dash=74 * SS, gap=46 * SS)
    else:
        d.rounded_rectangle(box, radius=radius, outline=frame_color, width=stroke)
    c = CANVAS * SS // 2
    d.ellipse((c - dot_r, c - dot_r, c + dot_r, c + dot_r), fill=dot_color)
    return img


def cand_frame_dot():
    return _frame_and_dot(stroke=68 * SS, dot_r=145 * SS)


def cand_frame_dot_two_tone():
    return _frame_and_dot(stroke=68 * SS, dot_r=145 * SS, dot_color=INK_LIGHT)


def cand_frame_dot_dashed():
    return _frame_and_dot(stroke=46 * SS, dot_r=145 * SS, dashed=True)


def cand_crop_marks():
    """四角括号 + 圆点。括号是「框选范围」最轻的画法。"""
    img = new_canvas()
    d = ImageDraw.Draw(img)
    inset, length, w = 150 * SS, 230 * SS, 62 * SS
    a, b = inset, CANVAS * SS - inset
    for (cx, cy, dx, dy) in [(a, a, 1, 1), (b, a, -1, 1), (a, b, 1, -1), (b, b, -1, -1)]:
        d.line([cx, cy, cx + dx * length, cy], fill=INK, width=w)
        d.line([cx, cy, cx, cy + dy * length], fill=INK, width=w)
    c = CANVAS * SS // 2
    r = 145 * SS
    d.ellipse((c - r, c - r, c + r, c + r), fill=INK)
    return img


def cand_drag_from_dot():
    """
    从圆点拖出选框：圆点落在左上角，右下三边画完整的框。

    叙事最贴 App 的实际手势 —— 长按圆点、往右下拖、松手定框。
    代价是它不对称，而图标在桌面上一排邻居里得靠轮廓认，不对称的形更难认。
    """
    img = new_canvas()
    d = ImageDraw.Draw(img)
    inset, radius, w = 150 * SS, 150 * SS, 62 * SS
    a, b = inset, CANVAS * SS - inset
    r = 128 * SS          # 圆点半径
    cxx, cyy = a + r, a + r

    # 右下三边：从圆点右侧起画到左下
    d.line([a + r * 2.1, a, b, a], fill=INK, width=w)          # 上边右段
    d.line([b, a, b, b], fill=INK, width=w)                     # 右边
    d.line([b, b, a, b], fill=INK, width=w)                     # 下边
    d.line([a, b, a, a + r * 2.1], fill=INK, width=w)           # 左边下段
    d.ellipse((cxx - r, cyy - r, cxx + r, cyy + r), fill=INK)
    return img


# --------------------------------------------------------------------------- 拼版

def main():
    here = os.path.dirname(os.path.abspath(__file__))
    src = Image.open(os.path.join(here, "icon-source.png")).convert("RGB")
    if src.width != src.height:
        s = min(src.width, src.height)
        o = ((src.width - s) // 2, (src.height - s) // 2)
        src = src.crop((o[0], o[1], o[0] + s, o[1] + s))

    items = [
        ("现在（霓虹渐变）", src),
        ("A 保留 Ai · 平涂靛蓝", cand_flat_ai()),
        ("B 框 + 圆点", save(cand_frame_dot(), os.path.join(here, "candidate_B_frame_dot.png"))),
        ("E 四角括号 + 圆点", save(cand_crop_marks(), os.path.join(here, "candidate_E_crop_marks.png"))),
        ("F 从圆点拖出选框", save(cand_drag_from_dot(), os.path.join(here, "candidate_F_drag_from_dot.png"))),
        ("D 虚线框 + 圆点", save(cand_frame_dot_dashed(), os.path.join(here, "candidate_D_dashed_frame.png"))),
    ]

    big, small = 200, 52
    pad, label_h = 24, 30
    col_w = big + small * 2 + pad * 4
    sheet = Image.new("RGB", (col_w + pad * 2, (len(items)) * (big + label_h + pad) + pad),
                      (250, 249, 246))
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("msyh.ttc", 20)
    except Exception:
        font = ImageFont.load_default()

    for i, (name, im) in enumerate(items):
        y = pad + i * (big + label_h + pad)
        d.text((pad, y), name, fill=(30, 28, 25), font=font)
        y += label_h
        sheet.paste(im.resize((big, big), Image.LANCZOS), (pad, y))
        # 小尺寸：浅色底 + 深色底，看两套主题下是否都立得住
        sheet.paste(im.resize((small, small), Image.LANCZOS), (pad + big + pad, y))
        tile = Image.new("RGB", (small, small), DARK)
        tile.paste(im.resize((small, small), Image.LANCZOS), (0, 0))
        sheet.paste(tile, (pad + big + pad * 2 + small, y))

    out = os.path.join(here, "_icon_candidates.png")
    sheet.save(out)
    print("已生成", out)
    print("尺寸", sheet.size)


if __name__ == "__main__":
    main()
