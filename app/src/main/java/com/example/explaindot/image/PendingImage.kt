package com.example.explaindot.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.example.explaindot.ai.AiConfig
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * 「当前待分析的那张图」—— 全手机只有一张，三个来源共用这一个槽位。
 *
 * 三个来源：
 *   · 圆点框选（走无障碍截屏，[com.example.explaindot.overlay.OverlayService]）
 *   · 相册（系统图片选择器）
 *   · 相机（系统相机 App）
 *
 * **为什么三者共用一个槽位。** 下游（[com.example.explaindot.analysis.AnalysisController]、
 * [com.example.explaindot.ai.DeepSeekClient]）只认「一个文件路径」这件事，
 * 它不需要知道图是从哪来的。给每个来源各开一份存储，等于把「当前在看哪张图」
 * 这个问题变成三个可能同时为真的答案，而界面上只有一块缩略图的位置。
 * 后一次取图覆盖前一次，顺带把「换了一张图」的语义也说得只剩一种。
 *
 * **落盘策略**（沿用早先只有框选时的三条规则，它们现在依然成立）：
 *   1. 文件名固定，每次覆盖写，天然只有一份
 *   2. 写完顺手把目录里其他东西删干净（老版本攒下的、以及任何意外残留）
 *   3. 落在 cacheDir 而不是 filesDir —— 万一清理逻辑失了手，
 *      系统在存储紧张时还能兜底回收
 *
 * **所有写入都是「先写 .tmp 再改名」。** 分析可能在另一次取图之后才开始跑，
 * 而它读的是这个固定路径；直接原地覆盖的话，读到的会是一份写了一半的 JPEG。
 * 改名在同一个文件系统上是原子的 —— 读的人要么看到旧的整张，要么新的整张。
 *
 * 单独抽成对象而不是散在调用方里，是因为「清理历史积压」除了取图后要做，
 * App 每次打开时也该做一次：用户升级上来时，老版本攒的那堆图还在，
 * 不能非要等他再取一次图才被清掉。
 */
object PendingImage {

    /** 目录名。**必须与 res/xml/file_paths.xml 里声明的 path 一致** —— 改这里要一起改 */
    private const val DIR_NAME = "pending"

    /** 固定文件名。覆盖写意味着任何时候最多只有这一张 */
    private const val FILE_NAME = "latest.jpg"

    /**
     * 相机把照片写到这儿，再由 [ingest] 收进 [FILE_NAME]。
     *
     * **为什么不让相机直接写 [FILE_NAME]。** 系统相机拿到 Uri 后是
     * 以「截断 + 创建」的方式打开的 —— 一旦它开了，槽位里原本那张图就成 0 字节了。
     * 而用户随时可能按返回取消，那时 [FILE_NAME] 已经被毁掉，界面上的缩略图
     * 和正在看的那轮分析全指向一个空文件。多一个暂存文件，取消就取消，
     * 槽位一动没动。
     */
    private const val CAMERA_NAME = "camera.jpg"

    /** 写一半的中间产物。改名的来源，[prune] 会把它当垃圾清掉 */
    private const val TEMP_NAME = "$FILE_NAME.tmp"

    /**
     * 早先只有框选时用的目录名。
     *
     * 那时候它叫 captures，因为装的全是截图；现在相册和相机也往这里放，
     * 名字就不成立了。旧目录在新版本里没有任何代码会去读，但它会一直躺在
     * 缓存里占着几十到几百 KB —— 顺手删掉，不然「清理」这件事就不彻底。
     */
    private const val LEGACY_DIR_NAME = "captures"

    /**
     * 送给模型的图，长边最多这么多像素 —— **是个上限，不是目标值**。
     *
     * 这个数是「小字还认得出」和「base64 别太肥」之间的取舍：
     * 一页书的照片常有 4000px 长边，原样发出去是几 MB 的 base64，
     * 上传慢、图片 token 也贵；压到 2048 以内之后正文和常见脚注依然清楚。
     *
     * 实际落点见 [sampleSizeFor]：多半在 1448~2048 之间，取决于源图尺寸。
     *
     * 框选那条路不受影响 —— 它送的是框内的原生分辨率，
     * 通常只有几百像素宽，本来就不该再降。
     */
    private const val MAX_EDGE = 2048

    /**
     * 降采样的下限：再降一档就低于这个值的话，就不降了。
     *
     * [MAX_EDGE] 除以 √2 —— 两个相邻的 2 的幂之间的几何中点。
     * 为什么取这个点，见 [sampleSizeFor]。
     */
    private val SAMPLE_FLOOR = (MAX_EDGE / 1.4142f).roundToInt()

    fun dir(context: Context): File = File(context.cacheDir, DIR_NAME)

