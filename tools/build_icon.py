"""
从一张「白底 + 图形」的源图，产出 Android 全套图标资源。

用法：
    python tools/build_icon.py                       # 用默认源图 tools/icon-source.png
    python tools/build_icon.py <源图.png> [--radius-dp 35]

核心是那句话：**内容要落在可见圆内**。
自适应图标总共 108dp，但启动器遮罩只保证中心那个 66dp 直径的圆必然可见，
遮罩最大也就到 72dp 直径。源图里那个圆角方框的外角半径是 47.7dp ——
直接铺满 108 的话，圆形遮罩（Pixel、部分主题）会把四个角整个切掉，
而外框是这张图的主干，切掉就废了。所以按最大半径缩放，让它整个待进可见圆。

实测：内容缩到 35dp 半径时，MIUI 的 square/圆形托盘都不会切到外框，
且在桌面上一屏邻居里大小完全一致（托盘会按自己的比例裁，设计只要在安全区内就行）。

产出：
    mipmap-*/ic_launcher_art.png    前景层（白底 + 缩放后的图形）
    mipmap-*/ic_launcher_mono.png   单色层（Android 13+ 主题图标取它的 alpha）
    mipmap-*/ic_launcher*.webp      老格式兜底（minSdk 26 用不到，保持不缺档）
    drawable/ic_launcher_background.xml  纯白底
    mipmap-anydpi/ic_launcher*.xml       组装三层
"""

import os
import sys

import numpy as np
from PIL import Image, ImageDraw

CANVAS_DP = 108

# 默认源图。它是构建输入，跟脚本放一起才不会哪天被当临时文件清掉
DEFAULT_SOURCE = os.path.join("tools", "icon-source.png")

DENSITIES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

# 默认把内容最大半径压到 35dp：可见圆半径是 36，留 1dp 余量，
# 免得遮罩边缘、阴影或各家一点点尺寸差异把外框啃到
DEFAULT_RADIUS_DP = 35.0

# 单色层的分离阈值。源图是白底，所以用「离白距离」切：
#   实测 设计内容 118~250（95% 在 232 以上），背景 ≤36
# 取 60~150 这段做过渡带，两边都干净，边缘还能保留抗锯齿
MONO_LO = 60.0
MONO_HI = 150.0


def load_source(path: str) -> np.ndarray:
    src = Image.open(path).convert("RGB")
    if src.width != src.height:
        side = min(src.width, src.height)
        off = ((src.width - side) // 2, (src.height - side) // 2)
        src = src.crop((off[0], off[1], off[0] + side, off[1] + side))
    a = np.asarray(src).astype(np.int32)
    print(f"源图 {src.width}x{src.height}")
    return a


def measure(src: np.ndarray) -> tuple[float, float, float, float]:
    """
    量出内容在「源图整幅 = 108dp」这套坐标系里的包围情况。

    返回 (内容最大半径dp, 包围盒左dp, 上dp, 宽dp)。
    后面缩放和居中都基于它 —— 量一次、用到处，避免两处各算一遍算出不同结果。
    """
    h, w, _ = src.shape
    dist = np.abs(255 - src).sum(axis=2)
    content = dist > 40

    ys, xs = np.nonzero(content)
    x0, x1, y0, y1 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    k = CANVAS_DP / w
    max_r_dp = float(np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2).max()) * k
    return max_r_dp, x0 * k, y0 * k, (x1 - x0 + 1) * k


def fit_scale(max_r_dp: float, radius_dp: float) -> float:
    """把内容最大半径从 max_r_dp 压到 radius_dp 所需的缩放比。"""
    return radius_dp / max_r_dp


def paste_centered(
    src_img: Image.Image, px: int, scale: float, background
) -> Image.Image:
    """
    把源图按 scale 缩放后居中贴到 px 见方的画布上。

    **关键**：side_px 必须由画布的像素尺寸算，不能由源图的像素尺寸算。
    源图是 2048px、画布是 432px，两者差着量级；把源图宽乘上缩放比再往小画布上贴，
    贴进去的其实是设计正中被放大了十几倍的一小块 —— 图标会彻底废掉。
    画布的 px 才对应 108dp，所以 side_px = 画布px × 缩放比。
    """
    side_px = max(1, int(round(px * scale)))
    scaled = src_img.resize((side_px, side_px), Image.LANCZOS)
    canvas = Image.new(src_img.mode, (px, px), background)
    off = (px - side_px) // 2
    canvas.paste(scaled, (off, off))
    return canvas


def foreground(src: np.ndarray, px: int, radius_dp: float) -> Image.Image:
    """前景层：白底 + 缩放居中后的图形。"""
    max_r_dp, _, _, content_w_dp = measure(src)
    scale = fit_scale(max_r_dp, radius_dp)
    print(
        f"  内容最大半径 {max_r_dp:.1f}dp -> {radius_dp:.0f}dp"
        f"（缩放 {scale:.3f}，内容落到 {content_w_dp * scale:.1f}dp 宽）"
    )
    return paste_centered(
        Image.fromarray(src.astype(np.uint8)), px, scale, (255, 255, 255)
    )


