"""
把「深色主体 + 浅色字形」的图标重绘成「主体渐变」。

和 tools/gradient_dot.py 的分工：

    gradient_dot   吃「RGB 全白、形状完全靠 alpha 描述」的图。整块都是主体，直接填。
    gradient_icon  吃「深色主体里挖出一个浅色字形」的图 —— 比如 app_icon.png 那种
                   黑色圆角方 + 白色 R。要先把主体和字形分开，只填主体。

三个不显然的地方，写在这里免得以后有人照直觉改回去：

1. **字形不能只用阈值切，要用连续的覆盖率。**

   主体是黑、字形是白，两者交界上有一圈中间调的像素（这张图上约 3000 个），
   是抗锯齿混出来的。直接二值化会在字形边缘留下锯齿。改成把「亮度」读作
   「白色的覆盖率」，拿它当混合系数在渐变与白之间插值，边缘过渡就和原图一样顺。
   这也是为什么 [NOISE_FLOOR] 只削掉最低的一小段：主体的黑是带噪声的
   （0~3 的块状压缩痕迹），不削的话整块渐变会被掺进万分之一的白。

2. **形状最外沿要先排除掉一圈，再判断字形。**

   app_icon.png 的最外 1 像素挂着一圈浅色描边（亮度 150~211，alpha 常常是 255），
   那是裁剪时带进来的杂质、不属于设计。如果和字形一起按亮度判断，它会因为
   「够亮」而被当成字形保留成白色 —— 结果就是渐变块的整个外沿镶上一圈白线。
   所以先按 [--trim] 把形状掩码往里收几像素，只在收进来的内部做字形判断，
   外圈一律当主体填渐变。

   实测这张图的白 R 距形状外沿 300 像素以上，收几像素完全碰不到它。
   这个值不需要调得很准：只要「大于杂质厚度（1~2px）、小于主体到字形的距离」，都是对的。

3. **渐变轴归一化到形状，不是画布。**

   理由同 gradient_dot.py：用户说的「左上 / 右下」指的是图形自己的左上右下角。
   按画布对角线归一化的话，形状两端永远取不到纯色，看到的始终是中间色。

另外两个一并处理掉的事：

- **透明区域的 RGB 也一起填渐变**（原图那里是纯黑）。PNG 缩小、旋转时 RGB 是
  带着 alpha 一起插值的，留黑会在边缘渗出灰黑一圈 —— 而这个图标最终要被系统
  缩到 48px 放进托盘，这件事很现实。
- **alpha 通道逐字节不动。** 形状不变，只换颜色。

用法：
    python tools/gradient_icon.py temp/app_icon.png
    python tools/gradient_icon.py temp/app_icon.png -o temp/app_icon_gradient.png \\
        --from 133,79,238 --to 32,246,210 --space oklab --preview
    python tools/gradient_icon.py in.png --glyph gradient     # 字形也一起变渐变
"""

import argparse
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

# 脚本自己所在目录要能 import colorramp（从别处调用本脚本时 sys.path[0] 不是这儿）
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import colorramp  # noqa: E402


# 默认就是 ExplainDot 图标量出来的那两个锚点色（见 tools/check_palette.py）：
# 紫 #854FEE（色相 260°）和青 #20F6D2（色相 170°）。
DEFAULT_FROM = (133, 79, 238)
DEFAULT_TO = (32, 246, 210)

# 低于这个亮度的一律当纯主体（压掉黑底的压缩噪声）
NOISE_FLOOR = 8

# 形状最外沿排除的像素数，见模块 docstring 第 2 条
DEFAULT_TRIM = 3

# 报告里判「这个像素算字形」的覆盖率门槛（只为统计，不参与着色）
GLYPH_REPORT_LEVEL = 0.5


# ---------------------------------------------------------------------------- 量

