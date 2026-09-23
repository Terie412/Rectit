"""
把一枚纯白的实心图形重绘成渐变 —— 线性（左上 → 右下）或径向（边缘 → 中心）。

输入是 temp/dot.png 那种：RGB 全是纯白、形状完全靠 alpha 描述的图标。
输出同尺寸、**alpha 逐字节不变**，只把 RGB 换成渐变。

四个不显然的地方，写在这里免得以后有人照直觉改回去：

1. **渐变坐标归一化到"形状"，不是整张画布。**

   线性：圆点内切在 128×128 里，直径 112。沿 45° 方向，圆上的极值点离中心
   ±r/√2 ≈ ±39.6，只占整张画布对角线长度（±90.5）的 62%。按画布对角线归一化
   的话，圆点两端永远取不到纯色，看到的始终是"被冲淡过的"中间色。

   径向：半径取「质心 → 最远的不透明像素」，不是内切圆半径，也不是画布的一半。
   这三者对正圆恰好相等，但对圆角方、对框就不一样了 —— 用最远像素才能保证
   形状的最外沿一定拿到纯的边缘色。

   顺带这个规则对非圆形同样成立：形状沿轴向的两个极值点分别是起点色和终点色。

2. **径向的 t=0 在边缘、t=1 在中心。** 这是反直觉的（数学上半径 0 在圆心），
   但 `--from / --to` 要读作"渐变从哪走到哪"，所以跟 `--mode radial` 的字面
   方向对齐：`--from` 是边缘色，`--to` 是中心色。
   换句话说 `--from 紫 --to 青` = 紫边青心；想反过来就交换两个参数。

3. **透明像素的 RGB 也一起填。** 原文件的透明区是 (0,0,0,0)，即 RGB 是黑。
   PNG 缩小、旋转时是带 RGB 一起插值的，黑 RGB 会在边缘渗出灰黑一圈。
   所以整张画布都写渐变 RGB，只把 alpha 原样贴回去。

4. **插值空间默认 sRGB**，即逐通道线性混合。这是 Compose 的
   `Brush.linearGradient` / `radialGradient`、CSS `linear-gradient` /
   `radial-gradient` 的默认行为，所以改出来的图和"直接在界面上画一条渐变"
   看起来是一致的。另给 `linear`（伽马正确）和 `oklab`（感知均匀，中间段不会
   发灰）两个选项，脚本会把三种空间的中点色打出来，可以看完再定。
   oklab 的过渡段可能略微超出 sRGB 色域，这里按通道截断，不做 gamut mapping。

用法：
    python tools/gradient_dot.py temp/dot.png                      # 左上→右下线性
    python tools/gradient_dot.py temp/dot_gradient.png --mode radial --preview
    python tools/gradient_dot.py temp/dot.png -o out.png \\
        --from 133,79,238 --to 32,246,210 --space oklab --preview
"""

import argparse
import math
import os

from PIL import Image

# 默认就是 ExplainDot 图标量出来的那两个锚点色（见 tools/check_palette.py）：
# 紫 #854FEE（色相 260°）和青 #20F6D2（色相 170°）。
DEFAULT_FROM = (133, 79, 238)
DEFAULT_TO = (32, 246, 210)

RAMP = 1024                 # 预计算的一维色带长度，避免逐像素做浮点混合


# ---------------------------------------------------------------------------- 颜色

def parse_color(text):
    """吃 "133,79,238" 或 "#854FEE" 或 "854FEE"，吐 (r, g, b)。"""
    t = text.strip().lstrip("#")
    if "," in t:
        parts = [p.strip() for p in t.split(",")]
        if len(parts) != 3:
            raise ValueError("颜色要三个分量：%r" % text)
        rgb = tuple(int(p) for p in parts)
    else:
        if len(t) != 6:
            raise ValueError("十六进制颜色要 6 位：%r" % text)
        rgb = (int(t[0:2], 16), int(t[2:4], 16), int(t[4:6], 16))
    for c in rgb:
        if not 0 <= c <= 255:
            raise ValueError("分量超出 0-255：%r" % text)
    return rgb


