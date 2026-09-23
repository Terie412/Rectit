"""
从一张设计图产出 Android 全套图标资源。

用法：
    python tools/build_icon.py                                    # 当前设计
    python tools/build_icon.py <源图.png> --mode bleed
    python tools/build_icon.py <源图.png> --mode safe --radius-dp 35

两种模式对应两种设计，别选错：

    bleed（当前）  源图自己就是**整块图标** —— 渐变铺满、带自己的圆角，形状
                   由 alpha 描述（角落透明）。做法：按内容裁紧成正方形、直接
                   铺满 108dp 画布，透明处填白。启动器遮罩照常裁切，于是圆托盘、
                   方托盘、圆角方托盘里都是满幅的渐变。

    safe（旧设计） 源图是**白底 + 中间一个图形**。做法：量出图形最大半径，
                   缩到 35dp 以内，让它落在启动器保证可见的 66dp 圆内，四周留白。

两种模式产出的文件完全一样（见下面的「产出」），只是构图和单色层的取法不同。

产出：
    mipmap-*/ic_launcher_art.png    前景层
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
DEFAULT_SOURCE = os.path.join("tools", "icon-source-r.png")

DENSITIES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

# safe 模式默认把内容最大半径压到 35dp：可见圆半径是 36，留 1dp 余量，
# 免得遮罩边缘、阴影或各家一点点尺寸差异把外框啃到
DEFAULT_RADIUS_DP = 35.0

# ---------------------------------------------------------------------------
# 单色层的分离阈值。两种模式下「字形」的定义不同，取法也不同。
#
# bleed 模式：源图是**彩色渐变底 + 白字形**，用 min(R,G,B) 切最干净。
#   实测 min 的分布是干净的双峰 —— 渐变区 32~111（青那头最亮的 min 也才 111），
#   字形 240~255，中间 128~239 那一档只占约 2000 px，是字形边缘的抗锯齿过渡带。
#   取它做过渡带，边缘过渡就和原图一样顺。
#   **不能用亮度**：渐变的青那端亮度到 198，按亮度切会把整片青色当成字形。
GLYPH_LO = 128.0
GLYPH_HI = 240.0

# safe 模式：源图是**白底 + 深色图形**，用「离白距离」切。
#   实测 设计内容 118~250（95% 在 232 以上），背景 ≤36
MONO_LO = 60.0
MONO_HI = 150.0

# 算裁剪框时四周先补的透明边。内容不居中时裁剪框会越界，
# 补一圈就不必为边界情况写分支
PAD = 16


# --------------------------------------------------------------------------- 读

def load_source(path: str, mode: str) -> Image.Image:
    """读源图。bleed 模式保留 alpha（形状靠它描述），safe 模式只用 RGB。"""
    img = Image.open(path)
    img = img.convert("RGBA") if mode == "bleed" else img.convert("RGB")
    if img.width != img.height:
        side = min(img.width, img.height)
        off = ((img.width - side) // 2, (img.height - side) // 2)
        img = img.crop((off[0], off[1], off[0] + side, off[1] + side))
    print(f"源图 {os.path.basename(path)}  {img.width}x{img.height}  {mode} 模式")
    return img


# --------------------------------------------------------------------------- 通用

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


def content_box(alpha: np.ndarray):
    """
    按 alpha 算出「紧贴内容的**正方形**」裁剪框（坐标已含 [PAD] 的偏移）。

    为什么要正方形：源图的内容 bbox 常是 1299x1302 这种差几像素的，
    直接按 bbox 裁再缩到正方形画布，图形会被拉扁千分之几。补成正方形
    之后 1:1 映射，形状一点不变形。

    **边长取短的那条，不取长的。** 取长边的话，正方形会往短边两侧各多要
    半个差值 —— 而内容本来就顶到原图边界了，多要的部分只能落到原图之外，
    拿到的是补边的透明像素。那圈透明在 bleed 模式里会被填成白，
    于是图标边上镶出一条白线。（满幅的源图最容易踩到：1482x1486 差 4px，
    就足以在左右各镶 2px 白。）

    取短边的代价是长边被裁掉一点。源图是方形图标，差得极少 ——
    1299x1302 裁掉 3px，占千分之二，肉眼看不出来。真差得多的话会打警告。

    返回 (框, 边长)。框和边长要在前景、单色层之间共用 —— 各算一遍迟早会错位。
    """
    ys, xs = np.nonzero(alpha > 0)
    x0, x1, y0, y1 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
    bw, bh = x1 - x0 + 1, y1 - y0 + 1
    side = min(bw, bh)

    if max(bw, bh) / side > 1.02:
        print(f"  ⚠ 内容不是正方形（{bw}x{bh}），按短边裁会切掉长边的一部分")

    cx, cy = (x0 + x1 + 1) / 2.0, (y0 + y1 + 1) / 2.0
    left = int(round(cx - side / 2.0)) + PAD
    top = int(round(cy - side / 2.0)) + PAD

    # 上面取短边之后框必然落在内容之内，这两行只是万一将来改回去时的兜底
    left = max(0, min(left, alpha.shape[1] + 2 * PAD - side))
    top = max(0, min(top, alpha.shape[0] + 2 * PAD - side))
    return (left, top, left + side, top + side), side


def pad_and_crop(img: Image.Image, box) -> Image.Image:
    """先补一圈透明再按框裁。框可能越界，所以不能在原图上直接裁。"""
    canvas = Image.new("RGBA", (img.width + 2 * PAD, img.height + 2 * PAD), (0, 0, 0, 0))
    canvas.paste(img, (PAD, PAD))
    return canvas.crop(box)


# --------------------------------------------------------------------- safe 模式

def measure(src: np.ndarray) -> tuple[float, float, float, float]:
    """
    量出内容在「源图整幅 = 108dp」这套坐标系里的包围情况。

    返回 (内容最大半径dp, 包围盒左dp, 上dp, 宽dp)。
    后面缩放和居中都基于它 —— 量一次、用到处，避免两处各算一遍算出不同结果。
    """
    h, w, _ = src.shape
    content = np.abs(255 - src).sum(axis=2) > 40

    ys, xs = np.nonzero(content)
    x0, x1, y0, y1 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    k = CANVAS_DP / w
    max_r_dp = float(np.sqrt((xs - cx) ** 2 + (ys - cy) ** 2).max()) * k
    return max_r_dp, x0 * k, y0 * k, (x1 - x0 + 1) * k


def fit_scale(max_r_dp: float, radius_dp: float) -> float:
    """把内容最大半径从 max_r_dp 压到 radius_dp 所需的缩放比。"""
    return radius_dp / max_r_dp


def safe_foreground(src: np.ndarray, px: int, radius_dp: float) -> Image.Image:
    """前景层：白底 + 缩放居中后的图形。"""
    max_r_dp, _, _, content_w_dp = measure(src)
    scale = fit_scale(max_r_dp, radius_dp)
    print(f"  内容最大半径 {max_r_dp:.1f}dp -> {radius_dp:.0f}dp"
          f"（缩放 {scale:.3f}，内容落到 {content_w_dp * scale:.1f}dp 宽）")
    return paste_centered(
        Image.fromarray(src.astype(np.uint8)), px, scale, (255, 255, 255)
    )


def safe_monochrome(src: np.ndarray, px: int, radius_dp: float) -> Image.Image:
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
    out[:, :, :3] = 255
    out[:, :, 3] = (alpha * 255).astype(np.uint8)

    max_r_dp, _, _, _ = measure(src)
    return paste_centered(
        Image.fromarray(out, "RGBA"), px, fit_scale(max_r_dp, radius_dp), (0, 0, 0, 0)
    )


# -------------------------------------------------------------------- bleed 模式

def bleed_foreground(src: Image.Image, px: int, box) -> Image.Image:
    """
    前景层：图形铺满整个 108dp 画布，透明处填白。

    **填白。** 源图如果是带圆角、四角透明的（早期那份就是这样），不填的话：
    方形托盘的角落会透出托盘自己的底色（各家用什么不一定），
    而这个图标要出现在桌面、设置列表、最近任务好几处 —— 填白之后
    它在哪儿都是同一个样子，不用去猜底下垫的是什么。
    源图本身已经满幅不透明时（现在这份），这一步是空操作。

    铺满而不是缩进安全圆内：源图自己就带圆角，那就是它的造型。
    再缩一圈的话，托盘里会变成「一块白方 + 中间一个小渐变方」。
    """
    cropped = pad_and_crop(src, box).resize((px, px), Image.LANCZOS)
    canvas = Image.new("RGBA", (px, px), (255, 255, 255, 255))
    canvas.alpha_composite(cropped)
    return canvas


def bleed_monochrome(src: Image.Image, px: int, box) -> Image.Image:
    """
    单色层：白 R，位置和前景里的那个 R 完全对齐。

    取的是**字形**而不是整块方形的轮廓。系统只拿 alpha 染色，如果给整块
    方形的轮廓，主题图标就变成一个没有任何特征的色块 —— 桌面上一排
    主题图标全是圆角方块，认不出哪个是哪个。R 才是这个图标的识别标记。

    裁剪框和缩放跟前景同源（同一个 [box]），否则 R 会和前景错位。

    覆盖率是连续的、不是二值：字形和渐变交界有一圈中间调像素，
    二值化会在边缘留下锯齿。
    """
    arr = np.asarray(src)
    minc = arr[:, :, :3].min(axis=2).astype(np.float64)
    cov = np.clip((minc - GLYPH_LO) / (GLYPH_HI - GLYPH_LO), 0.0, 1.0)
    # 形状之外（alpha=0 的角落）一律不算字形：那里是透明的黑，
    # min 也是 0，会被算成 0 覆盖率，本来就没问题；乘一遍只是把话说死
    cov = cov * (arr[:, :, 3] > 0)

    h, w = cov.shape
    rgba = np.zeros((h, w, 4), dtype=np.uint8)
    rgba[:, :, :3] = 255
    rgba[:, :, 3] = (cov * 255).astype(np.uint8)

    return pad_and_crop(Image.fromarray(rgba, "RGBA"), box).resize(
        (px, px), Image.LANCZOS
    )


# --------------------------------------------------------------------------- 主流程

def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    mode = "bleed"
    radius_dp = DEFAULT_RADIUS_DP
    for i, a in enumerate(sys.argv):
        if a == "--mode" and i + 1 < len(sys.argv):
            mode = sys.argv[i + 1]
        if a == "--radius-dp" and i + 1 < len(sys.argv):
            radius_dp = float(sys.argv[i + 1])
    if mode not in ("bleed", "safe"):
        raise SystemExit("--mode 只能是 bleed 或 safe")
    if not args:
        args = [DEFAULT_SOURCE]

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.join(root, "app", "src", "main", "res")

    source = args[0]
    if not os.path.isabs(source):
        source = os.path.join(root, source)
    if not os.path.exists(source):
        print(f"找不到源图：{source}")
        raise SystemExit(2)

    src_img = load_source(source, mode)
    src = np.asarray(src_img)

    # 裁剪框只算一次，前景和单色层共用 —— 各算一遍迟早会错位
    box = None
    if mode == "bleed":
        box, side = content_box(src[:, :, 3])
        print(f"  内容裁成正方形 {side}x{side} -> 铺满 108dp 画布")

        # 自检：裁剪框必须落在内容之内。跑到内容之外就会拿到原图没有的透明像素，
        # 填白之后就是图标边上一条白线 —— 这类问题在缩略图里几乎看不见，
        # 只能靠这里算出来
        ay, ax = np.nonzero(src[:, :, 3] > 0)
        bx0, by0, bx1, by1 = box
        ok = (bx0 - PAD >= ax.min() and by0 - PAD >= ay.min()
              and bx1 - PAD <= ax.max() + 1 and by1 - PAD <= ay.max() + 1)
        print("  裁剪框落在内容之内：%s" % ("是" if ok else "否 —— 会引入透明边，填白后是白线"))

    for folder, px in DENSITIES.items():
        target = os.path.join(res, folder)
        os.makedirs(target, exist_ok=True)

        if mode == "bleed":
            art = bleed_foreground(src_img, px, box)
            mono = bleed_monochrome(src_img, px, box)
            legacy_px = int(round(px * 48 / CANVAS_DP))
            base = bleed_foreground(src_img, legacy_px, box)
        else:
            art = safe_foreground(src, px, radius_dp)
            mono = safe_monochrome(src, px, radius_dp)
            legacy_px = int(round(px * 48 / CANVAS_DP))
            base = paste_centered(
                Image.fromarray(src.astype(np.uint8)), legacy_px,
                fit_scale(measure(src)[0], 30.0), (255, 255, 255),
            )

        art.save(os.path.join(target, "ic_launcher_art.png"), "PNG", optimize=True)
        mono.save(os.path.join(target, "ic_launcher_mono.png"), "PNG", optimize=True)
        base.convert("RGB").save(
            os.path.join(target, "ic_launcher.webp"), "WEBP", lossless=True
        )

        n = base.width
        ss = 4
        mask = Image.new("L", (n * ss, n * ss), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, n * ss - 1, n * ss - 1], fill=255)
        round_base = base.copy()
        round_base.putalpha(mask.resize((n, n), Image.LANCZOS))
        round_base.save(os.path.join(target, "ic_launcher_round.webp"), "WEBP",
                        lossless=True)
        print(f"  {folder:20} {px:>3}px  art/mono/legacy ok")

    drawable = os.path.join(res, "drawable")
    with open(os.path.join(drawable, "ic_launcher_background.xml"), "w",
              encoding="utf-8") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<!--\n"
            "    图标底色：纯白。\n"
            "    前景层是铺满的，正常遮罩下看不到这一层 —— 它的作用是在\n"
            "    前景万一缺失时不至于露出透明方块。\n"
            "-->\n"
            '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:shape="rectangle">\n'
            '    <solid android:color="#FFFFFFFF" />\n'
            "</shape>\n"
        )

    if mode == "bleed":
        why = (
            "    前景层是**铺满**的：源图自己就带圆角，那就是它的造型 ——\n"
            "    按内容裁紧成正方形后直接映射到 108dp 画布，透明处填白。\n"
            "    再缩进安全圆内的话，托盘里会变成「一块白方 + 中间一个小渐变方」。\n"
            "\n"
            "    角落填白是刻意的：方形托盘的角落否则会透出托盘底色，\n"
            "    而这个图标要出现在桌面、设置列表、最近任务好几处。\n"
            "\n"
            "    单色层取的是白 R 的字形，不是整块方形的轮廓 —— 给轮廓的话\n"
            "    主题图标会变成一个没有任何特征的色块，一排里认不出谁是谁。"
        )
    else:
        why = (
            "    前景用位图而不是矢量：图形带渐变，重绘成单色矢量会丢掉它。\n"
            "    构图在生成时已经缩到「内容最大半径 35dp」，落在启动器保证可见的\n"
            "    66dp 圆内，圆形、方形、圆角方形遮罩都切不到外框。\n"
            "    调整构图请改 tools/build_icon.py 的 radius 参数。"
        )

    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n"
        "    自适应图标（API 26+，本应用 minSdk 就是 26，生效的一直是这一份）。\n"
        "    由 tools/build_icon.py 生成，别手改 —— 重新跑一次就覆盖了。\n"
        "\n"
        f"{why}\n"
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

    # 自检：前景必须完全不透明（透明处已填白），单色层不该是整幅
    check = os.path.join(res, "mipmap-xxxhdpi")
    a = np.asarray(Image.open(os.path.join(check, "ic_launcher_art.png")).convert("RGBA"))
    m = np.asarray(Image.open(os.path.join(check, "ic_launcher_mono.png")).convert("RGBA"))
    print()
    print("自检")
    print("  前景 alpha 最小值 %d（255 = 没有残留透明像素）" % a[:, :, 3].min())
    print("  前景左上角像素 %s（255,255,255 = 角落已填白）"
          % (tuple(int(v) for v in a[0, 0, :3]),))
    print("  单色层不透明像素占 %.1f%%（只该占中间一块，不该是整幅）"
          % ((m[:, :, 3] > 0).mean() * 100))
    print("完成")


if __name__ == "__main__":
    main()
