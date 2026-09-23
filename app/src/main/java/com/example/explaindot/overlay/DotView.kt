package com.example.explaindot.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.example.explaindot.R
import com.example.explaindot.ui.theme.AccentOverlayArgb
import kotlin.math.hypot

/**
 * 那个常驻的小圆点本身。
 *
 * 手势只有一条主线：**按住不放**。
 *
 *   原地按住满 [LONG_PRESS_MS] → onLongPress，进入框选；手指接着滑就继续画框
 *   按住期间移动   → 变成拖动，挪动圆点位置
 *   点一下         → 什么都不做
 *
 * 为什么不做成「单击 / 双击」：双击要求用户先精确点两下，第二下还必须落在圆点
 * 那个几十 dp 的小窗口里；而长按只有一个要求 —— 按住别动。对一颗常驻在阅读界面
 * 上的小圆点来说，后者容错高得多，也不会在等第二下的那 300ms 里白吃掉别的触摸。
 *
 * 长按达成后手指通常还按在屏幕上，所以这个 View 会把**同一个手势**继续转成
 * onLongPressMove / onLongPressEnd 交给外面：用户长按完可以直接接着划框，
 * 不用先松手、再重新按一次。
 *
 * 坐标系有个容易踩的坑：窗口本身在被拖动，View 的局部坐标会跟着窗口一起动，
 * 用它算位移会得到错的结果。所以这里**一律用 MotionEvent.rawX / rawY（屏幕坐标）**。
 */
class DotView(context: Context) : View(context) {

    interface Listener {
        /** 手指按下。Service 在这里记下窗口当前位置，作为拖动基准。 */
        fun onPress()

        /** 拖动中。dx / dy 是相对按下点的位移（屏幕坐标）。 */
        fun onDrag(dx: Float, dy: Float)

        /** 抬手。moved 表示这次是不是真的拖动过——false 就是一次「单击」。 */
        fun onRelease(moved: Boolean)

        /** 原地按住满 [LONG_PRESS_MS]。外面在这里震动 + 铺框选层。 */
        fun onLongPress()

        /** 长按达成后，手指继续移动（屏幕坐标）。同一个手势接着画框。 */
        fun onLongPressMove(x: Float, y: Float)

        /** 长按达成后的抬手。 */
        fun onLongPressEnd()
    }

    var listener: Listener? = null

    /** 圆点直径，px，由 Service 按 dp 换算后塞进来 */
    var dotDiameter: Float = 0f

    /** 判定「算不算拖动」的距离阈值，px。比圆点半径略大，避免手抖误判 */
    var dragThreshold: Float = 0f

    private val density: Float get() = resources.displayMetrics.density

    /**
     * 圆点本体。`dot_gradient.png`：**径向渐变，边缘紫 → 中心青**，透明底、
     * 图形外边留了一圈透明边距（圆只占画布的 87.5%，128px 图里四周各留 8px）。
     *
     * 色值是在 128px 原图上实测的：边缘 `#8058ED`、中心 `#21F4D2`
     * （生成脚本 tools/gradient_dot.py 的端点写的是 `#854FEE` / `#20F6D2`；
     * 圆心正好落在四个像素的交叉点上，没有任何像素取到 t=1，所以最中心那个
     * 像素是 `#21F4D2`，差 2/255，看不出来）。
     *
     * **一定要按 alpha 边界裁掉那圈边距。** 否则把整张图缩到 dotDiameter，
     * 真正可见的圆就只有它的 87.5% —— 而长按进度环是按 dotDiameter 算半径的，
     * 环和圆之间会凭空多出一道缝，看着像画错了。
     * 裁剪边界是扫出来的、不是写死比例：以后换一张留白不同的图也不会错位。
     *
     * 懒加载 + 只做一次。原图 128px，就算缩到 xxxhdpi 上的 91px 也就一次重采样；
     * 之后每帧画的是同一张已裁好的位图，交给 GPU 缩放。
     *
     * **径向相比早先那版对角渐变，换来的是「整圈轮廓同色同对比度」。**
     * 那版是左上紫 → 右下青的线性渐变，于是右下那一段边缘也是青的，
     * 压在白底上只有约 1.5:1 —— 圆点在白底网页上右下半圈是虚的。
     * 现在边缘一圈都是同一种紫，对白 4.63:1、对深底(#0F1216) 4.06:1，
     * 任何背景上轮廓都立得住，不再需要「画完补一圈细白环」那个后备方案。
     *
     * 代价是中心那个青变成了纯装饰：对白只有 1.41:1，几乎融进白底。
     * 但它在形状内部、不承担轮廓职责，白底上看起来就是「紫色圆点中间透出一点光」。
     * 反过来在深色内容上（夜间模式、深色照片）它 13.35:1，是明确的视觉重心 ——
     * 这一版把对比度预算全部押在「浅底看形状、深底看中心」上。
     */
    private val dotImage: Bitmap? by lazy { loadDotImage() }

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dotBounds = RectF()

