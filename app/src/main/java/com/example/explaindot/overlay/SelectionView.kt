package com.example.explaindot.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.example.explaindot.ui.theme.AccentOverlayArgb
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 全屏的框选层。
 *
 * 它是一张铺满屏幕的透明窗口。圆点上长按达成时铺开，之后一直留着，
 * 直到用户框选完成、取消，或者超时。
 *
 * 为什么要单独开一个全屏窗口，而不是在圆点那个小窗口里画框：
 * 圆点窗口只有几十 dp，手指一划就出去了。同一个手势虽然能靠转发接住
 * （见 [dragFromExternal]），但用户抬手后想重新划一次时，新的一下必须落在窗内 ——
 * 只有全屏窗口接得住。
 *
 * ---------------------------------------------------------------------------
 * 状态机
 *
 *   IDLE ──滑动──▶ DRAGGING ──抬手（框够大）──▶ ADJUSTING ──点 ✓──▶ 回调 onSelectionReady
 *     │               │                              │
 *     │               └──抬手（框太小）──▶ 取消        └──点 ✕ / 手势被打断──▶ 取消
 *     └──轻点──▶ 取消（长按进来后改主意，这是唯一的退路）
 *
 * 松手不再直接截图，而是停在 ADJUSTING 让用户确认。原因很实在：
 * 手指从圆点出发拖一个框，落点很难一次就对，而且圆点本身还会压住屏幕边缘的字。
 * 一步到位等于把「框歪了」的代价转嫁给模型 —— 它只能照着歪掉的图猜。
 * 给一次调整机会，用户能自己修好，比把圆点做小有用得多。
 *
 * 调整态下三种手势，按优先级判定：
 *   1. 点按钮（✕ 取消 / ✓ 识别）—— 按钮最先判，它俩压在框外，不能和拖拽混在一起
 *   2. 拖四角手柄 —— 热区按「离哪个角最近」算，框很小时也不会抢错
 *   3. 拖框内部 —— 整体平移，尺寸不变
 *
 * 按下位置落在框外，当作「重新框一个」：直接开始新的拖拽。
 * 这样用户想重来时不用先取消，在空白处再划一下就成。
 */
class SelectionView(context: Context) : View(context) {

    interface Listener {
        /** 用户开始划框了。此时外面应该把圆点藏起来。 */
        fun onSelectionStart()

        /** 用户点了 ✓，框选定稿。rect 是屏幕坐标。 */
        fun onSelectionReady(rect: Rect)

        /** 取消，或者框太小没框住东西。 */
        fun onSelectionCancelled()
    }

    var listener: Listener? = null

    /** 小于这个边长（px）就当没框住东西 */
    var minimumSize: Float = 0f

    private enum class Mode { IDLE, DRAGGING, ADJUSTING }

    /** 调整态下抓住的是哪个部位 */
    private enum class Grip { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, INSIDE }

    private var mode = Mode.IDLE
    private var grip = Grip.NONE
    private val sel = RectF()

    /**
     * IDLE 态按下了，但还没滑够距离 —— 此刻分不清是「轻点想退出」还是「要开始划框」。
     * 长按进来之后用户可能改主意，轻点得能退出去，否则这张全屏层会一直挡着触摸。
     */
    private var pendingTap = false

    /** 本次按下的位置，以及按下那一刻的选框 —— 拖拽全靠这两个基准算增量 */
    private var downX = 0f
    private var downY = 0f
    private val downRect = RectF()