def hexs(rgb):
    return "#%02X%02X%02X" % rgb


def _s2l(c):
    """sRGB 分量 → 线性光。"""
    v = c / 255.0
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def _l2s(v):
    """线性光 → sRGB 分量（0-255），越界按通道截断。"""
    v = min(1.0, max(0.0, v))
    return int(round(255 * (12.92 * v if v <= 0.0031308
                            else 1.055 * v ** (1 / 2.4) - 0.055)))


def _mul(m, v):
    return [sum(row[i] * v[i] for i in range(3)) for row in m]


# Oklab 的前后变换矩阵（Björn Ottosson 的原始系数）
_OK_LMS = (
    (0.4122214708, 0.5363325363, 0.0514459929),
    (0.2119034982, 0.6806995451, 0.1073969566),
    (0.0883024619, 0.2817188376, 0.6299787005),
)
_OK_LAB = (
    (0.2104542553, 0.7936177850, -0.0040720468),
    (1.9779984951, -2.4285922050, 0.4505937099),
    (0.0259040371, 0.7827717662, -0.8086757660),
)
_OK_LMS_INV = (
    (1.0, 0.3963377774, 0.2158037573),
    (1.0, -0.1055613458, -0.0638541728),
    (1.0, -0.0894841775, -1.2914855480),
)
_OK_RGB = (
    (4.0767416621, -3.3077115913, 0.2309699292),
    (-1.2684380046, 2.6097574011, -0.3413193965),
    (-0.0041960863, -0.7034186147, 1.7076147010),
)


def to_oklab(rgb):
    lms = [max(0.0, c) ** (1 / 3) for c in _mul(_OK_LMS, [_s2l(c) for c in rgb])]
    return _mul(_OK_LAB, lms)


def from_oklab(lab):
    return tuple(_l2s(c) for c in _mul(_OK_RGB, [c ** 3 for c in _mul(_OK_LMS_INV, lab)]))


SPACES = {
    "srgb": lambda a, b, t: tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3)),
    "linear": lambda a, b, t: tuple(
        _l2s(_s2l(a[i]) + (_s2l(b[i]) - _s2l(a[i])) * t) for i in range(3)),
    "oklab": lambda a, b, t: from_oklab(
        [to_oklab(a)[i] + (to_oklab(b)[i] - to_oklab(a)[i]) * t for i in range(3)]),
}


def build_ramp(c0, c1, space):
    mix = SPACES[space]
    return [mix(c0, c1, i / (RAMP - 1)) for i in range(RAMP)]


# ---------------------------------------------------------------------------- 形状几何

class Geometry(object):
    """形状的几何量：线性轴的两个极值点，径向的圆心与半径。

    一次扫描全部算完 —— 两个模式共用同一遍遍历，省得按模式分叉。
    """

    def __init__(self, alpha, w, h):
        lo = hi = None
        p_lo = p_hi = (0, 0)
        sw = sx = sy = 0.0                  # alpha 加权的质心分子
        for y in range(h):
            row = y * w
            for x in range(w):
                a = alpha[row + x]
                if not a:
                    continue
                s = x + y                       # 45° 投影量（差一个 √2，后面归一化）
                if lo is None or s < lo:
                    lo, p_lo = s, (x, y)
                if hi is None or s > hi:
                    hi, p_hi = s, (x, y)
                sw += a
                sx += a * x
                sy += a * y
        if lo is None:
            raise ValueError("整张图的 alpha 都是 0，没有形状可以上色")

        self.lo, self.hi = lo, hi
        self.p_lo, self.p_hi = p_lo, p_hi
        self.w, self.h = w, h

        # alpha 加权的质心。正圆上它精确等于圆心；不规则形状上也给一个稳定锚点。
        # 用加权而不是纯包围盒中心：抗锯齿的边缘像素 alpha 很小，
        # 包围盒会被半透明的毛边拽偏，加权质心不会。
        self.cx, self.cy = sx / sw, sy / sw

        # 半径取"到最远不透明像素"，保证形状最外沿一定落在 t=0（纯边缘色）。
        # 用内切半径的话，非圆形状的四个角会提前撞到 t=0，色带被压扁。
        far, r = None, 0.0
        for y in range(h):
            row = y * w
            for x in range(w):
                if alpha[row + x]:
                    d = math.hypot(x - self.cx, y - self.cy)
                    if d > r:
                        r, far = d, (x, y)
        self.r = r
        self.p_far = far


