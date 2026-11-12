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

/**
 * Quick settings tile, shaped after Pixel 10 (Material 3 Expressive):
 *  - wide tiles are full pills; small tiles are circles when off and morph to a
 *    rounded square when on, and squeeze further while pressed;
 *  - dual-target wide tiles (Bluetooth, Modes…) keep a neutral body and show their
 *    state in the icon container, which is also the toggle target.
 */
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
    /** Wide tiles are dual-target like Pixel 10: the icon toggles, the rest opens details. */
    var onToggle: ((Tile) -> Unit)? = null
    var onLong: ((Tile) -> Unit)? = null
    /** Neutral body with a coloured icon container instead of a fully filled tile. */
    var dualStyle = false

    private val activeSmallRadius = ctx.dp(22f)
    private var radius = -1f            // -1 = derive from height on first draw
    private var pressed = false
    private var fill = pal.tileOff
    private var fg = pal.onSurface
    private var iconFill = pal.tileIconOff

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
        pressed = false; morph()
        onLong?.invoke(this)
    }

    init {
        isClickable = true
        contentDescription = label
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        radius = -1f
    }

    private fun targetRadius(): Float {
        val h = height.toFloat().takeIf { it > 0 } ?: return 0f
        val rest = if (wide) h / 2f else if (on) activeSmallRadius else h / 2f
        return if (pressed) min(rest, h * 0.28f) else rest
    }

    private fun morph() {
        val to = targetRadius()
        radiusAnim?.cancel()
        if (!isAttachedToWindow || radius < 0f) { radius = to; invalidate(); return }
        radiusAnim = ValueAnimator.ofFloat(radius, to).apply {
            duration = 220; interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { radius = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun colorsFor(state: Boolean): Triple<Int, Int, Int> = when {
        dualStyle -> Triple(pal.tileOff, pal.onSurface, if (state) pal.primary else pal.tileIconOff)
        state -> Triple(pal.primary, pal.onPrimary, 0)
        else -> Triple(pal.tileOff, pal.onSurface, pal.tileIconOff)
    }

    fun setOn(value: Boolean, animate: Boolean = true) {
        val changed = value != on
        on = value
        val (tFill, tFg, tIcon) = colorsFor(on)
        colorAnim?.cancel(); colorAnim = null
        if (!changed || !animate || !isAttachedToWindow) {
            fill = tFill; fg = tFg; iconFill = tIcon
            radius = -1f; invalidate(); return
        }
        val f0 = fill; val g0 = fg; val i0 = iconFill
        val ev = ArgbEvaluator()
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250; interpolator = EMPHASIZED
            addUpdateListener {
                val f = it.animatedValue as Float
                fill = ev.evaluate(f, f0, tFill) as Int
                fg = ev.evaluate(f, g0, tFg) as Int
                iconFill = ev.evaluate(f, i0, tIcon) as Int
                invalidate()
            }
            start()
        }
        morph()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longFired = false
                pressed = true; morph()
                postDelayed(longRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (e.x < 0 || e.y < 0 || e.x > width || e.y > height) {
                removeCallbacks(longRunnable); if (pressed) { pressed = false; morph() }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRunnable)
                val inside = e.x >= 0 && e.y >= 0 && e.x <= width && e.y <= height
                pressed = false; morph()
                if (!longFired && inside) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    val inIcon = wide && e.x < height
                    val t = onToggle
                    if (inIcon && t != null) t(this) else onTap?.invoke(this)
                }
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longRunnable); pressed = false; morph() }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (radius < 0f) radius = targetRadius()
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        bg.color = fill
        c.drawRoundRect(r, radius, radius, bg)
        val iconSize = context.dp(24f)
        if (!wide) {
            drawSymbol(c, symbol, width / 2f, height / 2f, iconSize, fg, on)
            return
        }
        val pad = context.dp(10f)
        val box = height - pad * 2
        val ccx = pad + box / 2; val ccy = height / 2f
        val showBox = dualStyle || !on
        if (showBox) {
            iconBg.color = iconFill
            r.set(pad, pad, pad + box, pad + box)
            // Dual-target toggles are rounded squares; plain inactive tiles a circle.
            val br = if (dualStyle) box * 0.32f else box / 2f
            c.drawRoundRect(r, br, br, iconBg)
        }
        val iconColor = if (dualStyle && on) pal.onPrimary else fg
        drawSymbol(c, symbol, ccx, ccy, iconSize, iconColor, on)

        val tx = pad + box + context.dp(12f)
        val avail = width - tx - context.dp(14f)
        labelPaint.color = fg
        secPaint.color = fg.withAlpha(if (on && !dualStyle) 215 else 185)
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
 * Material 3 Expressive brightness slider as on Pixel 10: filled track, a gap,
 * a thin vertical handle, a gap, then the inactive track. Uses the AOSP perceptual
 * (HLG-style) curve so the handle moves the way Pixel's does.
 */
class BrightnessSlider(ctx: Context, private val pal: Palette) : View(ctx) {
    var value = 0.5f
        private set
    var onChange: ((Float, Boolean) -> Unit)? = null
    var onFirstTouch: (() -> Boolean)? = null
    /** Long press: brightness options (adaptive brightness). */
    var onLong: (() -> Unit)? = null
    /** Shows Pixel's auto-brightness mark at the end of the track. */
    var autoOn = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var downX = 0f
    private var moved = false
    private var longFired = false
    private val longPress = Runnable {
        longFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLong?.invoke()
    }
    private val r = RectF()
    private var dragging = false
    private var handleW = ctx.dp(4f)

    fun setLinear(linear01: Float) { value = linearToGamma(linear01); invalidate() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                downX = e.x; moved = false; longFired = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (longFired) return true
                if (!moved && abs(e.x - downX) > ViewConfiguration.get(context).scaledTouchSlop) {
                    removeCallbacks(longPress)
                    if (onFirstTouch?.invoke() == false) { longFired = true; return true }
                    moved = true; dragging = true; animateHandle(context.dp(2f))
                }
                if (moved) update(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (!longFired && e.actionMasked == MotionEvent.ACTION_UP) {
                    if (!moved && onFirstTouch?.invoke() == false) return true
                    update(e)
                }
                if (dragging) { dragging = false; animateHandle(context.dp(4f)) }
            }
        }
        return true
    }

    private fun animateHandle(to: Float) {
        ValueAnimator.ofFloat(handleW, to).apply {
            duration = 150; addUpdateListener { handleW = it.animatedValue as Float; invalidate() }; start()
        }
    }

    private fun update(e: MotionEvent) {
        val inset = context.dp(8f)
        val nv = ((e.x - inset) / (width - inset * 2)).coerceIn(0f, 1f)
        if ((nv == 0f || nv == 1f) && nv != value) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        value = nv; invalidate()
        onChange?.invoke(gammaToLinear(nv), e.actionMasked == MotionEvent.ACTION_UP)
    }

    private fun seg(c: Canvas, l: Float, t: Float, rr: Float, b: Float, rl: Float, rrad: Float, color: Int) {
        if (rr - l <= 0.5f) return
        r.set(l, t, rr, b)
        path.reset()
        path.addRoundRect(r, floatArrayOf(rl, rl, rrad, rrad, rrad, rrad, rl, rl), Path.Direction.CW)
        p.color = color
        c.drawPath(path, p)
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        val trackT = h * 0.08f; val trackB = h * 0.92f
        val outer = context.dp(16f); val inner = context.dp(4f)
        val gap = context.dp(6f)
        val inset = context.dp(8f)
        val x = inset + (w - inset * 2) * value
        // Active part, gap, handle, gap, inactive part
        seg(c, 0f, trackT, x - gap - handleW / 2, trackB, outer, inner, pal.primary)
        seg(c, x + gap + handleW / 2, trackT, w, trackB, inner, outer, pal.sliderTrack)
        p.color = pal.primary
        r.set(x - handleW / 2, 0f, x + handleW / 2, h)
        c.drawRoundRect(r, handleW / 2, handleW / 2, p)
        val iconX = context.dp(24f)
        val onActive = x - gap > iconX + context.dp(14f)
        drawSymbol(c, Sym.LIGHT_MODE, iconX, h / 2, context.dp(20f), if (onActive) pal.onPrimary else pal.onSurface, true)
        if (autoOn) {
            val ax = w - context.dp(24f)
            val onTrack = x + gap < ax - context.dp(14f)
            drawSymbol(c, Sym.BRIGHTNESS_AUTO, ax, h / 2, context.dp(20f), if (onTrack) pal.onSurface else pal.onPrimary, true)
        }
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
 * Full-screen root of the shade window. Owns vertical drags inside the open shade:
 * down expands quick settings, up collapses them or closes the shade. A scrollable
 * child under the finger keeps the drag while it can still scroll that way.
 */
class ShadeRoot(ctx: Context) : FrameLayout(ctx) {
    var onDragStart: ((Float) -> Unit)? = null
    var onDrag: ((Float) -> Unit)? = null
    var onRelease: ((Float) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    /** (rawX, rawY, dy) -> true if the shade should take this vertical drag. */
    var takesDrag: ((Float, Float, Float) -> Boolean)? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null

    init { isFocusableInTouchMode = true }

    private fun tryStart(e: MotionEvent): Boolean {
        val dy = e.rawY - downY; val dx = e.rawX - downX
        if (abs(dy) > slop && abs(dy) > abs(dx) && takesDrag?.invoke(downX, downY, dy) != false) {
            dragging = true
            downY = e.rawY - (if (dy > 0) slop else -slop).toFloat() // no jump when the drag kicks in
            onDragStart?.invoke(dy)
            return true
        }
        return false
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (!dragging && tryStart(e)) return true
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
                if (!dragging) tryStart(e)
                if (dragging) onDrag?.invoke(e.rawY - downY)
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

/** Pixel-style battery: a rounded pill with the percentage inside, filled to the charge level. */
class BatteryPill(ctx: Context, private val pal: Palette) : View(ctx) {
    private var pct = 100
    private var charging = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Fonts.medium }
    private val r = RectF()

    fun set(percent: Int, isCharging: Boolean) { pct = percent.coerceIn(0, 100); charging = isCharging; invalidate() }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat() - context.dp(2.5f)
        r.set(0f, 0f, w, h)
        p.color = pal.onSurface.withAlpha(70); c.drawRoundRect(r, h * 0.36f, h * 0.36f, p)
        c.save(); c.clipRect(0f, 0f, w * pct / 100f, h)
        p.color = if (charging) pal.primary else pal.onSurface; c.drawRoundRect(r, h * 0.36f, h * 0.36f, p)
        c.restore()
        r.set(w + context.dp(0.8f), h * 0.32f, w + context.dp(2.5f), h * 0.68f)
        p.color = pal.onSurface.withAlpha(110); c.drawRoundRect(r, context.dp(1f), context.dp(1f), p)
        tp.textSize = h * 0.7f
        val fm = tp.fontMetrics
        val ty = h / 2f - (fm.ascent + fm.descent) / 2f
        val fx = w * pct / 100f
        // Digits flip colour exactly where the fill ends, so they read on both parts.
        c.save(); c.clipRect(0f, 0f, fx, h)
        tp.color = if (charging) pal.onPrimary else pal.surface
        c.drawText("$pct", w / 2f, ty, tp)
        c.restore()
        c.save(); c.clipRect(fx, 0f, w, h)
        tp.color = pal.onSurface
        c.drawText("$pct", w / 2f, ty, tp)
        c.restore()
    }
}

fun pill(ctx: Context, pal: Palette, text: String, onClick: () -> Unit): TextView =
    ctx.label(text, 14f, pal.onSurface, medium = true).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dpi(18f), ctx.dpi(11f), ctx.dpi(18f), ctx.dpi(11f))
        background = GradientDrawable().apply { setColor(pal.card); cornerRadius = ctx.dp(100f) }
        setOnClickListener { onClick() }
    }


/** Horizontally paged tile grid with Pixel's page dots. */
class TilePager(ctx: Context, private val pal: Palette, private val pages: List<View>) : FrameLayout(ctx) {
    /** Off while quick settings are collapsed, so only the first page's first row is in play. */
    var swipeEnabled = true
    val dots = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private var page = 0
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    init {
        pages.forEach { addView(it, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        pages.indices.forEach { _ ->
            dots.addView(View(ctx), LinearLayout.LayoutParams(ctx.dpi(6f), ctx.dpi(6f)).apply {
                marginStart = ctx.dpi(3f); marginEnd = ctx.dpi(3f)
            })
        }
        updateDots()
        clipChildren = true
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!dragging) place(0f)
    }

    private fun place(offset: Float) {
        val w = width.toFloat()
        pages.forEachIndexed { i, v -> v.translationX = (i - page) * w + offset }
    }

    private fun updateDots() {
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == page) pal.onSurface else pal.onSurfaceVariant.withAlpha(110))
            }
        }
    }

    fun reset() { page = 0; place(0f); updateDots() }

    /** Slide back to the first page (when quick settings collapse, the first row must be page 1's). */
    fun toFirstPage() {
        if (page == 0) return
        page = 0
        val w = width.toFloat()
        pages.forEachIndexed { i, v -> v.animate().translationX(i * w).setDuration(220).setInterpolator(EMPHASIZED).start() }
        updateDots()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX; val dy = e.y - downY
                if (swipeEnabled && abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) {
                    dragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> if (dragging) {
                var dx = e.x - downX
                if ((page == 0 && dx > 0) || (page == pages.size - 1 && dx < 0)) dx *= 0.3f
                place(dx)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val dx = e.x - downX
                val target = when {
                    !dragging -> page
                    dx < -width / 3f || vx < -800 -> (page + 1).coerceAtMost(pages.size - 1)
                    dx > width / 3f || vx > 800 -> (page - 1).coerceAtLeast(0)
                    else -> page
                }
                if (target != page) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                page = target
                val w = width.toFloat()
                pages.forEachIndexed { i, v ->
                    v.animate().translationX((i - page) * w).setDuration(350)
                        .setInterpolator(OvershootInterpolator(0.8f)).start()
                }
                updateDots()
                dragging = false
            }
        }
        return true
    }
}