def luminance(rgb):
    """感知亮度。权重是 Rec.709。用它当「白色覆盖率」的代理，见 docstring 第 1 条。"""
    return (0.2126 * rgb[:, :, 0] + 0.7152 * rgb[:, :, 1] + 0.0722 * rgb[:, :, 2])


def bbox(mask):
    ys, xs = np.nonzero(mask)
    if len(xs) == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())


def describe(mask):
    b = bbox(mask)
    if b is None:
        return "空"
    x0, y0, x1, y1 = b
    return "x[%d,%d] y[%d,%d]  %dx%d" % (x0, x1, y0, y1, x1 - x0 + 1, y1 - y0 + 1)


# ---------------------------------------------------------------------------- 预览

def make_preview(before, after, path, cell=224, pad=16, label_h=26):
    """把原图和新图并排铺在几种底色上，再附两块角落放大。

    透明 PNG 单看是看不出问题的 —— 浅色描边在浅底上会消失，在深底上才会露出来，
    所以必须压到多种底色上各看一遍。
    """
    bgs = [("on white", (255, 255, 255)),
           ("on light #F5F7FB", (0xF5, 0xF7, 0xFB)),
           ("on dark #0F1216", (0x0F, 0x12, 0x16)),
           ("on photo gray", (0x3A, 0x3A, 0x44))]

    cols = [("before", before), ("after", after)]
    row_h = cell + label_h
    W = pad + len(cols) * (cell + pad)
    H = pad + label_h + len(bgs) * row_h + pad
    panel = Image.new("RGB", (W, H), (0x1C, 0x1C, 0x22))
    draw = ImageDraw.Draw(panel)
    try:
        font = ImageFont.load_default(size=15)
    except TypeError:            # 老 Pillow 不支持 size 参数
        font = ImageFont.load_default()

    ink = (0xE7, 0xEA, 0xF1)
    dim = (0x9A, 0xA2, 0xB0)
    for ci, (name, _) in enumerate(cols):
        draw.text((pad + ci * (cell + pad), pad), name, fill=ink, font=font)

    y = pad + label_h
    for bg_name, bg in bgs:
        draw.text((pad, y + cell + 6), bg_name, fill=dim, font=font)
        for ci, (_, img) in enumerate(cols):
            tile = Image.new("RGB", (cell, cell), bg)
            small = img.resize((cell, cell), Image.LANCZOS)
            tile.paste(small, (0, 0), small)
            panel.paste(tile, (pad + ci * (cell + pad), y))
        y += row_h

    # 角落放大：渐变的两端色就落在这儿，顺便看外沿那圈杂质有没有清掉
    Z = 300
    x0, y0, x1, y1 = bbox(np.asarray(after)[:, :, 3] > 0)
    crops = [("top-left corner x4", x0, y0), ("bottom-right corner x4", x1 - Z, y1 - Z)]
    strip_h = Z + label_h
    panel2 = Image.new("RGB", (pad + len(crops) * (Z + pad), strip_h + pad), (0x1C, 0x1C, 0x22))
    d2 = ImageDraw.Draw(panel2)
    for ci, (name, cx, cy) in enumerate(crops):
        c = after.crop((cx, cy, cx + Z, cy + Z))
        # 压在深灰上：外沿的白线/灰线在这个底色下最显眼
        tile = Image.new("RGB", (Z, Z), (0x3A, 0x3A, 0x44))
        tile.paste(c, (0, 0), c)
        panel2.paste(tile, (pad + ci * (Z + pad), label_h))
        d2.text((pad + ci * (Z + pad), 4), name, fill=ink, font=font)

    out = Image.new("RGB", (max(panel.width, panel2.width), panel.height + panel2.height),
                    (0x1C, 0x1C, 0x22))
    out.paste(panel, (0, 0))
    out.paste(panel2, (0, panel.height))
    out.save(path)
    return out.size


# ---------------------------------------------------------------------------- 主流程

