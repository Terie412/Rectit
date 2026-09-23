package com.example.explaindot.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlin.math.roundToInt

/**
 * 把整屏截图裁成框选的那一块。
 *
 * [AccessibilityShotService] 拿到的是整屏图，框选给的是一块矩形，
 * 中间这一步就是这里做的。
 *
 * 坐标映射不能省：框选坐标用的是 windowMetrics 体系，截图用的是物理分辨率，
 * 两者在大多数机器上相等，但那是巧合不是契约，按比例映射才稳。
 *
 * @return 裁好的新位图；失败给 null。**不回收入参** [full]，由调用方负责。
 */
internal fun cropToSelection(full: Bitmap, rect: Rect, screenW: Int, screenH: Int): Bitmap? {
    if (screenW <= 0 || screenH <= 0) return null

    val scaleX = full.width.toFloat() / screenW
    val scaleY = full.height.toFloat() / screenH

    val left = (rect.left * scaleX).roundToInt().coerceIn(0, full.width - 1)
    val top = (rect.top * scaleY).roundToInt().coerceIn(0, full.height - 1)
    val right = (rect.right * scaleX).roundToInt().coerceIn(left + 1, full.width)
    val bottom = (rect.bottom * scaleY).roundToInt().coerceIn(top + 1, full.height)

    return try {
        Bitmap.createBitmap(full, left, top, right - left, bottom - top)
    } catch (t: Throwable) {
        Log.e(TAG, "裁剪失败 rect=$rect", t)
        null
    }
}

private const val TAG = "ShotCropper"
