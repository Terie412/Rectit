package com.example.explaindot.capture

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 借无障碍通道拿屏幕画面 —— 这是本 App 唯一的截屏通路。
 *
 * 对比一下另一条技术路线就很清楚它为什么值得：MediaProjection 会建立投屏会话，
 * 系统把它算作「投屏」，而 takeScreenshot() 跟电源键截屏走的是同一条路。
 *
 * 这一点在这里很关键：小米澎湃OS 的「屏幕共享防护」（电诈防护的一部分）判定的是
 * `projecting` —— **有没有活跃的投屏会话**。本服务不建立投屏会话，那套判定根本不会
 * 触发，所以微信文章能拿到原图，而不是被主动打码的那一版。
 *
 * 附带好处：不用每次弹授权框；进程被系统回收后会自动重连（普通 Service 做不到）。
 *
 * 代价：无障碍是系统里最高敏感的权限之一，必须用户亲手到设置里打开，应用无法自助。
 * 而且系统在应用更新后可能把它关掉，那时截不了图 —— 界面要如实说明。
 *
 * 本服务不监听任何事件、不读取窗口内容 —— 它唯一的作用就是借到那个截屏方法。
 * 系统允许这么做，但要求配置里显式声明 canTakeScreenshot，否则调用时直接抛
 * SecurityException（不是返回失败码，是崩）。见 res/xml/accessibility_shot_config.xml。
 */
class AccessibilityShotService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍截屏已就绪（系统 API ${Build.VERSION.SDK_INT}）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "无障碍截屏已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * 抓一张整屏图。
     *
     * 回调在后台线程，拿到的是**软件位图**（能直接裁剪、压缩），失败给 null。
     * 转换不能放主线程：一张全屏 ARGB_8888 在这个尺寸上是几十 MB，拷贝一次几十毫秒。
     */
    fun captureScreen(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // takeScreenshot 是 API 30 才有的。本机的 minSdk 是 26，
            // 真落到这段说明设备太旧，安静地失败即可 —— 调用方会给提示
            Log.w(TAG, "无障碍截屏需要 Android 11+（本机 API ${Build.VERSION.SDK_INT}），跳过")
            onResult(null)
            return
        }
        requestScreenshot(onResult)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestScreenshot(onResult: (Bitmap?) -> Unit) {
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, ioExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    onResult(screenshot.toSoftwareBitmap())
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "系统拒绝截屏：${describe(errorCode)}")
                    onResult(null)
                }
            })
        }.onFailure {
            // 配置里漏了 canTakeScreenshot、或用户中途关掉了服务，都会落到这里
            Log.e(TAG, "截屏调用直接抛异常", it)
            onResult(null)
        }
    }

    /**
     * 硬件缓冲 → 软件位图。
     *
     * wrapHardwareBuffer 给出的是 GPU 侧缓冲，配置为 HARDWARE —— 它既不能裁剪，
     * 也不能压缩，必须先复制成 ARGB_8888。而且复制得赶在 buffer.close() 之前完成，
     * 关掉之后再碰它就是无效内存。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun AccessibilityService.ScreenshotResult.toSoftwareBitmap(): Bitmap? {
        val buffer = hardwareBuffer
        return try {
            Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                ?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            Log.e(TAG, "硬件缓冲转位图失败", t)
            null
        } finally {
            buffer.close()
        }
    }

    /** 把系统的错误码翻译成人话，出问题时 logcat 里能直接看出原因 */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "系统内部错误"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "服务没有截屏能力（配置漏了 canTakeScreenshot）"
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "距上次截屏太近，被限流"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "显示器 ID 无效"
        ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "窗口 ID 无效"
        // 6（API 34 起）= 窗口带 FLAG_SECURE，系统拒绝交出内容。刻意不引用那个常量，
        // 它是 API 34 才有的，写死数字加注释比引一个可能不存在的字段稳
        else -> "未知错误码 $code"
    }

    companion object {
        private const val TAG = "A11yShot"

        /**
         * 当前活着的服务实例，系统 bind 上就挂、解绑就摘。
         *
         * 截屏必须通过实例调（takeScreenshot 是实例方法），所以这个为 null
         * 就真的取不到图了 —— 没有别的通路可回落。
         */
        @Volatile
        var instance: AccessibilityShotService? = null
            private set

        /** 全屏位图的转换与裁剪都不该占主线程，串行一条就够 */
        private val ioExecutor: Executor = Executors.newSingleThreadExecutor()

        /**
         * 用户在系统设置里是否真的开着了本服务。
         *
         * **这里必须看两个 setting，只看一个就会误判。** 它们回答的是两个不同的问题：
         *
         *   `ENABLED_ACCESSIBILITY_SERVICES` —— 白名单：用户**勾过**这个服务，
         *   存的是被勾选的服务列表（冒号分隔的 `包名/类名`）
         *   `ACCESSIBILITY_ENABLED`        —— 总开关：无障碍这个**功能整体**开着没有（1/0）
         *
         * 总开关关掉时，系统**不会**把白名单里的条目清掉。于是只看白名单就会得到
         * 「已开启」，而实际上一个服务都不生效、`takeScreenshot` 直接抛异常。
         *
         * 真机上就是这么踩到的：`accessibility_enabled=0` 配上白名单里仍有本服务，
         * 设置页显示「框选解释依赖它，已就绪」，而用户长按圆点根本截不到图 ——
         * 界面上没有任何线索指向真正的原因。
         *
         * 查系统设置、而不是看 [instance]：服务被系统重启的间隙 instance 会短暂为 null，
         * 但用户那边的开关其实还开着，界面不该闪一下变回「未开启」。
         *
         * 判断逻辑抽在 [accessibilityReady] 里，为的是能单测 —— 这个 bug 的形态就是
         * 「少查了一个条件」，而少查条件不会有任何报错，只会让界面说谎。
         */
        fun isEnabled(context: Context): Boolean {
            val resolver = context.contentResolver
            val enabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            val services = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return accessibilityReady(
                enabled = enabled,
                services = services,
                // flattenToString 给的是 `包名/全类名`，和 setting 里的写法同源
                expected = ComponentName(context, AccessibilityShotService::class.java)
                    .flattenToString()
            )
        }

        /** 拉起系统的无障碍设置页。应用不能自助开启，只能把用户送到那儿 */
        fun openSystemSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { Log.e(TAG, "无障碍设置页起不来", it) }
        }
    }
}

