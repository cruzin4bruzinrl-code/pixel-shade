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
 * Quick settings tile, measured off AOSP Android 16 QPR1 (qs/panels/ui/compose/infinitegrid):
 *  - height 72 dp; inactive = pill (50 dp radius), active = 24 dp radius, animated without overshoot;
 *  - large tiles: 8 dp start padding, 56 dp icon box, 6 dp gap, 12 dp end padding, 28 dp icon;
 *  - dual-target: body stays surfaceEffect1, icon box = primary (on, 16 dp radius) / surfaceEffect2 (off, circle);
 *  - plain large tiles have no icon box; tapping bounces the tile 8 dp (BounceableTileViewModel).
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
    /** Neutral body with a coloured icon box instead of a fully filled tile. */
    var dualStyle = false

    private val inactiveR = ctx.dp(50f)
    private val activeR = ctx.dp(24f)
    private val iconActiveR = ctx.dp(16f)
    private var radius = inactiveR
    private var iconRadius = inactiveR
    private var fill = pal.effect1
    private var fg = pal.onSurface
    private var iconFill = 0
    private var bounce = 0f

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dp(14f); typeface = Fonts.medium }
    private val secPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dp(14f); typeface = Fonts.regular }
    private val r = RectF()
    private var shapeAnim: ValueAnimator? = null
    private var colorAnim: ValueAnimator? = null
    private var longFired = false
    private val longRunnable = Runnable {
        longFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLong?.invoke(this)
    }

    init {
        isClickable = true
        contentDescription = label
    }

    private data class Look(val fill: Int, val fg: Int, val icon: Int)

    private fun look(state: Boolean) = when {
        dualStyle -> Look(pal.effect1, pal.onSurface, if (state) pal.primary else pal.effect2)
        state -> Look(pal.primary, pal.onPrimary, 0)
        else -> Look(pal.effect1, pal.onSurface, 0)
    }

    fun setOn(value: Boolean, animate: Boolean = true) {
        val changed = value != on
        on = value
        val t = look(on)
        val toR = if (on && !dualStyle) activeR else inactiveR
        val toIconR = if (on) iconActiveR else inactiveR
        colorAnim?.cancel(); shapeAnim?.cancel()
        if (!changed || !animate || !isAttachedToWindow) {
            fill = t.fill; fg = t.fg; iconFill = t.icon; radius = toR; iconRadius = toIconR
            invalidate(); return
        }
        val f0 = fill; val g0 = fg; val i0 = iconFill; val r0 = radius; val ir0 = iconRadius
        val ev = ArgbEvaluator()
        // animateColorAsState / animateDpAsState defaults: critically damped spring, no overshoot.
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300; interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                fill = ev.evaluate(f, f0, t.fill) as Int
                fg = ev.evaluate(f, g0, t.fg) as Int
                iconFill = ev.evaluate(f, i0, t.icon) as Int
                radius = r0 + (toR - r0) * f
                iconRadius = ir0 + (toIconR - ir0) * f
                invalidate()
            }
            start()
        }
    }

    /** Pixel's tap bounce: the tile grows 8 dp sideways and settles back. */
    private fun bounce() {
        ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 260; interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { bounce = (it.animatedValue as Float) * context.dp(8f); invalidate() }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longFired = false
                postDelayed(longRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (e.x < 0 || e.y < 0 || e.x > width || e.y > height) removeCallbacks(longRunnable)
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRunnable)
                val inside = e.x >= 0 && e.y >= 0 && e.x <= width && e.y <= height
                if (!longFired && inside) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    bounce()
                    val inIcon = wide && e.x < context.dp(8f + 56f + 3f)
                    val t = onToggle
                    if (inIcon && t != null) t(this) else onTap?.invoke(this)
                }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longRunnable)
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val rr = kotlin.math.min(radius, h / 2)
        r.set(-bounce / 2, 0f, width + bounce / 2, h)
        bg.color = fill
        c.drawRoundRect(r, rr, rr, bg)
        if (!wide) {
            drawSymbol(c, symbol, width / 2f, h / 2, context.dp(26f), fg, on)
            return
        }
        val box = context.dp(56f)
        val start = context.dp(8f)
        val top = (h - box) / 2
        if (dualStyle && iconFill != 0) {
            iconBg.color = iconFill
            r.set(start, top, start + box, top + box)
            val ir = kotlin.math.min(iconRadius, box / 2)
            c.drawRoundRect(r, ir, ir, iconBg)
        }
        val iconColor = if (dualStyle) (if (on) pal.onPrimary else pal.onSurface) else fg
        drawSymbol(c, symbol, start + box / 2, h / 2, context.dp(26f), iconColor, on)

        val tx = start + box + context.dp(6f)
        val avail = width - tx - context.dp(if (dualStyle) 8f else 12f)
        labelPaint.color = fg
        secPaint.color = fg
        val lbl = TextUtils.ellipsize(label, labelPaint, avail, TextUtils.TruncateAt.END).toString()
        val sec = TextUtils.ellipsize(secondary, secPaint, avail, TextUtils.TruncateAt.END).toString()
        if (sec.isEmpty()) {
            c.drawText(lbl, tx, h / 2 + context.dp(5f), labelPaint)
        } else {
            c.drawText(lbl, tx, h / 2 - context.dp(3f), labelPaint)
            c.drawText(sec, tx, h / 2 + context.dp(16f), secPaint)
        }
    }
}

