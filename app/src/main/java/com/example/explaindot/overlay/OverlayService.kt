package com.example.explaindot.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.explaindot.R
import com.example.explaindot.capture.AccessibilityShotService
import com.example.explaindot.capture.cropToSelection
import com.example.explaindot.image.PendingImage
import com.example.explaindot.ui.MainActivity
import kotlin.math.roundToInt

/**
 * 整个悬浮窗的宿主。它管两个窗口，按需挂载：
 *
 *   1. 圆点窗口   —— 常驻，只有 48dp，不抢焦点、不吃输入法。
 *   2. 框选窗口   —— 全屏透明，只在「长按触发之后」存在：从长按达成一直留到
 *                    框选完成、取消，或者闲置超时。
 *
 * 正常阅读时，全屏那一层根本不存在，所以不会拦截用户在别的 App 里的点击。
 *
 * 手势是怎么接起来的（这是这套东西最容易做错的地方）：
 *
 *   在圆点上原地按住 → DotView 自己读秒，满 DotView.LONG_PRESS_MS 回调 onLongPress
 *     ├─ 达成 → 铺全屏框选窗口 + 圆点隐身 + 震一下
 *     │         └─ 手指还在屏幕上，后续 MOVE 仍发给圆点窗口 →
 *     │            转发给框选层，用户接着滑就直接画框（不用松手重来）
 *     └─ 中途移动超阈值 → 认成拖动，读秒作废
 *
 *   拖动圆点   → onRelease(moved = true) → 存位置，不做别的
 *   点一下圆点 → 什么都不做
 *
 * 这里有个 Android 的脾气要记住：**手势在 ACTION_DOWN 那一刻就认给了某个窗口**，
 * 之后不管手指滑到哪里，MOVE 都发给它。所以长按达成后新铺的框选层收不到当前这个
 * 手势（它没接到 DOWN），必须由圆点窗口主动转发 —— 这就是 DotView 里
 * onLongPressMove / onLongPressEnd 那两个回调存在的理由。
 */
class OverlayService : Service(), DotView.Listener, SelectionView.Listener {

    private lateinit var windowManager: WindowManager
    private lateinit var store: DotPositionStore
    private val handler = Handler(Looper.getMainLooper())

    private var dotView: DotView? = null
    private var dotParams: WindowManager.LayoutParams? = null

    private var selectionView: SelectionView? = null

    /** 拖动开始时圆点窗口的位置，作为位移基准 */
    private var dragOriginX = 0
    private var dragOriginY = 0

    private val dismissSelection = Runnable { dismissSelectionWindow() }

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density
    private fun dp(value: Int): Float = value * density

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        store = DotPositionStore(this)

        // 顺序不能反：必须先 startForeground 再挂窗口。
        // 反了的话，系统可能在服务还没转正前就把进程优先级压下去，窗口随即被清掉。
        startAsForeground()
        addDotView()