    // ------------------------------------------------------------------ 画笔

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val dimPaint = Paint().apply { color = Color.parseColor("#A6000000") }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AccentOverlayArgb
    }

    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AccentOverlayArgb
    }

    /** 手柄外面套的白圈。纯色圆点落在浅色书页上会糊成一片，加圈才有轮廓 */
    private val gripHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F2FFFFFF")
    }

    private val confirmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AccentOverlayArgb
    }

    /**
     * 取消按钮（×）的底。冷近黑，和界面中性阶同族。
     *
     * **不接主题是有意的**：它和提示气泡都压在别人家的内容上，
     * 必须与主题无关才在任何背景上都看得见。但色相要跟界面一致 ——
     * 早先这里是暖炭灰 #27221C，是上一版暖纸调留下的，整套转冷之后它还是暖的。
     */
    private val cancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E61B1F26")
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** 操作提示气泡的底。同 [cancelPaint]，冷近黑，不接主题 */
    private val hintBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC12161D")
    }

    private val hintTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val hintBounds = RectF()

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == Mode.IDLE) {
            // 一整层透明的窗口，用户看不出「现在可以划框了」。
            // 给一句提示，开始划它自己就消失
            drawHint(canvas)
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()

        // 只画选框以外的四块遮罩，不碰选框内部。
        // 比用 PorterDuff.CLEAR 挖洞稳——那样在透明窗口上容易因为没开图层而失效。
        canvas.drawRect(0f, 0f, w, sel.top, dimPaint)
        canvas.drawRect(0f, sel.bottom, w, h, dimPaint)
        canvas.drawRect(0f, sel.top, sel.left, sel.bottom, dimPaint)
        canvas.drawRect(sel.right, sel.top, w, sel.bottom, dimPaint)

        borderPaint.strokeWidth = dp(2.5f).coerceAtLeast(2f)
        canvas.drawRect(sel, borderPaint)

        // 只在调整态画手柄和按钮。拖拽过程中画了反而碍眼 ——
        // 那时候手指正压着框边，任何附加图形都在干扰瞄准
        if (mode == Mode.ADJUSTING) {
            drawGrips(canvas)
            drawButtons(canvas)
        }
    }

    private fun drawGrips(canvas: Canvas) {
        val r = dp(GRIP_RADIUS_DP)
        val halo = dp(2f)
        gripCorners().forEach { (x, y) ->
            canvas.drawCircle(x, y, r + halo, gripHaloPaint)
            canvas.drawCircle(x, y, r, gripPaint)
        }
    }

    private fun drawButtons(canvas: Canvas) {
        val (confirm, cancel) = buttonRects()
        val d = confirm.width()
        val k = d * 0.17f

        iconPaint.strokeWidth = dp(3f).coerceAtLeast(3f)

        // 取消：深灰底 + 白叉
        canvas.drawCircle(cancel.centerX(), cancel.centerY(), d / 2f, cancelPaint)
        val cx = cancel.centerX()
        val cy = cancel.centerY()
        canvas.drawLine(cx - k, cy - k, cx + k, cy + k, iconPaint)
        canvas.drawLine(cx + k, cy - k, cx - k, cy + k, iconPaint)

        // 识别：蓝底 + 白勾
        canvas.drawCircle(confirm.centerX(), confirm.centerY(), d / 2f, confirmPaint)
        val tx = confirm.centerX()
        val ty = confirm.centerY()
        canvas.drawLine(tx - k, ty, tx - k * 0.15f, ty + k * 0.62f, iconPaint)
        canvas.drawLine(tx - k * 0.15f, ty + k * 0.62f, tx + k * 0.9f, ty - k * 0.55f, iconPaint)
    }

    /**
     * IDLE 态的提示条。
     *
     * 放在屏幕顶部而不是居中：用户的手指此刻还停在圆点上（多半在屏幕右侧中段），
     * 顶部是当下最不会被手挡住、也最不干扰阅读的位置。
     */
    private fun drawHint(canvas: Canvas) {
        hintTextPaint.textSize = dp(HINT_TEXT_SP)
        val textWidth = hintTextPaint.measureText(HINT_TEXT)
        val padH = dp(16f)
        val boxHeight = dp(36f)
        val cx = width / 2f
        val top = dp(HINT_TOP_DP)

        hintBounds.set(cx - textWidth / 2f - padH, top, cx + textWidth / 2f + padH, top + boxHeight)
        canvas.drawRoundRect(hintBounds, boxHeight / 2f, boxHeight / 2f, hintBgPaint)

        // 垂直居中的基线算法：把字体的上下留白各去掉一半
        val baseline = top + boxHeight / 2f - (hintTextPaint.descent() + hintTextPaint.ascent()) / 2f
        canvas.drawText(HINT_TEXT, cx, baseline, hintTextPaint)
    }

    // ------------------------------------------------------------------ 外部驱动

    /**
     * 由外面那一段手势驱动画框。
     *
     * 长按是「同一个手指」完成的：达成那一刻手指还按在屏幕上，而这个手势在系统那里
     * 已经认给了圆点窗口，框选层收不到它的 DOWN。所以圆点那边把后续的 MOVE 转过来，
     * 由这里接住 —— 用户长按完直接接着滑，不用松手重来一次。
     */
    fun dragFromExternal(x: Float, y: Float) {
        when (mode) {
            Mode.IDLE -> {
                pendingTap = false
                beginDrag(x, y)
            }

            Mode.DRAGGING -> {
                updateSelection(x, y)
                invalidate()
            }

            // 已经在调整了还传来外部手势，忽略 —— 这时候用户该拖手柄，不是重画一个
            Mode.ADJUSTING -> Unit
        }
    }

    /** 外部那段手势抬手。框还没画出来就什么都不做，让窗口留在 IDLE 等用户重新划。 */
    fun endExternal() {
        if (mode == Mode.DRAGGING) handleUp()
    }

    private fun gripCorners(): List<Pair<Float, Float>> = listOf(
        sel.left to sel.top,
        sel.right to sel.top,
        sel.left to sel.bottom,
        sel.right to sel.bottom
    )

    /**
     * 两个圆钮的位置。绘制和命中测试共用同一个算法 ——
     * 分成两处写的话，改了一边忘了另一边，就会出现「看得见却点不中」。
     *
     * 摆放顺序：默认挂在选框下方；下方放不下挪到上方；都放不下（框几乎占满屏幕）
     * 就压在框内底部。整体右对齐选框右边，符合「确认在右」的习惯。
     */
    private fun buttonRects(): Pair<RectF, RectF> {
        val d = dp(BUTTON_DP)
        val gap = dp(BUTTON_GAP_DP)
        val margin = dp(BUTTON_MARGIN_DP)

        val below = sel.bottom + margin
        val above = sel.top - margin - d
        val top = when {
            below + d <= height - margin -> below
            above >= margin -> above
            else -> (sel.bottom - margin - d).coerceAtLeast(margin)
        }

        val rowWidth = d * 2 + gap
        var right = min(sel.right, width - margin)
        if (right - rowWidth < margin) right = rowWidth + margin

        val confirm = RectF(right - d, top, right, top + d)
        val cancel = RectF(confirm.left - gap - d, top, confirm.left - gap, top + d)
        return confirm to cancel
    }

    // ------------------------------------------------------------------ 触摸

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 一律用屏幕坐标。窗口本身虽然铺满屏幕，但 FLAG_LAYOUT_NO_LIMITS 下
        // 它可能被摆到屏幕外，用局部坐标算会整体偏移
        val x = event.rawX
        val y = event.rawY

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(x, y)
            MotionEvent.ACTION_MOVE -> handleMove(x, y)
            MotionEvent.ACTION_UP -> handleUp()
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun handleDown(x: Float, y: Float) {
        if (mode == Mode.ADJUSTING) {
            val (confirm, cancelRect) = buttonRects()
            if (confirm.contains(x, y)) {
                // 用 post 把回调挪出这一轮事件分发：外面收到回调会立刻把这个窗口
                // 从 WindowManager 上摘掉，而「在 dispatchTouchEvent 里摘掉自己」
                // 会让后续 MOVE/UP 派发给一个已经不在树上的 View
                val rect = currentRect()
                post { listener?.onSelectionReady(rect) }
                return
            }
            if (cancelRect.contains(x, y)) {
                post { listener?.onSelectionCancelled() }
                return
            }

            when (val hit = hitGrip(x, y)) {
                Grip.NONE -> {
                    // 落在框外：当作「重新框一个」，比让用户先取消再重来少两步
                    beginDrag(x, y)
                }

                else -> {
                    grip = hit
                    downX = x
                    downY = y
                    downRect.set(sel)
                }
            }
            return
        }

        // IDLE：先记下按点，不急着开框。轻点要能退出，划动才算框选 ——
        // 长按进来之后用户可能改主意，得留一条退路，不然全屏层会一直挡着触摸
        pendingTap = true
        grip = Grip.NONE
        downX = x
        downY = y
        sel.set(x, y, x, y)
    }

    private fun handleMove(x: Float, y: Float) {
        when (mode) {
            Mode.IDLE -> {
                if (!pendingTap) return
                if (hypot(x - downX, y - downY) <= dp(TAP_SLOP_DP)) return
                // 滑够距离了，确定是要框选。起点用按下那一刻的位置，
                // 而不是当前点 —— 否则框会少掉开头这一小段
                pendingTap = false
                beginDrag(downX, downY)
                updateSelection(x, y)
                invalidate()
            }

            Mode.DRAGGING -> {
                updateSelection(x, y)
                invalidate()
            }

            Mode.ADJUSTING -> {
                if (grip == Grip.NONE) return
                applyGrip(x, y)
                invalidate()
            }
        }
    }

    /** 以按下点为锚，把选框拉到 (x, y)。归一化成 left/top 在前、right/bottom 在后 */
    private fun updateSelection(x: Float, y: Float) {
        sel.set(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
    }

    private fun handleUp() {
        when (mode) {
            Mode.DRAGGING -> {
                if (sel.width() < minimumSize || sel.height() < minimumSize) {
                    // 划是划了，但太小。当成取消，不去猜用户想干什么。
                    cancel()
                } else {
                    // 不直接截图，停下来等用户确认
                    mode = Mode.ADJUSTING
                    invalidate()
                }
            }

            Mode.ADJUSTING -> grip = Grip.NONE

            Mode.IDLE -> {
                if (!pendingTap) return
                // 长按进入框选模式后轻点一下 = 不想框了。
                // 这是用户唯一的「退出」动作，得让它生效
                pendingTap = false
                listener?.onSelectionCancelled()
            }
        }
    }

    private fun beginDrag(x: Float, y: Float) {
        pendingTap = false
        mode = Mode.DRAGGING
        grip = Grip.NONE
        downX = x
        downY = y
        sel.set(x, y, x, y)
        listener?.onSelectionStart()
        invalidate()
    }

    private fun cancel() {
        pendingTap = false
        val wasIdle = mode == Mode.IDLE
        mode = Mode.IDLE
        grip = Grip.NONE
        invalidate()
        // IDLE 态收到 CANCEL 说明手势被系统打断了（比如通知抢走焦点）。
        // 窗口留着让用户重新划一次，不要顺势把整个框选模式一起关掉
        if (!wasIdle) listener?.onSelectionCancelled()
    }

    private fun currentRect(): Rect = Rect(
        sel.left.roundToInt(),
        sel.top.roundToInt(),
        sel.right.roundToInt(),
        sel.bottom.roundToInt()
    )

    // ------------------------------------------------------------------ 命中与几何

    /**
     * 判断按在哪个部位上。四角按「离得最近的那个」算，
     * 而不是「先匹配到谁算谁」—— 框小的时候四个热区会叠在一起，
     * 按顺序匹配会让右下角永远抢不到。
     */
    private fun hitGrip(x: Float, y: Float): Grip {
        val radius = dp(GRIP_TOUCH_RADIUS_DP)
        var best = Grip.NONE
        var bestDistance = Float.MAX_VALUE

        fun consider(candidate: Grip, cx: Float, cy: Float) {
            val distance = hypot(x - cx, y - cy)
            if (distance <= radius && distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }

        consider(Grip.TOP_LEFT, sel.left, sel.top)
        consider(Grip.TOP_RIGHT, sel.right, sel.top)
        consider(Grip.BOTTOM_LEFT, sel.left, sel.bottom)
        consider(Grip.BOTTOM_RIGHT, sel.right, sel.bottom)

        if (best != Grip.NONE) return best
        return if (sel.contains(x, y)) Grip.INSIDE else Grip.NONE
    }

    /**
     * 把拖动落到选框上。所有分支都夹在屏幕范围内 ——
     * 选到屏幕外没有意义，MediaProjection 只会给你一片黑。
     */
    private fun applyGrip(x: Float, y: Float) {
        val maxX = width.toFloat()
        val maxY = height.toFloat()
        val minSize = minimumSize

        when (grip) {
            Grip.INSIDE -> {
                val left = (downRect.left + (x - downX))
                    .coerceIn(0f, (maxX - downRect.width()).coerceAtLeast(0f))
                val top = (downRect.top + (y - downY))
                    .coerceIn(0f, (maxY - downRect.height()).coerceAtLeast(0f))
                sel.set(left, top, left + downRect.width(), top + downRect.height())
            }

            Grip.TOP_LEFT -> {
                sel.left = x.coerceIn(0f, sel.right - minSize)
                sel.top = y.coerceIn(0f, sel.bottom - minSize)
            }

            Grip.TOP_RIGHT -> {
                sel.right = x.coerceIn(sel.left + minSize, maxX)
                sel.top = y.coerceIn(0f, sel.bottom - minSize)
            }

            Grip.BOTTOM_LEFT -> {
                sel.left = x.coerceIn(0f, sel.right - minSize)
                sel.bottom = y.coerceIn(sel.top + minSize, maxY)
            }

            Grip.BOTTOM_RIGHT -> {
                sel.right = x.coerceIn(sel.left + minSize, maxX)
                sel.bottom = y.coerceIn(sel.top + minSize, maxY)
            }

            Grip.NONE -> Unit
        }
    }

    private companion object {
        /** 手柄的可见半径 */
        const val GRIP_RADIUS_DP = 9f

        /** IDLE 态判定「轻点」的位移上限。手指按下去总会抖几 px，不能要求完全不动 */
        const val TAP_SLOP_DP = 6f

        /** 提示条距屏幕顶部的距离 —— 避开状态栏和挖孔 */
        const val HINT_TOP_DP = 72f

        /** 提示条字号 */
        const val HINT_TEXT_SP = 13f

        /** IDLE 态提示文案 */
        const val HINT_TEXT = "划一下开始框选，轻点退出"

        /** 手柄的触摸热区半径。比可见尺寸大一圈，手指没那么准 */
        const val GRIP_TOUCH_RADIUS_DP = 26f

        /** 确认 / 取消两个圆钮的直径 */
        const val BUTTON_DP = 46f

        /** 两个圆钮的间距 */
        const val BUTTON_GAP_DP = 12f

        /** 圆钮与选框、与屏幕边缘的留白 */
        const val BUTTON_MARGIN_DP = 14f
    }
}
