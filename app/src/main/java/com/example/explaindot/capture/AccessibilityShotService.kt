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
         * 「屏幕取图」现在是什么状态。
         *
         * ## 判据是服务实例，设置只用来解释原因
         *
         * 截屏只能通过实例调（`takeScreenshot` 是实例方法），所以 [instance] 为 null
         * 就是真的取不到图 —— 系统设置里显示成什么样都不改变这一点。
         *
         * **这一点是连着踩了两次才定下来的。** 前两版都只看系统设置，两次都在说谎：
         *
         *   1. `accessibility_enabled=0` 而白名单里仍留着本服务 —— 只看白名单
         *      会得出「已就绪」
         *   2. 两个 setting 都对，但 `dumpsys accessibility` 里 `Bound services:{}`：
         *      白名单是**直接写 setting** 留下的（MIUI 自己的界面开关没走过），
         *      系统从未真正 bind。这一次连设置都查不出问题
         *
         * 第二种状态在真机上很常见：应用被强行停止、或者更新之后，白名单条目会留着，
         * 而服务不再被绑定。用户看到的是「已就绪」，然后长按圆点毫无反应。
         *
         * ## 关于"服务重启的间隙"
         *
         * 早先的版本为了避免那个间隙闪一下，特意不看实例。但这里报的不是
         * 「未开启」而是「还没连上」，措辞上就是暂时的，比"已就绪"这种谎话好得多。
         * 而且那个间隙很短，用户下次进设置页就会重新读。
         */
        fun state(context: Context): A11yState {
            val resolver = context.contentResolver
            return accessibilityState(
                connected = instance != null,
                enabled = Settings.Secure.getInt(
                    resolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                ),
                services = Settings.Secure.getString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ),
                // flattenToString 给的是 `包名/全类名`，和 setting 里的写法同源
                expected = ComponentName(context, AccessibilityShotService::class.java)
                    .flattenToString()
            )
        }

        /** 能不能截图。只要那个结论的调用方用这个 */
        fun isEnabled(context: Context): Boolean = state(context) == A11yState.Ready

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
 * 「屏幕取图」此刻处于哪种状态。
 *
 * 四态而不是布尔，因为「不能取图」有**三种原因，用户要去动的东西各不相同**：
 *
 *   [Ready]         服务连着，能截
 *   [SwitchOff]     无障碍总开关关着 —— 去系统设置里先打开页面顶部的「无障碍」，
 *                   再确认下面本应用的开关。分开说这一句，是因为用户到了那个页面
 *                   很可能只看到一排应用、找不到自己该动哪个
 *   [NotSelected]   总开关开着，但本应用没被勾上 —— 直接在列表里打开本应用
 *   [NotConnected]  系统里显示是开着的，但服务**没被绑定** —— 需要把这个开关
 *                   关掉再打开一次。这一态是它自己的、不能说成另外两种
 *
 * ## 为什么必须有 [NotConnected]
 *
 * 前三种状态都只看系统设置就能判断，而**系统设置可以和真实能力不一致**。
 * 真机上连续踩到两次，两次的界面都是错的：
 *
 *   1. 第一次：`accessibility_enabled=0` 但白名单里还留着本服务 →
 *      只看白名单会得出「已就绪」
 *   2. 第二次：两个 setting 都是对的（总开关 1、白名单有本服务），
 *      而 `dumpsys accessibility` 里 `Bound services:{}` —— 服务根本没连上。
 *      这一次连设置都查不出问题，只有服务实例能反映真相
 *
 * 所以判据必须是 [AccessibilityShotService.instance]：截屏只能通过实例调
 * （`takeScreenshot` 是实例方法），实例为 null 就是真的取不到图，
 * 设置里显示成什么样都不改变这一点。设置只用来解释"为什么没连上"。
 *
 * 合成一个布尔的话，"不能取图"就只剩一句话，而用户照着它走完可能还是不行。
 */
enum class A11yState { Ready, NotConnected, SwitchOff, NotSelected }

/**
 * 判定无障碍服务的当前状态。纯函数，好单测。
 *
 * [connected] 是**权威判据** —— 它来自服务实例，代表"真的能截"。其余三个参数
 * 只在它说"不能"的时候用来解释原因，见 [A11yState] 的注释。
 *
 * 所以顺序是：连着就是 Ready，没连着才去看设置。反过来（先看设置、
 * 设置对了就说 Ready）正是之前那两次误判的形态。
 */
internal fun accessibilityState(
    connected: Boolean,
    enabled: Int,
    services: String?,
    expected: String
): A11yState {
    if (connected) return A11yState.Ready

    // 没连上。下面三种原因里挑一个最可能是用户该去处理的那个 ——
    // 顺序对应"从最外层到最里层"：总开关 → 白名单 → 都对了但还是没连上
    if (enabled != 1) return A11yState.SwitchOff

    val list = services ?: return A11yState.NotSelected
    val target = flattenComponent(expected)
    val selected = list.split(':').any { flattenComponent(it) == target }

    return if (selected) A11yState.NotConnected else A11yState.NotSelected
}

/**
 * [accessibilityState] 的布尔投影：只有 [A11yState.Ready] 才算能截图。
 *
 * 单独留一个函数，是因为大多数调用方（圆点服务、首页的待办清单）
 * 只关心"能不能截"，不关心为什么不能。
 */
internal fun accessibilityReady(
    connected: Boolean,
    enabled: Int,
    services: String?,
    expected: String
): Boolean = accessibilityState(connected, enabled, services, expected) == A11yState.Ready

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