# ---------------------------------------------------------------------------- 渲染

def render(src, c0, c1, space, mode):
    """c0 = 渐变起点色，c1 = 终点色。

    linear: 起点在左上，终点在右下。
    radial: 起点在边缘，终点在中心（见模块 docstring 第 2 条）。
    """
    img = src.convert("RGBA")
    w, h = img.size
    alpha = img.getchannel("A").tobytes()   # 原始字节，逐像素索引就是 int
    geo = Geometry(alpha, w, h)

    ramp = build_ramp(c0, c1, space)
    last = RAMP - 1

    if mode == "linear":
        span = float(geo.hi - geo.lo) or 1.0

        def at(x, y):
            return (x + y - geo.lo) / span
    else:
        inv_r = 1.0 / geo.r if geo.r else 0.0

        def at(x, y):
            # 半径归一化后翻过来：边缘 t=0（起点色），圆心 t=1（终点色）
            return 1.0 - math.hypot(x - geo.cx, y - geo.cy) * inv_r

    out = bytearray()
    for y in range(h):
        row = y * w
        for x in range(w):
            t = at(x, y)
            r, g, b = ramp[0 if t <= 0 else last if t >= 1 else int(t * last + 0.5)]
            out += bytes((r, g, b, alpha[row + x]))

    return Image.frombytes("RGBA", (w, h), bytes(out)), geo


def make_preview(img, path, scale=3, pad=12):
    """深底 + 浅底两块拼一起。透明 PNG 单看是看不出问题的，得压到两种底上。"""
    w, h = img.size
    big = img.resize((w * scale, h * scale), Image.LANCZOS)
    panel = Image.new("RGB", (big.width * 2 + pad * 3, big.height + pad * 2), (0x80, 0x80, 0x80))
    for i, bg in enumerate(((0x1C, 0x1C, 0x22), (0xFF, 0xFF, 0xFF))):
        tile = Image.new("RGB", big.size, bg)
        tile.paste(big, (0, 0), big)
        panel.paste(tile, (pad + i * (big.width + pad), pad))
    panel.save(path)


def make_side_by_side(imgs, path, scale=3, pad=12, bg=(0x1C, 0x1C, 0x22)):
    """把几张图并排拼一起，压在同一块底色上。用来直接比两种方向的观感。"""
    tiles = []
    for im in imgs:
        big = im.resize((im.width * scale, im.height * scale), Image.LANCZOS)
        tile = Image.new("RGB", big.size, bg)
        tile.paste(big, (0, 0), big)
        tiles.append(tile)
    wsum = sum(t.width for t in tiles) + pad * (len(tiles) + 1)
    panel = Image.new("RGB", (wsum, tiles[0].height + pad * 2), bg)
    x = pad
    for t in tiles:
        panel.paste(t, (x, pad))
        x += t.width + pad
    panel.save(path)


# ---------------------------------------------------------------------------- 主流程

