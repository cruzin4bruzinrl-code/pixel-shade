package se.rob.pixelpanel

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)

/** Quick settings tile. wide = 2x1 with label, otherwise 1x1 icon only. */
class Tile(
    ctx: Context,
    private val pal: Palette,
    val glyph: Glyph,
    val label: String,
    var secondary: String,
    val wide: Boolean,
    initiallyOn: Boolean,
) : View(ctx) {

    var on = initiallyOn
        private set
    var onToggle: ((Tile, Boolean) -> Unit)? = null

    private val restRadius = ctx.dp(if (wide) 24f else 22f)
    private val pressRadius = ctx.dp(12f)
    private var radius = restRadius
    private var fillColor = if (on) pal.primary else pal.surfaceContainer
    private var fgColor = if (on) pal.onPrimary else pal.onSurface

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = ctx.dp(14f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val secPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dp(12f) }
    private val iconBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var radiusAnim: ValueAnimator? = null
    private var colorAnim: ValueAnimator? = null

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
    }

    fun setOn(value: Boolean, animate: Boolean = true) {
        if (value == on) return
        on = value
        val fromFill = fillColor; val toFill = if (on) pal.primary else pal.surfaceContainer
        val fromFg = fgColor; val toFg = if (on) pal.onPrimary else pal.onSurface
        colorAnim?.cancel()
        if (!animate) { fillColor = toFill; fgColor = toFg; invalidate(); return }
        val ev = ArgbEvaluator()
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250; interpolator = EMPHASIZED
            addUpdateListener {
                val f = it.animatedValue as Float
                fillColor = ev.evaluate(f, fromFill, toFill) as Int
                fgColor = ev.evaluate(f, fromFg, toFg) as Int
                invalidate()
            }
            start()
        }
    }

    private fun animateRadius(to: Float) {
        radiusAnim?.cancel()
        radiusAnim = ValueAnimator.ofFloat(radius, to).apply {
            duration = 180; interpolator = EMPHASIZED
            addUpdateListener { radius = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animateRadius(pressRadius)
            MotionEvent.ACTION_UP -> {
                animateRadius(restRadius)
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                setOn(!on)
                onToggle?.invoke(this, on)
            }
            MotionEvent.ACTION_CANCEL -> animateRadius(restRadius)
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        bg.color = fillColor
        c.drawRoundRect(r, radius, radius, bg)
        val iconSize = context.dp(22f)
        if (!wide) {
            Icons.draw(c, glyph, width / 2f, height / 2f, iconSize, fgColor)
            return
        }
        // Icon sits in its own circle on wide tiles, like the Pixel 10 large tiles.
        val pad = context.dp(12f)
        val circle = height - pad * 2
        val ccx = pad + circle / 2; val ccy = height / 2f
        iconBg.color = if (on) pal.primaryContainer.withAlpha(0) else pal.surfaceContainerHigh
        if (!on) c.drawCircle(ccx, ccy, circle / 2, iconBg)
        Icons.draw(c, glyph, ccx, ccy, iconSize, fgColor)

        val tx = pad + circle + context.dp(10f)
        val avail = width - tx - pad
        labelPaint.color = fgColor
        secPaint.color = fgColor.withAlpha(if (on) 200 else 170)
        val lbl = TextUtils.ellipsize(label, android.text.TextPaint(labelPaint), avail, TextUtils.TruncateAt.END).toString()
        val sec = TextUtils.ellipsize(secondary, android.text.TextPaint(secPaint), avail, TextUtils.TruncateAt.END).toString()
        c.drawText(lbl, tx, height / 2f - context.dp(2f), labelPaint)
        c.drawText(sec, tx, height / 2f + context.dp(15f), secPaint)
    }
}

fun Int.withAlpha(a: Int): Int = (this and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

/** Thick Expressive brightness slider. Visual only in the test build. */
class BrightnessSlider(ctx: Context, private val pal: Palette) : View(ctx) {
    var value = 0.55f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val clip = Path()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val nv = ((e.x - height / 2f) / (width - height)).coerceIn(0f, 1f)
                if ((nv == 0f || nv == 1f) && nv != value) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                value = nv; invalidate()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        r.set(0f, 0f, w, h)
        clip.reset(); clip.addRoundRect(r, h / 2, h / 2, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        track.color = pal.surfaceContainer
        c.drawRect(r, track)
        val fx = h + (w - h) * value
        fill.color = pal.primary
        r.set(0f, 0f, fx, h)
        c.drawRoundRect(r, h / 2, h / 2, fill)
        c.restore()
        Icons.draw(c, Glyph.SUN, h / 2, h / 2, context.dp(20f), pal.onPrimary)
    }
}

/** One fake notification card. Corners are set by the stack it lives in. */
class NotifCard(ctx: Context, pal: Palette, app: String, time: String, title: String, text: String, appColor: Int) :
    LinearLayout(ctx) {
    val bg = GradientDrawable().apply { setColor(pal.card) }

    init {
        orientation = HORIZONTAL
        background = bg
        val p = ctx.dpi(16f)
        setPadding(p, ctx.dpi(14f), p, ctx.dpi(14f))
        val icon = object : View(ctx) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = appColor }
            override fun onDraw(c: Canvas) { c.drawCircle(width / 2f, height / 2f, width / 2f, paint) }
        }
        addView(icon, LayoutParams(ctx.dpi(32f), ctx.dpi(32f)).apply { marginEnd = ctx.dpi(14f) })
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(TextView(ctx).apply {
            this.text = "$app · $time"; textSize = 12f; setTextColor(pal.onSurfaceVariant); maxLines = 1
        })
        col.addView(TextView(ctx).apply {
            this.text = title; textSize = 15f; setTextColor(pal.onSurface); maxLines = 1
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        col.addView(TextView(ctx).apply {
            this.text = text; textSize = 14f; setTextColor(pal.onSurfaceVariant); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}

/**
 * Stack of notification cards with Pixel-style grouped corners and
 * swipe-to-dismiss where the neighbours are dragged along a little.
 */
class NotifStack(ctx: Context) : LinearLayout(ctx) {
    private val outer = ctx.dp(28f)
    private val inner = ctx.dp(6f)
    private val gap = ctx.dpi(2f)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var target: View? = null
    private var swiping = false
    private var passedThreshold = false
    private var vt: VelocityTracker? = null
    var onEmpty: (() -> Unit)? = null

    init { orientation = VERTICAL; clipChildren = false }

    fun addCard(card: NotifCard) {
        addView(card, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = if (childCount == 0) 0 else gap
        })
        updateCorners()
    }

    private fun updateCorners() {
        val n = childCount
        for (i in 0 until n) {
            val card = getChildAt(i) as NotifCard
            val top = if (i == 0) outer else inner
            val bottom = if (i == n - 1) outer else inner
            card.bg.cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
            (card.layoutParams as LayoutParams).topMargin = if (i == 0) 0 else gap
        }
        requestLayout()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; swiping = false; passedThreshold = false
                target = childAt(e.y)
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX; val dy = e.y - downY
                if (target != null && abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
                    swiping = true
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    private fun childAt(y: Float): View? {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (y >= c.top && y <= c.bottom) return c
        }
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val t = target ?: return false
        if (e.actionMasked != MotionEvent.ACTION_DOWN) vt?.addMovement(e)
        if (e.actionMasked == MotionEvent.ACTION_MOVE && !swiping) {
            val dx = e.x - downX; val dy = e.y - downY
            if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
                swiping = true
                parent.requestDisallowInterceptTouchEvent(true)
            }
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (swiping) {
                val dx = e.x - downX
                t.translationX = dx
                t.alpha = 1f - min(1f, abs(dx) / width) * 0.4f
                pullNeighbours(t, dx)
                val crossed = abs(dx) > width * 0.4f
                if (crossed != passedThreshold) {
                    passedThreshold = crossed
                    performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                        else HapticFeedbackConstants.CONTEXT_CLICK
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val dx = t.translationX
                val dismiss = abs(dx) > width * 0.4f || (abs(vx) > 1500 && vx * dx > 0)
                if (dismiss) dismiss(t, if (dx == 0f) vx else dx) else settle(t)
                swiping = false; target = null
            }
        }
        return true
    }

    private fun pullNeighbours(t: View, dx: Float) {
        val i = indexOfChild(t)
        for (j in 0 until childCount) {
            if (j == i) continue
            val d = abs(j - i)
            getChildAt(j).translationX = if (d == 1) dx * 0.12f else if (d == 2) dx * 0.04f else 0f
        }
    }

    private fun settle(t: View) {
        for (j in 0 until childCount) {
            getChildAt(j).animate().translationX(0f).alpha(1f).setDuration(350)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        }
    }

    fun dismiss(t: View, dir: Float) {
        val sign = if (dir >= 0) 1f else -1f
        t.animate().translationX(sign * width * 1.1f).alpha(0f).setDuration(200).setInterpolator(EMPHASIZED)
            .withEndAction { collapse(t) }.start()
        for (j in 0 until childCount) if (getChildAt(j) !== t) {
            getChildAt(j).animate().translationX(0f).setDuration(300)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
        }
    }

    private fun collapse(t: View) {
        val h = t.height
        ValueAnimator.ofInt(h, 0).apply {
            duration = 250; interpolator = EMPHASIZED
            addUpdateListener {
                t.layoutParams.height = it.animatedValue as Int
                t.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    removeView(t); updateCorners()
                    if (childCount == 0) onEmpty?.invoke()
                }
            })
            start()
        }
    }

    fun clearAll() {
        val n = childCount
        for (i in 0 until n) {
            val c = getChildAt(n - 1 - i)
            postDelayed({ if (c.parent === this) dismiss(c, 1f) }, i * 60L)
        }
    }
}

/**
 * Full-screen root of the shade window. Intercepts upward drags to close the
 * shade, taps outside the content, and the back key.
 */
class ShadeRoot(ctx: Context) : FrameLayout(ctx) {
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((Float) -> Unit)? = null
    var onRelease: ((Float) -> Unit)? = null
    var onTapOutside: ((Float, Float) -> Unit)? = null
    var onBack: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null

    init { isFocusableInTouchMode = true }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dy = e.rawY - downY; val dx = e.rawX - downX
                if (dy < -slop && abs(dy) > abs(dx)) {
                    dragging = true; onDragStart?.invoke(); return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dy = e.rawY - downY
                if (!dragging && dy < -slop) { dragging = true; onDragStart?.invoke() }
                if (dragging) onDrag?.invoke(min(0f, dy))
            }
            MotionEvent.ACTION_UP -> {
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                if (dragging) onRelease?.invoke(vt?.yVelocity ?: 0f)
                else onTapOutside?.invoke(e.rawX, e.rawY)
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onRelease?.invoke(0f)
                dragging = false
            }
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

fun pill(ctx: Context, pal: Palette, text: String, onClick: () -> Unit): TextView =
    TextView(ctx).apply {
        this.text = text; textSize = 14f; setTextColor(pal.onSurface)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(ctx.dpi(18f), ctx.dpi(10f), ctx.dpi(18f), ctx.dpi(10f))
        background = GradientDrawable().apply { setColor(pal.card); cornerRadius = ctx.dp(100f) }
        setOnClickListener { onClick() }
    }

fun maxOf3(a: Float, b: Float, c: Float) = max(a, max(b, c))