        // 以「窗口是否真的挂上」为准，而不是以走到这一行为准：
        // addDotView 失败（权限被撤）时会 stopSelf，那种情况此刻还不该算在运行。
        isRunning = dotView != null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横竖屏切换后屏幕尺寸变了，圆点可能落在屏幕外，拉回来
        handler.post { clampDotIntoScreen() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        dismissSelectionWindow()
        dotView?.let { runCatching { windowManager.removeView(it) } }
        dotView = null
        dotParams = null
        isRunning = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 前台服务

    private fun startAsForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "框选圆点", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "圆点常驻时显示，可从这里关闭"
                    setShowBadge(false)
                }
            )
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dot)
            // 名字取资源里的 app_name，不写字面量：这条通知是用户最常看到 App 名字的
            // 地方之一（每开一次圆点就出现一次），改名字时最容易漏的就是它
            .setContentTitle(getString(R.string.app_name) + "已开启")
            .setContentText("在圆点上按住，接着划出要解释的范围")
            .setContentIntent(openApp)
            .addAction(0, "关闭", stopService)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ------------------------------------------------------------------ 圆点窗口

    private fun addDotView() {
        if (dotView != null) return

        val windowSize = dp(DOT_WINDOW_DP).roundToInt()
        val view = DotView(this).apply {
            // 窗口比圆点大一圈：手指不用点得那么准，视觉上还是一颗小圆点
            dotDiameter = dp(DOT_DIAMETER_DP)
            dragThreshold = dp(DRAG_THRESHOLD_DP)
            listener = this@OverlayService
        }

        val bounds = screenBounds()
        val params = WindowManager.LayoutParams(
            windowSize,
            windowSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START

            val (savedX, savedY) = store.load()
            if (savedX < 0 || savedY < 0) {
                // 第一次启动：贴右边，竖直方向放在上三分之一处（拇指够得着，又不挡正文开头）
                x = bounds.right - windowSize - dp(8f).roundToInt()
                y = bounds.top + bounds.height() / 3
            } else {
                x = savedX
                y = savedY
            }
        }

        runCatching { windowManager.addView(view, params) }.onFailure {
            Log.e(TAG, "圆点挂载失败，多半是悬浮窗权限被撤了", it)
            stopSelf()
            return
        }

        dotView = view
        dotParams = params
        clampDotIntoScreen()
    }

    private fun clampDotIntoScreen() {
        val view = dotView ?: return
        val params = dotParams ?: return
        val bounds = screenBounds()

        params.x = params.x.coerceIn(bounds.left, maxOf(bounds.left, bounds.right - params.width))
        params.y = params.y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - params.height))

        runCatching { windowManager.updateViewLayout(view, params) }
        store.save(params.x, params.y)
    }

    // ------------------------------------------------------------------ DotView 回调

    override fun onPress() {
        val params = dotParams ?: return
        dragOriginX = params.x
        dragOriginY = params.y
    }

    override fun onDrag(dx: Float, dy: Float) {
        val view = dotView ?: return
        val params = dotParams ?: return
        val bounds = screenBounds()

        params.x = (dragOriginX + dx).roundToInt()
            .coerceIn(bounds.left, maxOf(bounds.left, bounds.right - params.width))
        params.y = (dragOriginY + dy).roundToInt()
            .coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - params.height))

        runCatching { windowManager.updateViewLayout(view, params) }
    }

    override fun onRelease(moved: Boolean) {
        val params = dotParams ?: return
        if (moved) {
            store.save(params.x, params.y)
        }
        // 单击到此为止：什么都不做。改成「长按」之后这里不再需要猜用户意图，
        // 也就少了一段每次都吃掉 300ms 触摸的空窗
    }

    override fun onLongPress() {
        // 圆点先隐身：接下来这一整段（划框 + 调整）它都不该出现。
        // 它默认贴着屏幕右缘，而用户想框的往往正是右缘那一列字
        dotView?.alpha = 0f
        buzz()
        armSelectionWindow()
    }

    override fun onLongPressMove(x: Float, y: Float) {
        // 长按达成时手指还按在屏幕上，这个手势的后续事件仍然发给圆点窗口
        // （系统在 DOWN 时就认死了目标）。转给框选层，用户才能一口气长按 + 划框
        selectionView?.dragFromExternal(x, y)
    }

    override fun onLongPressEnd() {
        // 只结束「当前这一段划框」。框没划出来的话，框选层会留在 IDLE 等用户重新划，
        // 窗口不撤 —— 圆点的可见性也归后面的回调统一管，这里不碰
        selectionView?.endExternal()
    }

    // ------------------------------------------------------------------ 框选窗口

    private fun armSelectionWindow() {
        if (selectionView != null) return

        val view = SelectionView(this).apply {
            minimumSize = dp(MIN_SELECTION_DP)
            listener = this@OverlayService
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 让遮罩铺满刘海区域，否则顶部会留一条没被盖住的白边
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        runCatching { windowManager.addView(view, params) }.onFailure {
            Log.e(TAG, "框选窗口挂载失败", it)
            return
        }

        selectionView = view
        // 兜底超时：万一用户长按之后走开了，这层全屏窗口会一直吃掉所有触摸，
        // 用户连别的 App 都点不动。闲置够久就自己撤掉。
        // 一旦开始划框，onSelectionStart 会把计时撤掉，不会中途打断
        handler.postDelayed(dismissSelection, IDLE_TIMEOUT_MS)
    }

    /**
     * 撤掉框选层。
     *
     * keepDotHidden = true 是给「马上要截图」那条路径用的：圆点得继续保持隐身，
     * 等图拿到手再放它出来。不加这个开关的话，抬手瞬间圆点就恢复可见，
     * 100ms 后截的那一帧里必然带着它，压在框内的文字上。
     */
    private fun dismissSelectionWindow(keepDotHidden: Boolean = false) {
        handler.removeCallbacks(dismissSelection)
        val view = selectionView
        selectionView = null
        if (view != null) {
            runCatching { windowManager.removeView(view) }
        }
        if (!keepDotHidden) {
            restoreDot()
        }
    }

    /**
     * 取图超时兜底。
     *
     * 取图是异步的，理论上回调一定会来（成功或失败）。但只要有一环卡住 ——
     * 无障碍服务正好被系统解绑 —— 回调就永远不来。
     * 而圆点此刻正处于隐身状态，用户会觉得「按了半天没反应，圆点还消失了」。
     * 所以到点无条件把它放出来，最多是这一次没截成。
     */
    private val captureTimeout = Runnable {
        Log.w(TAG, "取图超时，把圆点放出来")
        restoreDot()
    }

    private fun armCaptureTimeout() {
        handler.removeCallbacks(captureTimeout)
        handler.postDelayed(captureTimeout, CAPTURE_TIMEOUT_MS)
    }

    /** 让圆点重新可见。它唯一会被藏起来的原因就是「马上要截图」。 */
    private fun restoreDot() {
        dotView?.alpha = 1f
    }

    // ------------------------------------------------------------------ SelectionView 回调

    override fun onSelectionStart() {
        // 开始划框了：撤掉闲置计时（用户已经动起来了，不该被超时打断），
        // 圆点继续保持隐身
        handler.removeCallbacks(dismissSelection)
        dotView?.alpha = 0f
    }

    override fun onSelectionReady(rect: Rect) {
        val screen = screenBounds()
        // 抬手这一刻就把圆点继续按住不显示 —— 等下要截的那一帧里不能有它。
        // 它默认贴在屏幕右缘，而用户想框的往往正是右缘那一列字。
        dismissSelectionWindow(keepDotHidden = true)

        if (!canCapture()) {
            restoreDot()
            // 两种情况都说「屏幕取图没开」，但原因完全不同，得分开讲：
            // 一种是用户真没开，另一种是系统开关还开着、服务却还没绑上
            Toast.makeText(
                this,
                if (AccessibilityShotService.isEnabled(this)) {
                    "取图服务还没就绪，等一两秒再框一次"
                } else {
                    "还不能截屏。回主界面把「屏幕取图」打开再试"
                },
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // 覆盖层刚撤掉，得给系统留一帧时间把遮罩从画面上抹掉，
        // 否则截出来的图会带着一层半透明黑罩。
        armCaptureTimeout()
        handler.postDelayed({
            captureRegion(rect, screen.width(), screen.height()) { bitmap ->
                handler.post { onCaptureResult(bitmap, rect) }
            }
        }, CAPTURE_DELAY_MS)
    }

    /**
     * 能不能取图。只有无障碍这一条通路，所以就是问它在不在。
     *
     * 查的是服务实例而不是系统开关：真的要截图必须通过实例调 takeScreenshot，
     * 系统开着但还没 bind 上的那个间隙，截也截不出来。
     */
    private fun canCapture(): Boolean = AccessibilityShotService.instance != null

    /**
     * 取一块屏幕区域。**回调可能落在后台线程**，调用方负责切回去。
     *
     * 只走无障碍通路：它跟电源键截屏同源，不建立投屏会话，
     * 所以不会被小米的「屏幕共享防护」判定成投屏而主动打码 ——
     * 微信文章走这条路能拿到原图。
     *
     * 拿不到图就回 null，由调用方给出提示。**没有备用通路**：原先那条
     * MediaProjection 会回落到打码的图上，恰好在最需要它的场景（敏感 App 里）
     * 悄悄给出一份错的东西，比明确失败更糟。
     */
    private fun captureRegion(rect: Rect, screenW: Int, screenH: Int, onDone: (Bitmap?) -> Unit) {
        val shot = AccessibilityShotService.instance
        if (shot == null) {
            Log.w(TAG, "无障碍截屏服务不在，取不到图")
            onDone(null)
            return
        }
        shot.captureScreen { full ->
            if (full == null) {
                Log.w(TAG, "无障碍截屏没拿到图")
                onDone(null)
                return@captureScreen
            }
            val cropped = cropToSelection(full, rect, screenW, screenH)
            if (cropped !== full) full.recycle()
            onDone(cropped)
        }
    }

    /** 截图完成，回主线程收尾 */
    private fun onCaptureResult(bitmap: Bitmap?, rect: Rect) {
        // 图已经到手，圆点可以露面了。放最前面是因为后面每条分支都要 return，
        // 漏掉任何一条它就会一直隐身，用户会以为圆点没了。
        // 超时兜底也一并撒掉 —— 它存在的唯一理由就是怕这一次回调不来。
        handler.removeCallbacks(captureTimeout)
        restoreDot()

        if (bitmap == null) {
            Toast.makeText(
                this,
                "截屏失败。回主界面看一眼「屏幕取图」是不是被系统关掉了",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // 先落盘再交给界面：后面送给模型时读的是文件，
        // 而不是拿 Bitmap 穿过 Intent（Parcelable 有大小上限，大图会直接崩）。
        //
        // 落盘策略（固定文件名、只留一张、写完清理）在 [PendingImage] 里 ——
        // 相册和相机两条路也走同一个槽位，三者的产物形状必须一致。
        val file = PendingImage.save(this, bitmap)
        val sizeLabel = "${bitmap.width}×${bitmap.height}"
        val weightLabel = file?.let { "${it.length() / 1024} KB" } ?: "?"
        Log.i(TAG, "截取 $sizeLabel / $weightLabel / rect=$rect")
        bitmap.recycle()

        if (file == null) {
            Toast.makeText(this, "保存截图失败", Toast.LENGTH_SHORT).show()
            return
        }

        // 结果直接落在首页，不再另开一个用完即弃的结果页 ——
        // 权限状态和使用说明已经搬到设置页去了，首页就是给结果留的
        runCatching {
            startActivity(MainActivity.captureIntent(this, file))
        }.onFailure {
            // 小米的「后台弹出界面」权限没给时，会静默失败在这里
            Log.e(TAG, "首页起不来", it)
            Toast.makeText(this, "结果页打不开 —— 检查「后台弹出界面」权限", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSelectionCancelled() {
        dismissSelectionWindow()
    }

    // ------------------------------------------------------------------ 工具

    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }

    /**
     * 长按达成的震动反馈。
     *
     * 这个提示是必需的，不是装饰：长按这条路上没有任何「看得见的结果」——
     * 圆点在同一时刻隐身，屏幕上唯一的反馈就是震这一下。
     * 没有它，用户按满那一小会儿也不知道自己成功没有。
     */
    private fun buzz() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30L)
            }
        }
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "explain_dot_overlay"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_STOP = "com.example.explaindot.action.STOP"

        /**
         * 框选层铺好之后，用户一直不动就自己撤掉的时限。
         *
         * 这不是「等待操作」的时限，而是防止用户长按完走开、全屏层一直挡着触摸——
         * 那种情况下用户会觉得整个手机都点不动了。10 秒足够宽裕，而且一旦开始划框，
         * 计时会在 onSelectionStart 里被撤掉，不会中途打断。
         */
        private const val IDLE_TIMEOUT_MS = 10_000L

        /**
         * 圆点的视觉直径。
         *
         * 原来是 44dp，挡字挡得厉害：它默认贴在屏幕右缘，而屏幕右缘正是正文
         * 最右一列字所在的位置，框选那一带时圆点会压住文字。
         * 28dp 视觉占地比 44dp 少六成（面积按平方缩），仍然一指点得中 ——
         * 手指的容错靠的是下面那个更大的窗口，不是圆点本身。
         */
        private const val DOT_DIAMETER_DP = 28f

        /**
         * 圆点窗口的边长，比圆点大一圈：手指不用点得那么准。
         *
         * 注意这个值同时决定了「圆点会吃掉多大范围的触摸」——
         * 窗口盖住的地方，底下 App 是收不到事件的。所以 64dp 偏大，收到 48dp。
         */
        private const val DOT_WINDOW_DP = 48f
        private const val DRAG_THRESHOLD_DP = 6f
        private const val MIN_SELECTION_DP = 16f

        /**
         * 抬手到取帧之间等的这一小会儿，是留给系统把框选遮罩从画面上抹掉的。
         * 太短会截到带黑罩的图，太长用户会察觉到画面「变了」。
         * 100ms ≈ 6 帧，实测够用。
         */
        private const val CAPTURE_DELAY_MS = 100L

        /**
         * 取图的总时限。
         *
         * 正常一次取图 200ms 内就回来了（无障碍通常只要几十毫秒），
         * 4 秒纯粹是「回调丢了」的兜底。设太短会在慢机器上误判，
         * 太长则圆点隐身过久 —— 用户会以为它没了。
         */
        private const val CAPTURE_TIMEOUT_MS = 4_000L

        /**
         * 圆点现在是否挂着。主界面用它决定开关显示成什么状态。
         *
         * 用 @Volatile 而不是 StateFlow：它只在「主界面回到前台时读一次」这个场景用，
         * 不需要订阅式推送，为此拉一套 Flow 的样板不划算。
         * setter 是 private，只有服务自己的生命周期能改它 —— 外部读得到，改不了。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