    /** 当前那张图的落点。注意：调用它不代表文件存在 */
    fun file(context: Context): File = File(dir(context), FILE_NAME)

    /**
     * 交给系统相机的写入位置。
     *
     * 目录必须先建出来 —— FileProvider 只负责授权一个路径，不会替我们创建它。
     * 收进槽位之后这个文件就没用了，[prune] 会把它清掉
     * （它只保留 [FILE_NAME] 那一个）。
     */
    fun cameraShot(context: Context): File = File(dir(context), CAMERA_NAME)

    /**
     * 删掉固定文件以外的一切，外加那个已经废弃的旧目录。
     *
     * 对不存在的目录、删不掉的单个文件都不报错 —— 这是收尾动作，
     * 失败了大不了下次再删，不该因为清理失败就让整个取图流程断掉。
     */
    fun prune(context: Context) {
        dir(context).listFiles()?.forEach { old ->
            if (old.name != FILE_NAME) {
                runCatching { old.delete() }
            }
        }
        runCatching { File(context.cacheDir, LEGACY_DIR_NAME).deleteRecursively() }
    }

    /**
     * 把一张现成的位图落成「当前待分析的那张图」。框选那条路走这里。
     *
     * **不回收入参** [bitmap]，由调用方负责 —— 和 [com.example.explaindot.capture.cropToSelection]
     * 的约定一致，避免出现「传进来的人以为被回收了、这里以为没有」这种两不管。
     *
     * @return 落盘后的文件；失败给 null
     */
    fun save(context: Context, bitmap: Bitmap): File? = runCatching {
        writeJpeg(context, bitmap)
    }.onFailure {
        Log.e(TAG, "位图落盘失败", it)
    }.getOrNull()

    /**
     * 把一份来自外部的图（相册选中的、相机拍下的）收进来。
     *
     * 这一路必须过一遍重编码，不能把原文件直接搬过来：
     *   · 相册里的图可能是 PNG / WebP / HEIC，而 [com.example.explaindot.ai.DeepSeekClient]
     *     是按 `data:image/jpeg` 发出去的 —— 格式对不上，模型那边认不认全看运气
     *   · 相机拍的常见是 4000px 长边、好几 MB，base64 之后体积翻三分之一还多
     *   · 相册图片的 EXIF 里常带旋转角，而 BitmapFactory **不认** EXIF，
     *     照原样发出去，竖着拍的书页会躺倒 —— 字是横的，模型照样能读，
     *     但读错的概率明显变高
     *
     * 三件事在这一处一次做完，出去的就是一张横平竖直、长边不超过 [MAX_EDGE]
     * 的 JPEG，和框选那条路的产物形状完全一致。
     *
     * 两个来源的差别只在「读哪个 Uri」：
     *   · 相册给的是一个系统的 content Uri
     *   · 相机给的是 [cameraShot] 那个文件 —— 它是另一个文件，不是槽位本身，
     *     所以这里不需要担心读着读着把正在读的东西覆盖掉。收完之后
     *     [prune] 会把那个暂存文件清掉
     *
     * @return 收好之后的文件；解码失败或读不出来给 null
     */
    fun ingest(context: Context, uri: Uri): File? = runCatching {
        val decoded = decode(context, uri) ?: return@runCatching null
        decoded.useSafely { writeJpeg(context, it) }
    }.onFailure {
        Log.e(TAG, "外部图片收不进来：$uri", it)
    }.getOrNull()

    // ------------------------------------------------------------------ 内部：解码

    /**
     * 解码一份外部图，摆正方向，并缩到长边不超过 [MAX_EDGE]。
     *
     * 返回的位图由调用方负责回收（[useSafely] 就是干这个的）。
     */
    private fun decode(context: Context, uri: Uri): Bitmap? {
        // 第一遍只问尺寸，不分配像素。inJustDecodeBounds 时 decodeStream 返回 null 是正常的，
        // 结果是写进 options 里的
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "读不出图片尺寸，可能是不认识的格式：$uri")
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        if (raw == null) {
            Log.w(TAG, "解码失败：$uri")
            return null
        }

