package com.example.explaindot.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 悬浮窗权限的查询与申请。
 *
 * 这个权限和别的不一样：它不是运行时权限，没法用 requestPermissions() 弹框，
 * 只能跳到系统设置页让用户自己打开开关。所以调用方必须在 onResume 里重新查一次，
 * 因为这个页面是「跳出去再回来」的。
 */
object OverlayPermission {

    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 标准入口。注意各家 ROM（小米 / 华为 / OPPO）可能把它重定向到自己的权限中心，
     * 页面长得不一样，但 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` 始终是正确入口。
     */
    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
}
