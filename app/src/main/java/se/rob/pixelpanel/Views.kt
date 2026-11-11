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
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)

/** Bundled typefaces: Google Sans Flex (OFL) and Material Symbols Rounded (Apache-2.0). */
object Fonts {
    lateinit var regular: Typeface; private set
    lateinit var medium: Typeface; private set
    lateinit var display: Typeface; private set
    lateinit var symFilled: Typeface; private set
    lateinit var symOutline: Typeface; private set

    fun init(ctx: Context) {
        if (::regular.isInitialized) return
        val r = ctx.resources
        regular = r.getFont(R.font.gsf_regular)
        medium = r.getFont(R.font.gsf_medium)
        display = r.getFont(R.font.gsf_display)
        symFilled = r.getFont(R.font.symbols_filled)
        symOutline = r.getFont(R.font.symbols_outline)
    }
}

fun Int.withAlpha(a: Int): Int = (this and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

fun Context.label(text: CharSequence?, sp: Float, color: Int, medium: Boolean = false, lines: Int = 1) =
    TextView(this).apply {
        this.text = text; textSize = sp; setTextColor(color)
        typeface = if (medium) Fonts.medium else Fonts.regular
        includeFontPadding = false
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    }

/** A Material Symbol as a view. */
class SymbolView(ctx: Context, var symbol: String, var color: Int, var sizeDp: Float, var filled: Boolean = true) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    override fun onDraw(c: Canvas) {
        p.typeface = if (filled) Fonts.symFilled else Fonts.symOutline
        p.textSize = context.dp(sizeDp); p.color = color
        val fm = p.fontMetrics
        c.drawText(symbol, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, p)
    }
}

fun drawSymbol(c: Canvas, sym: String, cx: Float, cy: Float, size: Float, color: Int, filled: Boolean) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = if (filled) Fonts.symFilled else Fonts.symOutline
        textSize = size; this.color = color
    }
    val fm = p.fontMetrics
    c.drawText(sym, cx, cy - (fm.ascent + fm.descent) / 2f, p)
}

/** Quick settings tile. wide = 2x1 with label, otherwise 1x1 icon only. */
class Tile(
    ctx: Context,
    private val pal: Palette,
    var symbol: String,
    val label: String,
    secondary: String,
    val wide: Boolean,
) : View(ctx) {

    var on = false
        private set
    var secondary: String = secondary
        set(v) { field = v; invalidate() }
    var onTap: ((Tile) -> Unit)? = null
    var onLong: ((Tile) -> Unit)? = null

    private val restRadius = ctx.dp(if (wide) 24f else 22f)
    private val pressRadius = ctx.dp(12f)
    private var radius = restRadius
    private var fillColor = pal.surfaceContainer
    private var fgColor = pal.onSurface

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dp(15f); typeface = Fonts.medium }
    private val secPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dp(13f); typeface = Fonts.regular }
    private val r = RectF()
    private var radiusAnim: ValueAnimator? = null
    private var colorAnim: ValueAnimator? = null
    private var longFired = false
    private val longRunnable = Runnable {
        longFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        animateRadius(restRadius)
        onLong?.invoke(this)
    }

    init {
        isClickable = true
        contentDescription = label
    }

    fun setOn(value: Boolean, animate: Boolean = true) {
        if (value == on && colorAnim == null) { applyColorsNow(); return }
        on = value
        val toFill = if (on) pal.primary else pal.surfaceContainer
        val toFg = if (on) pal.onPrimary else pal.onSurface
        colorAnim?.cancel(); colorAnim = null
        if (!animate || !isAttachedToWindow) { fillColor = toFill; fgColor = toFg; invalidate(); return }
        val fromFill = fillColor; val fromFg = fgColor
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

    private fun applyColorsNow() {
        fillColor = if (on) pal.primary else pal.surfaceContainer
        fgColor = if (on) pal.onPrimary else pal.onSurface
        invalidate()
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
            MotionEvent.ACTION_DOWN -> {
                longFired = false
                animateRadius(pressRadius)
                postDelayed(longRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (e.x < 0 || e.y < 0 || e.x > width || e.y > height) {
                removeCallbacks(longRunnable); animateRadius(restRadius)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRunnable)
                if (!longFired && e.x >= 0 && e.y >= 0 && e.x <= width && e.y <= height) {
                    animateRadius(restRadius)
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onTap?.invoke(this)
                }
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longRunnable); animateRadius(restRadius) }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        bg.color = fillColor
        c.drawRoundRect(r, radius, radius, bg)
        val iconSize = context.dp(24f)
        if (!wide) {
            drawSymbol(c, symbol, width / 2f, height / 2f, iconSize, fgColor, on)
            return
        }
        val pad = context.dp(12f)
        val circle = height - pad * 2
        val ccx = pad + circle / 2; val ccy = height / 2f
        if (!on) { iconBg.color = pal.surfaceContainerHigh; c.drawCircle(ccx, ccy, circle / 2, iconBg) }
        drawSymbol(c, symbol, ccx, ccy, iconSize, fgColor, on)

        val tx = pad + circle + context.dp(10f)
        val avail = width - tx - pad
        labelPaint.color = fgColor
        secPaint.color = fgColor.withAlpha(if (on) 210 else 180)
        val lbl = TextUtils.ellipsize(label, labelPaint, avail, TextUtils.TruncateAt.END).toString()
        val sec = TextUtils.ellipsize(secondary, secPaint, avail, TextUtils.TruncateAt.END).toString()
        if (sec.isEmpty()) {
            c.drawText(lbl, tx, height / 2f + context.dp(5f), labelPaint)
        } else {
            c.drawText(lbl, tx, height / 2f - context.dp(2f), labelPaint)
            c.drawText(sec, tx, height / 2f + context.dp(16f), secPaint)
        }
    }
}