    /** 长按进度环。蓝色跟框选层保持一致，让「按下去会发生什么」一眼可预期 */
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AccentOverlayArgb
        strokeCap = Paint.Cap.ROUND
    }

    private val progressBounds = RectF()

    private var downRawX = 0f
    private var downRawY = 0f
    private var moved = false

    /** 长按是否已经达成。达成之后整个手势都走另一个分支 */
    private var longPressed = false

    /** 进度环走到哪了，0..1。纯视觉，不承担触发时机 */
    private var pressProgress = 0f

    /**
     * 长按倒计时。到点就认账。
     *
     * 触发时机用 Handler 而不是动画的 end 回调：ValueAnimator.cancel() 同样会走
     * onAnimationEnd，靠它触发就得再拿一个标志位去区分「跑完」和「被取消」，容易出岔子。
     */
    private val longPressRunnable = Runnable {
        longPressed = true
        progressAnimator.cancel()
        pressProgress = 0f
        invalidate()
        listener?.onLongPress()
    }

    private val progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = LONG_PRESS_MS
        interpolator = LinearInterpolator()
        addUpdateListener {
            pressProgress = it.animatedValue as Float
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // 被取消（手指动了或抬手了）就把环擦掉；正常跑完时 onAnimationEnd
                // 早于 longPressRunnable，这里不能抢先清零，交给 runnable 自己收尾
                if (!longPressed && pressProgress != 0f) {
                    pressProgress = 0f
                    invalidate()
                }
            }
        })
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        // 窗口比圆点大一圈，好让手指不用点得那么准；圆点画在正中间
        val r = (dotDiameter / 2f).coerceAtMost(minOf(cx, cy))

        // 圆点本体就是这张图，不再叠任何自绘的圆环或白心 ——
        // 图本身是一整套观感，再往上加东西只会互相打架
        dotImage?.let { image ->
            dotBounds.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawBitmap(image, null, dotBounds, imagePaint)
        }

        if (pressProgress > 0f) {
            val ringRadius = r + density * PROGRESS_GAP_DP
            progressPaint.strokeWidth = density * PROGRESS_WIDTH_DP
            progressBounds.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
            // 从正上方顺时针走满一圈，走满即触发（时长 = LONG_PRESS_MS）
            canvas.drawArc(progressBounds, -90f, 360f * pressProgress, false, progressPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                moved = false
                longPressed = false
                alpha = 0.7f
                startProgress()
                listener?.onPress()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // 长按已达成：这个手势接下来归外面（框选层）用，
                // 注意事件仍然发到我们这个窗口，所以要主动转出去
                if (longPressed) {
                    listener?.onLongPressMove(event.rawX, event.rawY)
                    return true
                }

                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!moved && hypot(dx, dy) > dragThreshold) {
                    moved = true
                    // 手一动就说明不是长按，环立刻擦掉，别让用户以为还在读秒
                    abortProgress()
                }
                if (moved) listener?.onDrag(dx, dy)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                abortProgress()
                if (longPressed) {
                    // 这里**不碰 alpha**：长按之后圆点要保持隐身，
                    // 什么时候放它出来由外面决定（截图拿到图之后）
                    longPressed = false
                    listener?.onLongPressEnd()
                } else {
                    alpha = 1f
                    listener?.onRelease(moved)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun startProgress() {
        removeCallbacks(longPressRunnable)
        progressAnimator.cancel()
        pressProgress = 0f
        progressAnimator.start()
        postDelayed(longPressRunnable, LONG_PRESS_MS)
    }

    private fun abortProgress() {
        removeCallbacks(longPressRunnable)
        progressAnimator.cancel()
        if (pressProgress != 0f) {
            pressProgress = 0f
            invalidate()
        }
    }

    // ------------------------------------------------------------------ 圆点位图

    /**
     * 解出圆点，并按 alpha 边界裁掉透明边距。
     *
     * 解不出来就返回 null —— 画不出来比崩掉好：那样屏幕上还剩长按进度环，
     * 用户至少知道按住这里会发生什么。
     */
    private fun loadDotImage(): Bitmap? {
        val source = runCatching {
            BitmapFactory.decodeResource(resources, R.drawable.dot_gradient)
        }.getOrNull() ?: return null

        val bounds = opaqueBounds(source)
        return runCatching {
            if (bounds.left == 0 && bounds.top == 0 &&
                bounds.width() == source.width && bounds.height() == source.height
            ) {
                source
            } else {
                Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width(), bounds.height())
            }
        }.getOrNull()
    }

    /**
     * 扫出图形的不透明包围盒。
     *
     * 阈值取 [ALPHA_THRESHOLD] 而不是 0：抗锯齿的边缘上有一圈 alpha 极低的像素，
     * 按 0 算包围盒会往外多出一两个像素 —— 缩到圆点上就是一圈没用的毛边。
     */
    private fun opaqueBounds(bitmap: Bitmap): Rect {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((pixels[row + x] ushr 24) < ALPHA_THRESHOLD) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        // 整张图都是透明的（图坏了）：退回整幅，让上面那步自己判断
        if (right < left || bottom < top) return Rect(0, 0, w, h)
        return Rect(left, top, right + 1, bottom + 1)
    }

    companion object {
        /**
         * 长按多久算数。
         *
         * 0.4 秒，追求「按一下就能划」的即时感。代价是留给用户「滑出去改成拖动」的
         * 窗口变短了 —— 短按之后手指若有停顿再动，会被判成长按而不是拖动。
         * 真误触了就往回调，或把 [DRAG_THRESHOLD_DP] 相应调大。
         */
        const val LONG_PRESS_MS = 400L

        /** 进度环与圆点之间的空隙 */
        private const val PROGRESS_GAP_DP = 4f

        /** 进度环的粗细 */
        private const val PROGRESS_WIDTH_DP = 2.5f

        /**
         * 判「这个像素算不算图形」的 alpha 下限（0..255）。
         *
         * **对当前这张 `dot_gradient.png` 它其实是个空操作** —— 实测阈值取 1 到 32
         * 扫出来的包围盒一模一样，都是 112x112。抗锯齿边缘上确实有 alpha 只有
         * 2 和 6 的像素（16 个，正好是圆弧与包围盒四条边相切的那几点），
         * 但同一条边线上还压着 alpha 210/228/255 的像素，真正撑出包围盒的是后者。
         *
         * 留着是为了下一张图：万一换的素材边缘更虚，最外层就只剩低 alpha 的毛边，
         * 那时按 0 算就会多出一两个像素 —— 缩到圆点上正好是一圈脏边。
         * 所以这是个便宜的保险，而不是在修一个已经发生的问题。
         */
        private const val ALPHA_THRESHOLD = 8
    }
}