def main():
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    ap = argparse.ArgumentParser(
        description="把「深色主体 + 浅色字形」的图标重绘成主体渐变")
    ap.add_argument("src", help="输入 PNG")
    ap.add_argument("-o", "--out", help="输出路径，默认在输入旁边加 _gradient")
    ap.add_argument("--from", dest="c0", help="左上色，如 133,79,238 或 #854FEE")
    ap.add_argument("--to", dest="c1", help="右下色，如 32,246,210 或 #20F6D2")
    ap.add_argument("--space", choices=sorted(colorramp.SPACES), default="srgb",
                    help="插值空间，默认 srgb（和 Compose / CSS 一致）")
    ap.add_argument("--glyph", choices=("keep", "gradient"), default="keep",
                    help="字形保留原样（keep，默认）还是也填成渐变（gradient）")
    ap.add_argument("--trim", type=int, default=DEFAULT_TRIM,
                    help="形状外沿排除的像素数，用来甩掉裁剪杂质（默认 %d）" % DEFAULT_TRIM)
    ap.add_argument("--noise-floor", type=int, default=NOISE_FLOOR,
                    help="低于此亮度算纯主体，压掉黑底噪声（默认 %d）" % NOISE_FLOOR)
    ap.add_argument("--preview", action="store_true", help="另存一张多底色对照图")
    args = ap.parse_args()

    c0 = colorramp.parse_color(args.c0) if args.c0 else DEFAULT_FROM
    c1 = colorramp.parse_color(args.c1) if args.c1 else DEFAULT_TO

    src_path = args.src if os.path.isabs(args.src) else os.path.join(here, args.src)
    if not os.path.exists(src_path):
        raise SystemExit("找不到输入文件：%s" % src_path)
    out_path = args.out or os.path.join(
        os.path.dirname(src_path),
        os.path.splitext(os.path.basename(src_path))[0] + "_gradient.png")
    if not os.path.isabs(out_path):
        out_path = os.path.join(here, out_path)

    src = Image.open(src_path)
    if not (src.mode in ("RGBA", "LA", "PA") or "transparency" in src.info):
        raise SystemExit("输入没有 alpha 通道，形状无从判断（%s）" % src.mode)
    src.load()
    icc = src.info.get("icc_profile")

    # ---------------------------------------------------------------- 拆主体 / 字形
    arr = np.asarray(src.convert("RGBA")).astype(np.int32)
    h, w = arr.shape[:2]
    alpha = arr[:, :, 3]
    lum = luminance(arr[:, :, :3])

    shape = alpha > 0
    if not shape.any():
        raise SystemExit("整张图的 alpha 都是 0，没有形状可以上色")
    interior = colorramp.erode(shape, args.trim)

    cov = np.clip((lum - args.noise_floor) / (255.0 - args.noise_floor), 0.0, 1.0)
    glyph_cov = np.where(interior, cov, 0.0) if args.glyph == "keep" \
        else np.zeros((h, w), dtype=np.float64)

    # ---------------------------------------------------------------- 渐变
    lo, hi, p_lo, p_hi = colorramp.diagonal_span(shape)
    t = colorramp.diagonal_t(w, h, lo, hi)
    ramp = np.array(colorramp.build_ramp(c0, c1, args.space), dtype=np.uint8)
    idx = np.clip(np.rint(t * (colorramp.RAMP - 1)), 0, colorramp.RAMP - 1).astype(np.int32)
    grad = ramp[idx].astype(np.float64)

    # 主体 → 渐变；字形按覆盖率混白。字形之外 cov=0，于是透明区也拿到渐变 RGB，
    # 缩放时不会再渗出黑边（见 docstring）。
    g = glyph_cov[:, :, None]
    out_rgb = np.rint(grad * (1.0 - g) + 255.0 * g).clip(0, 255).astype(np.uint8)
    out = np.dstack([out_rgb, alpha.astype(np.uint8)])

    # 原地覆盖时不能还占着源文件的句柄，所以上面已经 src.load() 读完了
    Image.fromarray(out, "RGBA").save(out_path, "PNG", icc_profile=icc, optimize=True)

    # ---------------------------------------------------------------- 汇报
    print("输入  %s  %dx%d %s" % (os.path.relpath(src_path, here), src.width, src.height, src.mode))
    print("输出  %s  %dx%d RGBA  字形处理 %s"
          % (os.path.relpath(out_path, here), w, h, args.glyph))
    print()
    print("主体  不透明 %d px   %s" % (int(shape.sum()), describe(shape)))
    print("渐变轴 45° 方向，x+y ∈ [%d, %d]（长度 %d）" % (lo, hi, hi - lo))
    print("  起点 %s @%s   终点 %s @%s"
          % (colorramp.hexs(c0), p_lo, colorramp.hexs(c1), p_hi))

    glyph = glyph_cov >= GLYPH_REPORT_LEVEL
    if glyph.any():
        gb = bbox(glyph)
        sb = bbox(shape)
        print("字形  覆盖率≥%.0f%% 的有 %d px   %s"
              % (GLYPH_REPORT_LEVEL * 100, int(glyph.sum()), describe(glyph)))
        print("      距主体外沿：左 %d  右 %d  上 %d  下 %d（--trim %d 的余量很足）"
              % (gb[0] - sb[0], sb[2] - gb[2], gb[1] - sb[1], sb[3] - gb[3], args.trim))
    else:
        print("字形  无（全部当主体处理）")

    # 被 --trim 甩掉的那一圈里，有多少本来是"亮"的 —— 就是那圈裁剪杂质
    ring_bright = (shape & ~interior) & (lum > 128)
    print()
    print("外沿  --trim %d 甩掉 %d px，其中亮（>128）%d px —— 这批就是被当黑色处理的杂质"
          % (args.trim, int((shape & ~interior).sum()), int(ring_bright.sum())))
    if ring_bright.any():
        print("      它们的亮度范围 %.0f~%.0f，若按字形处理就会在四边镶上白线"
              % (lum[ring_bright].min(), lum[ring_bright].max()))

    print()
    print("端点与中点（当前空间 %s）：" % args.space)
    for name in ("srgb", "linear", "oklab"):
        mid = colorramp.mix(c0, c1, name, 0.5)
        print("  %-7s %s → 中 %s → %s%s"
              % (name, colorramp.hexs(c0), colorramp.hexs(mid), colorramp.hexs(c1),
                 "   ← 采用" if name == args.space else ""))

    # ---------------------------------------------------------------- 自检
    # alpha 必须逐字节不动；两个极值点必须真的落在端点色上
    a_in = np.asarray(src.convert("RGBA"))[:, :, 3]
    got_lo = out[p_lo[1], p_lo[0], :3]
    got_hi = out[p_hi[1], p_hi[0], :3]
    print()
    print("自检  alpha 通道 %s（%d 个像素）"
          % ("逐字节一致" if np.array_equal(a_in, alpha.astype(np.uint8)) else "!! 被改动了 !!",
             a_in.size))
    print("      左上极值点 期望 %s  实测 %s  @%s alpha=%d"
          % (colorramp.hexs(c0), colorramp.hexs(got_lo), p_lo, alpha[p_lo[1], p_lo[0]]))
    print("      右下极值点 期望 %s  实测 %s  @%s alpha=%d"
          % (colorramp.hexs(c1), colorramp.hexs(got_hi), p_hi, alpha[p_hi[1], p_hi[0]]))

    if args.preview:
        prev = os.path.splitext(out_path)[0] + "_preview.png"
        size = make_preview(src.convert("RGBA"), Image.fromarray(out, "RGBA"), prev)
        print()
        print("对照图 %s（%dx%d，多底色 + 两角放大）" % (os.path.relpath(prev, here), size[0], size[1]))


if __name__ == "__main__":
    main()