def main():
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    ap = argparse.ArgumentParser(
        description="给纯白实心图形上渐变（线性 / 径向）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="linear: --from 左上、--to 右下      radial: --from 边缘、--to 中心")
    ap.add_argument("src", help="输入 PNG（靠 alpha 描述形状的纯白图形）")
    ap.add_argument("-o", "--out", help="输出路径，默认在输入旁边加 _gradient")
    ap.add_argument("--mode", choices=("linear", "radial"), default="linear",
                    help="linear 左上→右下（默认） / radial 边缘→中心")
    ap.add_argument("--from", dest="c0", help="起点色，如 133,79,238 或 #854FEE")
    ap.add_argument("--to", dest="c1", help="终点色，如 32,246,210 或 #20F6D2")
    ap.add_argument("--space", choices=sorted(SPACES), default="srgb",
                    help="插值空间，默认 srgb（和 Compose / CSS 一致）")
    ap.add_argument("--preview", action="store_true", help="另存一张深底+浅底对照图")
    args = ap.parse_args()

    c0 = parse_color(args.c0) if args.c0 else DEFAULT_FROM
    c1 = parse_color(args.c1) if args.c1 else DEFAULT_TO

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
    # 先整体读进内存。两个原因：写盘要覆盖同一个路径时不能还占着文件句柄
    # （Windows 上会直接报错），而且下面的自检必须拿**改写前**的 alpha 来比 ——
    # 存完再读就成了新文件跟它自己比，永远通过，等于没检查。
    src.load()
    a_in = src.convert("RGBA").getchannel("A").tobytes()

    out, geo = render(src, c0, c1, args.space, args.mode)
    out.save(out_path)

    # ------------------------------------------------------------------ 汇报
    w, h = out.size
    radial = args.mode == "radial"

    print("输入  %s  %dx%d %s" % (os.path.relpath(src_path, here), src.width, src.height, src.mode))
    print("输出  %s  %dx%d RGBA  模式 %s" % (os.path.relpath(out_path, here), w, h, args.mode))
    print()

    if radial:
        print("渐变轴：径向，圆心 (%.2f, %.2f)  半径 %.2f" % (geo.cx, geo.cy, geo.r))
        print("  边缘 %s  →  中心 %s" % (hexs(c0), hexs(c1)))
        print("  t=0 在边缘、t=1 在圆心 —— --from 是边缘色。想反过来交换两个参数即可")
        print("  半径取到最远的不透明像素 %s，所以最外沿一定拿到纯边缘色" % (geo.p_far,))
    else:
        print("渐变轴：45° 方向，投影区间 x+y ∈ [%d, %d]（长度 %d）"
              % (geo.lo, geo.hi, geo.hi - geo.lo))
        print("  起点落在左上尖端 %s，终点落在右下尖端 %s" % (geo.p_lo, geo.p_hi))
        print("  若按整张画布对角线归一化，形状只覆盖其中 %.0f%%，两端永远是中间色"
              % (100 * (geo.hi - geo.lo) / (2.0 * (max(w, h) - 1))))

    print()
    print("端点与中点（当前空间 %s）：" % args.space)
    for name in ("srgb", "linear", "oklab"):
        mid = SPACES[name](c0, c1, 0.5)
        print("  %-7s %s → 中 %s → %s%s"
              % (name, hexs(c0), hexs(mid), hexs(c1),
                 "   ← 采用" if name == args.space else ""))

    # 自检：alpha 必须逐字节一致；渐变两端的极值点必须落在端点色上
    a_out = out.getchannel("A").tobytes()
    px = out.load()
    if radial:
        p_a, p_b = geo.p_far, (int(round(geo.cx)), int(round(geo.cy)))
        label_a, label_b = "边缘最远点", "圆心"
    else:
        p_a, p_b = geo.p_lo, geo.p_hi
        label_a, label_b = "左上尖端  ", "右下尖端  "

    print()
    print("自检  alpha 通道 %s（%d 个像素）"
          % ("逐字节一致" if a_in == a_out else "!! 被改动了 !!", len(a_in)))
    print("      %s %s  实测 %s  @%s" % (label_a, hexs(c0), hexs(px[p_a][:3]), p_a))
    print("      %s %s  实测 %s  @%s" % (label_b, hexs(c1), hexs(px[p_b][:3]), p_b))
    print("      不透明像素 %d / %d（形状没有变大变小）"
          % (sum(1 for a in a_out if a), w * h))

    if args.preview:
        prev = os.path.splitext(out_path)[0] + "_preview.png"
        make_preview(out, prev)
        print()
        print("对照图 %s（左深底 / 右浅底）" % os.path.relpath(prev, here))


if __name__ == "__main__":
    main()