def monochrome(src: np.ndarray, px: int, radius_dp: float) -> Image.Image:
    """
    单色层：白色 + 推导出的 alpha。

    系统只取 alpha 染色，所以形状准不准全看这里。源图是白底，
    按「离白距离」切最直接：白 = 透明，图形 = 不透明。
    注意不能用「亮度」或「饱和度」—— 这张图的渐变从紫到青，
    两者的亮度差很大，按亮度切会让紫色那头变薄。

    缩放用的 scale 跟前景完全同源，否则主题图标会和前景错位。
    """
    h, w, _ = src.shape
    dist = 255 - src.min(axis=2)
    alpha = np.clip((dist - MONO_LO) / (MONO_HI - MONO_LO), 0.0, 1.0)

    out = np.zeros((h, w, 4), dtype=np.uint8)
    out[:, :, 0] = 255
    out[:, :, 1] = 255
    out[:, :, 2] = 255
    out[:, :, 3] = (alpha * 255).astype(np.uint8)

    max_r_dp, _, _, _ = measure(src)
    return paste_centered(
        Image.fromarray(out, "RGBA"),
        px,
        fit_scale(max_r_dp, radius_dp),
        (0, 0, 0, 0),
    )


def legacy(src: np.ndarray, px: int) -> Image.Image:
    """
    老格式兜底图（方形 / 圆形各一份）。

    这条分支在 minSdk 26 的设备上根本不会被走到，留着只是不让资源缺档。
    它没有遮罩，所以让内容铺到约 62dp —— 比前景略大（不会顶边），又不至于显小。
    """
    max_r_dp, _, _, _ = measure(src)
    return paste_centered(
        Image.fromarray(src.astype(np.uint8)), px, fit_scale(max_r_dp, 30.0), (255, 255, 255)
    )


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        args = [DEFAULT_SOURCE]

    radius_dp = DEFAULT_RADIUS_DP
    for i, a in enumerate(sys.argv):
        if a == "--radius-dp" and i + 1 < len(sys.argv):
            radius_dp = float(sys.argv[i + 1])

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.join(root, "app", "src", "main", "res")

    source = args[0]
    if not os.path.isabs(source):
        source = os.path.join(root, source)
    if not os.path.exists(source):
        print(f"找不到源图：{source}")
        raise SystemExit(2)

    src = load_source(source)

    for folder, px in DENSITIES.items():
        target = os.path.join(res, folder)
        os.makedirs(target, exist_ok=True)

        foreground(src, px, radius_dp).save(
            os.path.join(target, "ic_launcher_art.png"), "PNG", optimize=True
        )
        monochrome(src, px, radius_dp).save(
            os.path.join(target, "ic_launcher_mono.png"), "PNG", optimize=True
        )

        legacy_px = int(round(px * 48 / CANVAS_DP))
        base = legacy(src, legacy_px).convert("RGBA")
        base.convert("RGB").save(
            os.path.join(target, "ic_launcher.webp"), "WEBP", lossless=True
        )
        n = base.width
        ss = 4
        mask = Image.new("L", (n * ss, n * ss), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, n * ss - 1, n * ss - 1], fill=255)
        mask = mask.resize((n, n), Image.LANCZOS)
        base.putalpha(mask)
        base.save(os.path.join(target, "ic_launcher_round.webp"), "WEBP", lossless=True)
        print(f"  {folder:20} {px:>3}px  art/mono/legacy ok")

    drawable = os.path.join(res, "drawable")
    with open(os.path.join(drawable, "ic_launcher_background.xml"), "w",
              encoding="utf-8") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<!--\n"
            "    图标底色：纯白。\n"
            "    前景层本身就带白底，所以这层其实看不见 —— 它的作用是在\n"
            "    前景万一缺失时不至于露出透明方块。\n"
            "-->\n"
            '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:shape="rectangle">\n'
            '    <solid android:color="#FFFFFFFF" />\n'
            "</shape>\n"
        )

    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n"
        "    自适应图标（API 26+，本应用 minSdk 就是 26，生效的一直是这一份）。\n"
        "\n"
        "    前景用位图而不是矢量：图形带渐变，重绘成单色矢量会丢掉它。\n"
        "    构图在生成时已经缩到「内容最大半径 35dp」，落在启动器保证可见的\n"
        "    66dp 圆内，圆形、方形、圆角方形遮罩都切不到外框。\n"
        "    调整构图请改 tools/build_icon.py 的 radius 参数。\n"
        "\n"
        "    单色层给 Android 13+ 的「按壁纸取色」用，系统只取它的 alpha。\n"
        "-->\n"
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_art" />\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_mono" />\n'
        "</adaptive-icon>\n"
    )
    anydpi = os.path.join(res, "mipmap-anydpi")
    os.makedirs(anydpi, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, name), "w", encoding="utf-8") as f:
            f.write(adaptive)
    print("anydpi/ic_launcher{,round}.xml ok")

    for stale in ("ic_launcher_foreground.xml", "ic_launcher_monochrome.xml"):
        p = os.path.join(drawable, stale)
        if os.path.exists(p):
            os.remove(p)
            print(f"  移除过期的 {stale}")
    print("完成")


if __name__ == "__main__":
    main()
