"""
渐变工具共用的色彩机器：解析色值、在几种色彩空间里插值、生成一维色带。

被 tools/gradient_dot.py 和 tools/gradient_icon.py 共用。抽出来是因为
「在两个颜色之间怎么插值」这件事本身有分量 —— 它决定中间段长什么样 ——
不该在两个脚本里各写一份、各自演化。

三种插值空间，差别只在过渡段的观感：

| 空间   | 中点（紫 #854FEE → 青 #20F6D2） | 什么时候用 |
|--------|------------------|------------|
| srgb   | #52A2E0          | **默认**。逐通道线性混合，Compose 的 `Brush.linearGradient` / CSS `linear-gradient` 的默认行为，和「在界面上直接画一条渐变」一致 |
| linear | #63BBE1          | 伽马正确。物理上对，但中间段偏亮，冷色渐变会显得发白 |
| oklab  | #73AAE5          | 感知均匀。中间段不会发灰，紫→青这种跨大色相的过渡最顺 |

oklab 的过渡段可能略微超出 sRGB 色域，这里按通道截断（clamp），不做
gamut mapping —— 对这几个色值实测没碰到越界。
"""

# 预计算色带的长度。1024 档对 8 位输出绰绰有余（相邻两档差不到 1/255），
# 逐像素只做一次取整 + 查表，比每个像素都跑一遍浮点混合快一个量级。
RAMP = 1024


# ---------------------------------------------------------------------------- 色值

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
    return "#%02X%02X%02X" % tuple(int(c) for c in rgb[:3])


# ---------------------------------------------------------------------------- 色彩空间

def _s2l(c):
    """sRGB 分量 → 线性光。"""
    v = c / 255.0
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def _l2s(v):
    """线性光 → sRGB 分量（0-255）。越界按通道截断。"""
    v = min(1.0, max(0.0, v))
    return int(round(255 * (12.92 * v if v <= 0.0031308
                            else 1.055 * v ** (1 / 2.4) - 0.055)))


def _mul(m, v):
    return [sum(row[i] * v[i] for i in range(3)) for row in m]


# Oklab 的正反变换矩阵（Björn Ottosson 的原始系数）
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


def mix(c0, c1, space, t):
    """在 c0 → c1 上取 t 处的颜色，t 允许超出 [0,1]（会按端点截断）。"""
    if t <= 0.0:
        return tuple(c0)
    if t >= 1.0:
        return tuple(c1)
    return SPACES[space](c0, c1, t)


def build_ramp(c0, c1, space, n=RAMP):
    """预计算一维色带，返回 n 个 (r,g,b) 的列表。"""
    f = SPACES[space]
    return [f(c0, c1, i / (n - 1)) for i in range(n)]


# ---------------------------------------------------------------------------- 几何

def diagonal_span(mask):
    """形状沿 45° 方向（左上 → 右下）的投影范围与两个极值点。

    投影量取 x + y（差一个 √2 系数，反正后面要归一化）。

    **为什么归一化到形状、而不是整张画布的对角线。** 形状的外沿才是「左上」和
    「右下」—— 用户说「左上角是紫色」，指的是图形自己的左上角。按画布对角线
    归一化的话，形状只覆盖其中一段，两端永远取不到纯色，看到的始终是「被冲淡过
    的」中间色。按形状归一化，形状的第一个和最后一个像素正好是端点色。

    返回 (lo, hi, p_lo, p_hi)，p_* 是极值点的 (x, y)。
    """
    import numpy as np

    ys, xs = np.nonzero(mask)
    if len(xs) == 0:
        raise ValueError("整张图的 alpha 都是 0，没有形状可以上色")
    s = xs + ys
    i_lo, i_hi = int(s.argmin()), int(s.argmax())
    return (int(s[i_lo]), int(s[i_hi]),
            (int(xs[i_lo]), int(ys[i_lo])), (int(xs[i_hi]), int(ys[i_hi])))


def diagonal_t(w, h, lo, hi):
    """整张画布上每个像素沿 45° 的归一化位置 t（0 = 左上极值，1 = 右下极值）。

    用广播而不是 np.mgrid：x 只有一维、y 只有一维，靠相加让 numpy 展开成
    (h, w)，省掉两个和结果同尺寸的中间数组。1482x1486 的图上差别是几十 MB。
    """
    import numpy as np

    x = np.arange(w, dtype=np.float64)
    y = np.arange(h, dtype=np.float64)[:, None]
    span = float(hi - lo) or 1.0
    return (x[None, :] + y - lo) / span


def erode(mask, radius):
    """把二值掩码向内收 radius 像素（钻石形结构元，4 邻域迭代）。

    用来甩掉形状最外沿那一圈。不依赖 scipy：只要 numpy 的移位就够了。
    """
    import numpy as np

    m = mask
    for _ in range(radius):
        e = m.copy()
        e[1:, :] &= m[:-1, :]
        e[:-1, :] &= m[1:, :]
        e[:, 1:] &= m[:, :-1]
        e[:, :-1] &= m[:, 1:]
        m = e
    return m
