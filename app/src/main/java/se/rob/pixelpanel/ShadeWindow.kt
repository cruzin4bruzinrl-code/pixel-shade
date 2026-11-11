package se.rob.pixelpanel

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Pixel-style shade, living in an accessibility overlay window that is
 * added once and kept warm. Hidden = invisible and untouchable.
 */
class ShadeWindow(private val ctx: Context, private val wm: WindowManager) {

    val root = ShadeRoot(ctx)
    private val scrim = View(ctx)
    private var content = LinearLayout(ctx)
    private var diag: TextView? = null
    private var stack: NotifStack? = null
    private var torchTile: Tile? = null

    var progress = 0f
        private set
    var isOpen = false
        private set
    var onStateChanged: ((Boolean) -> Unit)? = null

    private var attached = false
    private var shown = false
    private var currentBlur = -1
    private var dragStart = 0f
    var blurSupported = wm.isCrossWindowBlurEnabled
        private set

    @Suppress("DEPRECATION")
    private val refreshHz = try { wm.defaultDisplay.refreshRate } catch (_: Exception) { 60f }
    private val meter = FrameMeter(refreshHz)

    private val cameraManager = ctx.getSystemService(CameraManager::class.java)
    private val torchId: String? = try {
        cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: Exception) { null }

    private val baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    private val hiddenFlags = baseFlags or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        hiddenFlags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        title = "Pixelpanel"
    }

    // Material 3 Expressive "default spatial" spring for the expressive motion scheme.
    private val prop = object : FloatPropertyCompat<ShadeWindow>("progress") {
        override fun getValue(o: ShadeWindow) = o.progress
        override fun setValue(o: ShadeWindow, v: Float) = o.setProgress(v)
    }
    private val spring = SpringAnimation(this, prop).apply {
        spring = SpringForce().apply { dampingRatio = 0.8f; stiffness = 380f }
        setMinimumVisibleChange(0.0005f)
        addEndListener { _, canceled, value, _ -> if (!canceled) settled(value) }
    }

    private val blurListener = Consumer<Boolean> { enabled ->
        blurSupported = enabled
        applyScrimColor()
    }

    init {
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        build()
        root.visibility = View.INVISIBLE
        root.onDragStart = { beginDrag(0) }
        root.onDrag = { dy -> dragTo(dy) }
        root.onRelease = { vy -> release(vy) }
        root.onTapOutside = { _, y -> if (y > content.bottom + content.translationY) close() }
        root.onBack = { close() }

        torchId?.let { id ->
            try {
                cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId == id) torchTile?.setOn(enabled, isOpen)
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (_: Exception) { }
        }
    }

    fun attach() {
        if (attached) return
        wm.addView(root, lp)
        wm.addCrossWindowBlurEnabledListener(ctx.mainExecutor, blurListener)
        attached = true
    }

    fun detach() {
        if (!attached) return
        spring.cancel()
        try { wm.removeCrossWindowBlurEnabledListener(blurListener) } catch (_: Exception) { }
        try { wm.removeViewImmediate(root) } catch (_: Exception) { }
        attached = false
    }

    /** Rebuild content after a theme or orientation change. */
    fun rebuild() {
        if (isOpen || shown) return
        root.removeView(content)
        content = LinearLayout(ctx)
        build()
    }

    private val height: Float
        get() = content.height.takeIf { it > 0 }?.toFloat() ?: (ctx.resources.displayMetrics.heightPixels * 0.85f)

    // ---- Gesture API used by the service's top strip --------------------------------

    fun beginDrag(eventUptimeMs: Long) {
        spring.cancel()
        show()
        dragStart = progress
        meter.start(eventUptimeMs)
    }

    fun dragTo(dy: Float) {
        var p = dragStart + dy / height
        if (p > 1f) p = 1f + (p - 1f) * 0.35f // resistance when pulled past fully open
        setProgress(p.coerceAtLeast(0f))
    }

    fun release(vy: Float) {
        val v = vy / height
        val open = when {
            v > 0.6f -> true
            v < -0.6f -> false
            else -> progress > 0.4f
        }
        animateTo(if (open) 1f else 0f, v)
    }

    fun open() {
        beginDrag(0)
        animateTo(1f, 0f)
    }

    fun close() {
        if (!shown) return
        meter.start(0)
        animateTo(0f, 0f)
    }

    private fun animateTo(target: Float, velocity: Float) {
        spring.setStartVelocity(velocity)
        spring.animateToFinalPosition(target)
    }

    // ---- Rendering --------------------------------------------------------------------

    private fun setProgress(p: Float) {
        progress = p
        val h = height
        content.translationY = if (p <= 1f) (p - 1f) * h else (p - 1f) * h * 0.3f
        val clamped = p.coerceIn(0f, 1f)
        scrim.alpha = clamped
        content.alpha = (clamped * 1.6f).coerceAtMost(1f)
        updateBlur(clamped)
    }

    private fun updateBlur(clamped: Float) {
        if (!blurSupported || !shown) return
        val max = ctx.dpi(Prefs.blurRadius(ctx).toFloat())
        val target = if (Prefs.blurScales(ctx)) ((clamped * max) / 4f).roundToInt() * 4 else if (clamped > 0f) max else 0
        if (target == currentBlur) return
        currentBlur = target
        lp.blurBehindRadius = target
        if (attached) wm.updateViewLayout(root, lp)
    }

    private fun show() {
        if (shown) return
        shown = true
        applyScrimColor()
        root.visibility = View.VISIBLE
        lp.flags = hiddenFlags or (if (blurSupported) WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0)
        currentBlur = -1
        lp.blurBehindRadius = 0
        if (attached) wm.updateViewLayout(root, lp)
        refreshBattery()
    }

    private fun settled(value: Float) {
        val stats = meter.stop()
        if (stats != null) {
            Prefs.setLastStats(ctx, stats)
            diag?.text = diagText(stats)
        }
        if (value <= 0.001f) {
            shown = false; isOpen = false
            root.visibility = View.INVISIBLE
            lp.flags = hiddenFlags
            lp.blurBehindRadius = 0
            currentBlur = -1
            if (attached) wm.updateViewLayout(root, lp)
            onStateChanged?.invoke(false)
        } else if (!isOpen) {
            isOpen = true
            lp.flags = baseFlags or (if (blurSupported) WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0)
            if (attached) wm.updateViewLayout(root, lp)
            root.requestFocus()
            onStateChanged?.invoke(true)
        }
    }

    private fun applyScrimColor() {
        val pal = Palette(ctx)
        scrim.setBackgroundColor(pal.scrim.withAlpha(if (blurSupported) 120 else 225))
    }

    private fun diagText(stats: String?): String {
        val blur = if (blurSupported) "Oskärpa mellan fönster: PÅ (${Prefs.blurRadius(ctx)} dp)"
        else "Oskärpa mellan fönster: AV – reservläge med mörk ton"
        return if (stats == null) blur else "$blur\n$stats"
    }

    private fun refreshBattery() {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        batteryView?.text = "$pct %"
    }

    private var batteryView: TextView? = null

    // ---- Layout -----------------------------------------------------------------------

    private fun build() {
        val pal = Palette(ctx)
        val pad = ctx.dpi(16f)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(pad, ctx.statusBarHeight() + ctx.dpi(8f), pad, ctx.dpi(12f))
        content.clipChildren = false
        content.clipToPadding = false

        // Header: clock + date, battery on the right
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val clockCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        clockCol.addView(TextClock(ctx).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 30f; setTextColor(pal.onSurface)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        })
        clockCol.addView(TextClock(ctx).apply {
            format24Hour = "EEE d MMM"; format12Hour = "EEE d MMM"
            textSize = 14f; setTextColor(pal.onSurfaceVariant)
        })
        header.addView(clockCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        batteryView = TextView(ctx).apply { textSize = 14f; setTextColor(pal.onSurfaceVariant) }
        header.addView(batteryView)
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = ctx.dpi(16f)
        })

        // Tiles
        val gap = ctx.dpi(8f)
        val tileH = ctx.dpi(72f)
        fun row(vararg tiles: Tile): LinearLayout {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            tiles.forEachIndexed { i, t ->
                val w = if (t.wide) 2f else 1f
                r.addView(t, LinearLayout.LayoutParams(0, tileH, w).apply { if (i > 0) marginStart = gap })
            }
            return r
        }
        val wifi = Tile(ctx, pal, Glyph.WIFI, "Internet", "Wi‑Fi", true, true)
        val bt = Tile(ctx, pal, Glyph.BLUETOOTH, "Bluetooth", "Av", true, false)
        val dnd = Tile(ctx, pal, Glyph.DND, "Stör ej", "Av", true, false)
        val torch = Tile(ctx, pal, Glyph.FLASHLIGHT, "Ficklampa", "Av", true, false)
        torchTile = torch
        torch.onToggle = { t, on ->
            t.secondary = if (on) "På" else "Av"
            torchId?.let { try { cameraManager.setTorchMode(it, on) } catch (_: Exception) { } }
        }
        listOf(bt, dnd).forEach { t -> t.onToggle = { tt, on -> tt.secondary = if (on) "På" else "Av" } }

        val lpRow = { top: Int -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileH).apply { topMargin = top } }
        content.addView(row(wifi, bt), lpRow(0))
        content.addView(row(dnd, torch), lpRow(gap))
        content.addView(row(
            Tile(ctx, pal, Glyph.AIRPLANE, "Flygplansläge", "", false, false),
            Tile(ctx, pal, Glyph.BATTERY, "Batterisparläge", "", false, false),
            Tile(ctx, pal, Glyph.ROTATE, "Rotera", "", false, true),
            Tile(ctx, pal, Glyph.LOCATION, "Plats", "", false, true),
        ), lpRow(gap))

        // Page dots
        val dots = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (i in 0 until 2) {
            dots.addView(View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (i == 0) pal.onSurface else pal.onSurfaceVariant.withAlpha(110))
                }
            }, LinearLayout.LayoutParams(ctx.dpi(6f), ctx.dpi(6f)).apply { marginStart = ctx.dpi(3f); marginEnd = ctx.dpi(3f) })
        }
        content.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(6f)).apply {
            topMargin = ctx.dpi(12f)
        })

        // Brightness
        content.addView(BrightnessSlider(ctx, pal), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(52f)).apply {
            topMargin = ctx.dpi(12f)
        })

        // Notifications
        val s = NotifStack(ctx)
        stack = s
        s.addCard(NotifCard(ctx, pal, "Meddelanden", "nu", "Testnotis", "Svep åt sidan – grannkorten följer med", pal.primary))
        s.addCard(NotifCard(ctx, pal, "Gmail", "5 min", "Pixelpanel bygge", "Oskärpa och svep testas i den här versionen", pal.tertiary))
        s.addCard(NotifCard(ctx, pal, "Kalender", "12 min", "Kalibrering", "Måtten justeras mot Pixel 10 i nästa steg", pal.secondary))
        content.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(20f)
        })

        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(pill(ctx, pal, "Hantera") { })
        actions.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(pill(ctx, pal, "Rensa allt") { s.clearAll() })
        content.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(12f)
        })

        diag = TextView(ctx).apply {
            textSize = 11f; setTextColor(pal.onSurfaceVariant)
            text = diagText(Prefs.lastStats(ctx))
        }
        content.addView(diag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(12f)
        })

        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        content.translationY = -10000f
        scrim.alpha = 0f
        applyScrimColor()
    }

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 0.001f
}