/**
 * Thick Expressive brightness slider with the AOSP perceptual (HLG-style) curve,
 * so the thumb moves the way Pixel's does rather than linearly.
 */
class BrightnessSlider(ctx: Context, private val pal: Palette) : View(ctx) {
    var value = 0.5f
        private set
    var onChange: ((Float, Boolean) -> Unit)? = null
    var onFirstTouch: (() -> Boolean)? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val clip = Path()

    fun setLinear(linear01: Float) { value = linearToGamma(linear01); invalidate() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (onFirstTouch?.invoke() == false) return false
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val nv = ((e.x - height / 2f) / (width - height)).coerceIn(0f, 1f)
                if ((nv == 0f || nv == 1f) && nv != value) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                value = nv; invalidate()
                onChange?.invoke(gammaToLinear(nv), e.actionMasked == MotionEvent.ACTION_UP)
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
        drawSymbol(c, Sym.LIGHT_MODE, h / 2, h / 2, context.dp(22f), pal.onPrimary, true)
    }

    companion object {
        // AOSP BrightnessUtils constants (hybrid log-gamma curve).
        private const val R = 0.5f
        private const val A = 0.17883277f
        private const val B = 0.28466892f
        private const val C = 0.55991073f

        fun gammaToLinear(v: Float): Float {
            val ret = if (v <= R) (v / R) * (v / R) else exp((v - C) / A) + B
            return (ret / 12f).coerceIn(0f, 1f)
        }

        fun linearToGamma(l: Float): Float {
            val n = (l * 12f).coerceAtLeast(0f)
            val ret = if (n <= 1f) sqrt(n) * R else A * ln(n - B) + C
            return ret.coerceIn(0f, 1f)
        }
    }
}

/** A card that lives in a [NotifStack] and gets its corners from it. */
interface StackCard {
    val bg: GradientDrawable
    val dismissKeys: List<String>
    val dismissable: Boolean
}

/**
 * Stack of cards with Pixel-style grouped corners and swipe-to-dismiss where
 * the neighbours are pulled along a little before letting go.
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
    var onDismiss: ((StackCard) -> Unit)? = null

    init { orientation = VERTICAL; clipChildren = false }

    fun addCard(card: View) {
        addView(card, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        updateCorners()
    }

    private fun updateCorners() {
        val n = childCount
        for (i in 0 until n) {
            val card = getChildAt(i)
            val top = if (i == 0) outer else inner
            val bottom = if (i == n - 1) outer else inner
            (card as? StackCard)?.bg?.cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
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
                val canGo = (t as? StackCard)?.dismissable != false
                val dx = (e.x - downX).let { if (canGo) it else it * 0.25f }
                t.translationX = dx
                t.alpha = 1f - min(1f, abs(dx) / width) * 0.4f
                pullNeighbours(t, dx)
                val crossed = canGo && abs(dx) > width * 0.4f
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
                val canGo = (t as? StackCard)?.dismissable != false
                val dismiss = swiping && canGo && (abs(dx) > width * 0.4f || (abs(vx) > 1500 && vx * dx > 0))
                if (dismiss) dismiss(t, if (dx == 0f) vx else dx) else settle()
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

    private fun settle() {
        for (j in 0 until childCount) {
            getChildAt(j).animate().translationX(0f).alpha(1f).setDuration(350)
                .setInterpolator(OvershootInterpolator(1.4f)).start()
        }
    }

    fun dismiss(t: View, dir: Float) {
        val sign = if (dir >= 0) 1f else -1f
        (t as? StackCard)?.let { onDismiss?.invoke(it) }
        t.animate().translationX(sign * width * 1.1f).alpha(0f).setDuration(200).setInterpolator(EMPHASIZED)
            .withEndAction { collapse(t) }.start()
        for (j in 0 until childCount) if (getChildAt(j) !== t) {
            getChildAt(j).animate().translationX(0f).setDuration(300)
                .setInterpolator(OvershootInterpolator(1.6f)).start()
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
                }
            })
            start()
        }
    }

    /** Animated "clear all": dismisses dismissable cards from the bottom up. */
    fun clearAll() {
        val cards = (0 until childCount).map { getChildAt(it) }.filter { (it as? StackCard)?.dismissable == true }.reversed()
        cards.forEachIndexed { i, c -> postDelayed({ if (c.parent === this) dismiss(c, 1f) }, i * 50L) }
    }
}

/**
 * Full-screen root of the shade window. Intercepts upward drags to close the
 * shade (unless a scrollable child under the finger can still scroll), and the back key.
 */
class ShadeRoot(ctx: Context) : FrameLayout(ctx) {
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((Float) -> Unit)? = null
    var onRelease: ((Float) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    /** Returns true if a child at (rawX, rawY) should get the upward drag instead. */
    var childScrolls: ((Float, Float) -> Boolean)? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var childOwns = false
    private var vt: VelocityTracker? = null

    init { isFocusableInTouchMode = true }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false
                childOwns = childScrolls?.invoke(e.rawX, e.rawY) == true
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dy = e.rawY - downY; val dx = e.rawX - downX
                if (!childOwns && dy < -slop && abs(dy) > abs(dx)) {
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
    ctx.label(text, 14f, pal.onSurface, medium = true).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dpi(18f), ctx.dpi(11f), ctx.dpi(18f), ctx.dpi(11f))
        background = GradientDrawable().apply { setColor(pal.card); cornerRadius = ctx.dp(100f) }
        setOnClickListener { onClick() }
    }