        val upright = applyExifOrientation(context, uri, raw)
        return scaleToMaxEdge(upright)
    }

    /**
     * 降采样倍率。
     *
     * 判据是「再降一档就掉到 [SAMPLE_FLOOR] 以下了吗」，掉下去了就停手。
     * 于是解码后的长边落在 [SAMPLE_FLOOR, 2×SAMPLE_FLOOR)，最常见的
     * 12MP 照片（4000×3000）解出来是 2000px；超过 [MAX_EDGE] 的那部分
     * 由 [scaleToMaxEdge] 收尾。
     *
     * **为什么不取「降完不小于 [MAX_EDGE]」这个看起来更保守的规则。**
     * 那条规则的实际含义是「结果落在 [MAX_EDGE, 2×MAX_EDGE)」，
     * 而 4000px 正好落在最坏的那一端：会以 4000px 解码，
     * ARGB_8888 就是 48MB —— 加上随后缩放出的那张，峰值 60MB。
     * 换来的只是 2048 与 2000 之间那 2% 的分辨率，对认字没有任何可感差别。
     *
     * **为什么不取「降完不超过 [MAX_EDGE]」。** 源图长边刚过线时会被腰斩：
     * 2500px 会掉到 1250px，书页上的小字就真糊了。而看书的照片恰恰
     * 集中在 2000~4000px 这一段，撞上的概率不低。
     *
     * √2 是这两个 2 的幂之间代价相等的分界点：往这边偏，最差结果是
     * [MAX_EDGE] 的 71%（约 1448px，认字依然够用），内存上限约 37MB；
     * 往那边偏，最差是分辨率掉一半。分辨率是这个 App 的立身之本，所以偏内存这侧。
     */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        val longest = maxOf(width, height)
        var sample = 1
        while (longest / (sample * 2) >= SAMPLE_FLOOR) sample *= 2
        return sample
    }

    /**
     * 摆正方向。读 EXIF 的旋转标记，按它转（或翻）回来。
     *
     * **为什么用 android.media.ExifInterface（已废弃）而不是 androidx 那个。**
     * androidx.exifinterface 是一条新依赖，而这个项目为了「离线也能构建」
     * 一直在收紧依赖表；为一个只读一个字段的功能加一个库，不划算。
     * 平台自带的这个类从 API 24 就有、读方向标记这件事足够可靠，
     * 废弃的是整个类而不是这个能力。见下面 @Suppress 的理由。
     *
     * 拿不到方向标记时原样返回 —— 那多半是 PNG 之类本来就没有 EXIF 的格式。
     */
    @Suppress("DEPRECATION")
    private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: return bitmap

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)

            // 转置 / 反转置 = 转 90 度再水平翻。这两个标记很少见，但不处理的话
            // 出来的图是镜像的 —— 镜子里的字，模型读起来是另一个词
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }

        val rotated = runCatching { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }
            .getOrNull() ?: return bitmap
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * 兜底：万一 [sampleSizeFor] 降完仍超过 [MAX_EDGE]，往下缩到正好。
     *
     * 这一步通常不执行 —— 降采样之后长边多半已经落在目标区间里了。
     * 会落到这里的是「长边刚过 MAX_EDGE 一点点」那种源图（比如 2049px）：
     * 它不值得再降一档（那会掉到 1024），所以按原尺寸解出来，在这里收个尾。
     *
     * **只往下缩，不往上放大。** 放大只是把同样的像素摊大，白占体积也白涨 token。
     */
    private fun scaleToMaxEdge(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= MAX_EDGE) return src

        val ratio = MAX_EDGE.toFloat() / longest
        val width = (src.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (src.height * ratio).roundToInt().coerceAtLeast(1)

        val scaled = runCatching { Bitmap.createScaledBitmap(src, width, height, true) }
            .getOrNull() ?: return src
        if (scaled !== src) src.recycle()
        return scaled
    }

    // ------------------------------------------------------------------ 内部：落盘

    /**
     * 编码成 JPEG 并原子地放进槽位。
     *
     * 质量取 [AiConfig.imageQuality]（来自 ai.properties 的 deepseek.imageQuality）——
     * 这份配置的用途写的就是「送给模型的图不必无损」，落盘是它唯一的用武之地。
     * 早先它是个死配置：同类逻辑在 OverlayService 里另外硬编码了一个 85。
     */
    private fun writeJpeg(context: Context, bitmap: Bitmap): File? {
        val dir = dir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "目录建不出来：$dir")
            return null
        }

        val temp = File(dir, TEMP_NAME)
        val quality = AiConfig.imageQuality

        FileOutputStream(temp).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                Log.e(TAG, "JPEG 编码失败")
                temp.delete()
                return null
            }
        }

        val target = File(dir, FILE_NAME)
        if (!temp.renameTo(target)) {
            // 改名失败（极罕见）也不能把图丢了：退化成非原子的覆盖写
            Log.w(TAG, "改名失败，退化成直接覆盖写")
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }

        prune(context)
        return target
    }

    /** 用完就回收，无论 [block] 是正常返回还是抛出去 */
    private inline fun <T> Bitmap.useSafely(block: (Bitmap) -> T): T =
        try {
            block(this)
        } finally {
            recycle()
        }

    private const val TAG = "PendingImage"
}
