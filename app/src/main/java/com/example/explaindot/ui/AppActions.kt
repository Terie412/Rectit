package com.example.explaindot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.explaindot.overlay.OverlayService

/**
 * 圆点的开关。
 *
 * 唯一一件顺手要做的事是讨一次通知权限：前台服务必须挂一个常驻通知，
 * 而那个通知同时是「关掉圆点」的入口 —— 不给权限通知就看不见，
 * 用户就少了一条退出路径。所以这里要一次，但拿不到也照常启动。
 *
 * 用「发一次请求、不管结果」而不是 registerForActivityResult：
 * 后者必须在 Activity STARTED 之前注册，而首页和设置页都可能触发这个动作，
 * 为一次可以忽略的结果去管注册时序不划算。
 */
fun toggleDot(activity: ComponentActivity, running: Boolean) {
    if (running) {
        OverlayService.stop(activity)
        return
    }
    requestNotificationPermission(activity)
    OverlayService.start(activity)
}

private fun requestNotificationPermission(activity: ComponentActivity) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted = ContextCompat.checkSelfPermission(
        activity,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATION
        )
    }
}

private const val REQUEST_NOTIFICATION = 100