/**
 * 无障碍服务此刻是否真的可用。
 *
 * ## 为什么是两个条件，缺一个就会说谎
 *
 * [enabled] 和 [services] 来自两个不同的 setting，回答两个不同的问题：
 *
 *   `accessibility_enabled`             —— 无障碍这个**功能整体**开着没有（1/0）
 *   `enabled_accessibility_services`    —— 用户**勾过**哪些服务（冒号分隔的 `包名/类名`）
 *
 * **总开关关掉时系统不会清空白名单。** 于是只查白名单会得到「开着」，
 * 而实际上一个服务都不生效 —— 界面显示「已就绪」，用户去框选却什么也截不到，
 * 而且找不到任何线索指向真正的原因。
 *
 * 真机上就是这样：`accessibility_enabled=0`，白名单里仍留着本服务，
 * 设置页显示「框选解释依赖它，已就绪」。
 *
 * ## 为什么不看服务实例（[AccessibilityShotService.instance]）
 *
 * 服务被系统重启的间隙实例会短暂为 null，但用户那边的开关其实还开着 ——
 * 那时报「未开启」是在说谎，用户会跑去系统设置里把一个好好的开关关掉再打开。
 *
 * ## 关于多用户
 *
 * 两个 setting 都是按用户存的，`Settings.Secure` 默认读当前用户，与这里的
 * 服务实例属于同一个用户，不需要额外处理。
 *
 * 抽成纯函数是为了能单测：这个 bug 的形态是「少查了一个条件」，
 * 而少查条件不会有任何报错。
 */
internal fun accessibilityReady(enabled: Int, services: String?, expected: String): Boolean {
    // 总开关。只认 1 —— 读不到时 getInt 给的是默认值 0，那也正是「没开」
    if (enabled != 1) return false

    val list = services ?: return false
    val target = flattenComponent(expected)
    return list.split(':').any { flattenComponent(it) == target }
}

/**
 * 把 `包名/.类名` 的短写展开成 `包名/全类名`，好让两种写法能比出相等。
 *
 * 系统存进 setting 的形式不统一：有的是全类名（`pkg/capture.Xxx`），
 * 有的是相对写法（`pkg/.capture.Xxx`）—— 后者在无障碍设置页里手动开关过之后
 * 更容易出现。不展开的话，同一个服务会因为写法不同被认成两个。
 */
private fun flattenComponent(raw: String): String {
    val s = raw.trim()
    val slash = s.indexOf('/')
    // 没有斜杠、或者斜杠贴着首尾 —— 这不是一个组件名，原样返回让它比不相等
    if (slash <= 0 || slash == s.length - 1) return s

    val pkg = s.substring(0, slash)
    val cls = s.substring(slash + 1)
    return if (cls.startsWith(".")) "$pkg/$pkg$cls" else "$pkg/$cls"
}