/**
 * Brightness slider after AOSP Android 16 QPR1 (brightness/ui/compose/BrightnessSlider.kt):
 * 40 dp track (12 dp outer / 2 dp inner corners), 4×52 dp thumb, 6 dp thumb–track gap,
 * primary active track, surfaceEffect1 inactive track, 28 dp sun icon riding the end of the
 * active track (or the far end of the inactive track when there is no room).
 * Uses the AOSP perceptual (HLG) curve.
 */
class BrightnessSlider(ctx: Context, private val pal: Palette) : View(ctx) {
    var value = 0.5f
        private set
    var onChange: ((Float, Boolean) -> Unit)? = null
    var onFirstTouch: (() -> Boolean)? = null
    /** Long press: brightness options (adaptive brightness). */
    var onLong: (() -> Unit)? = null
    var autoOn = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()
    private var downX = 0f
    private var moved = false
    private var longFired = false
    private var dragging = false
    private val longPress = Runnable {
        longFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLong?.invoke()
    }

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
                    moved = true; dragging = true
                }
                if (moved) update(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (!longFired && e.actionMasked == MotionEvent.ACTION_UP) {
                    if (!moved && onFirstTouch?.invoke() == false) return true
                    update(e)
                }
                dragging = false
            }
        }
        return true
    }

    private fun update(e: MotionEvent) {
        val nv = (e.x / width).coerceIn(0f, 1f)
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
        val trackH = context.dp(40f)
        val t = (h - trackH) / 2; val b = t + trackH
        val outer = context.dp(12f); val inner = context.dp(2f)
        val gap = context.dp(6f)
        val thumbW = context.dp(4f)
        val x = (w * value).coerceIn(thumbW / 2, w - thumbW / 2)
        val activeEnd = x - thumbW / 2 - gap
        val inactiveStart = x + thumbW / 2 + gap
        seg(c, 0f, t, activeEnd, b, outer, inner, pal.primary)
        seg(c, inactiveStart, t, w, b, inner, outer, pal.effect1)
        p.color = pal.primary
        r.set(x - thumbW / 2, (h - context.dp(52f)) / 2, x + thumbW / 2, (h + context.dp(52f)) / 2)
        c.drawRoundRect(r, thumbW / 2, thumbW / 2, p)
        val icon = context.dp(28f); val pad = context.dp(6f)
        val sym = if (autoOn) Sym.BRIGHTNESS_AUTO else Sym.LIGHT_MODE
        if (activeEnd > icon + pad * 2) {
            drawSymbol(c, sym, activeEnd - pad - icon / 2, h / 2, context.dp(22f), pal.onPrimary, true)
        } else {
            drawSymbol(c, sym, w - pad - icon / 2, h / 2, context.dp(22f), pal.onSurface, true)
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
    private val inner = ctx.dp(4f)
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
