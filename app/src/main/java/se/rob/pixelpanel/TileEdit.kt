package se.rob.pixelpanel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.hypot

/** Every tile Pixelpanel knows: Pixel's own set plus Samsung-only features drawn Pixel-style. */
data class TileInfo(val id: String, val sym: String, val label: String, val defaultWide: Boolean)

object TileCatalog {
    val all = listOf(
        TileInfo("internet", Sym.WIFI, "Internet", true),
        TileInfo("bluetooth", Sym.BLUETOOTH, "Bluetooth", true),
        TileInfo("dnd", Sym.DO_NOT_DISTURB_ON, "Stör ej", true),
        TileInfo("torch", Sym.FLASHLIGHT_ON, "Ficklampa", true),
        TileInfo("airplane", Sym.AIRPLANEMODE_ACTIVE, "Flygplansläge", false),
        TileInfo("saver", Sym.BATTERY_SAVER, "Batterisparläge", false),
        TileInfo("rotate", Sym.SCREEN_ROTATION, "Rotera automatiskt", false),
        TileInfo("location", Sym.LOCATION_ON, "Plats", false),
        TileInfo("data", Sym.SIGNAL_CELLULAR_ALT, "Mobildata", true),
        TileInfo("dark", Sym.DARK_MODE, "Mörkt tema", true),
        TileInfo("hotspot", Sym.WIFI_TETHERING, "Surfzon", true),
        TileInfo("nfc", Sym.NFC, "NFC", true),
        TileInfo("camera", Sym.PHOTO_CAMERA, "Kamera", false),
        TileInfo("alarm", Sym.ALARM, "Alarm", false),
        TileInfo("cast", Sym.CAST, "Casta skärmen", false),
        TileInfo("screenoff", Sym.POWER_SETTINGS_NEW, "Lås skärmen", false),
        TileInfo("nightlight", Sym.NIGHTLIGHT, "Nattljus", true),
        TileInfo("screenrecord", Sym.SCREEN_RECORD, "Skärminspelning", true),
        TileInfo("qr", Sym.QR_CODE_SCANNER, "Skanna QR-kod", false),
        TileInfo("quickshare", Sym.SHARE, "Quick Share", true),
        TileInfo("securefolder", Sym.FOLDER_MANAGED, "Hemlig mapp", false),
        TileInfo("dex", Sym.DESKTOP_WINDOWS, "Trådlös DeX", false),
        TileInfo("linkwindows", Sym.PHONELINK, "Länk till Windows", true),
        TileInfo("multicontrol", Sym.MOUSE, "Multikontroll", false),
        TileInfo("wificall", Sym.WIFI_CALLING, "Wi‑Fi-samtal", true),
        TileInfo("interpreter", Sym.TRANSLATE, "Tolk", false),
        TileInfo("auracast", Sym.PODCASTS, "Ljudsändning", false),
    )
    val byId = all.associateBy { it.id }

    /** The out-of-the-box layout: Pixel 10's default order, then the extras on later pages. */
    val defaultLayout: List<Pair<String, Boolean>> = listOf(
        "internet" to true, "bluetooth" to true, "dnd" to true, "torch" to true,
        "airplane" to false, "saver" to false, "rotate" to false, "location" to false,
        "data" to true, "dark" to true, "hotspot" to true, "nfc" to true,
        "camera" to false, "alarm" to false, "cast" to false, "screenoff" to false,
        "nightlight" to true, "screenrecord" to true,
        "qr" to false, "securefolder" to false, "dex" to false, "interpreter" to false,
    )

    fun load(ctx: Context): MutableList<Pair<String, Boolean>> {
        val raw = Prefs.tileLayout(ctx) ?: return defaultLayout.toMutableList()
        val list = raw.split(',').mapNotNull { item ->
            val parts = item.split(':')
            val id = parts.getOrNull(0) ?: return@mapNotNull null
            if (id !in byId) null else id to (parts.getOrNull(1) == "w")
        }
        return if (list.isEmpty()) defaultLayout.toMutableList() else list.toMutableList()
    }

    fun save(ctx: Context, layout: List<Pair<String, Boolean>>) =
        Prefs.setTileLayout(ctx, layout.joinToString(",") { "${it.first}:${if (it.second) "w" else "s"}" })

    /** Packs tiles into rows of four units (wide = 2), never splitting a wide tile across rows. */
    fun <T> rows(items: List<T>, wide: (T) -> Boolean): List<List<T>> {
        val out = ArrayList<List<T>>()
        var row = ArrayList<T>(); var used = 0
        for (it in items) {
            val span = if (wide(it)) 2 else 1
            if (used + span > 4) { out += row; row = ArrayList(); used = 0 }
            row += it; used += span
        }
        if (row.isNotEmpty()) out += row
        return out
    }
}

/**
 * A 4-column grid that packs wide (2) and small (1) cells like the shade does.
 * In edit mode: tap toggles wide/small, long-press and drag reorders.
 */
@SuppressLint("ViewConstructor")
class TileGrid(ctx: Context, private val gap: Int, private val rowH: Int) : ViewGroup(ctx) {
    /** (tile, x, y) with x/y relative to the tile. */
    var onTap: ((View, Float, Float) -> Unit)? = null
    var onReordered: (() -> Unit)? = null
    var reorderable = false

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var target: View? = null
    private var dragging = false
    private var moved = false
    private var grabDX = 0f; private var grabDY = 0f
    private var absX = 0f; private var absY = 0f
    private val longPress = Runnable {
        val t = target ?: return@Runnable
        if (!reorderable || moved) return@Runnable
        dragging = true
        parent.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        t.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start()
        t.elevation = context.dp(8f)
    }

    private fun span(v: View) = (v.tag as? Boolean)?.let { if (it) 2 else 1 } ?: 1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cell = (w - gap * 3) / 4
        var rows = 0; var used = 4
        for (i in 0 until childCount) {
            val c = getChildAt(i); val s = span(c)
            if (used + s > 4) { rows++; used = 0 }
            used += s
            c.measure(MeasureSpec.makeMeasureSpec(cell * s + gap * (s - 1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowH, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, if (rows == 0) 0 else rows * rowH + (rows - 1) * gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cell = (width - gap * 3) / 4
        var row = -1; var used = 4
        for (i in 0 until childCount) {
            val c = getChildAt(i); val s = span(c)
            if (used + s > 4) { row++; used = 0 }
            val x = used * (cell + gap); val y = row * (rowH + gap)
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            used += s
            if (c === target && dragging) { c.translationX = absX - x; c.translationY = absY - y }
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false; dragging = false
                target = (0 until childCount).map { getChildAt(it) }.firstOrNull {
                    e.x >= it.left && e.x <= it.right && e.y >= it.top && e.y <= it.bottom
                }
                target?.let { grabDX = e.x - it.left; grabDY = e.y - it.top }
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.x - downX, e.y - downY) > slop) { moved = true; removeCallbacks(longPress) }
                val t = target
                if (dragging && t != null) {
                    absX = e.x - grabDX; absY = e.y - grabDY
                    t.translationX = absX - t.left; t.translationY = absY - t.top
                    // Move into the slot of the nearest other tile.
                    val cx = e.x; val cy = e.y
                    var best = -1; var bestD = Float.MAX_VALUE
                    for (i in 0 until childCount) {
                        val c = getChildAt(i); if (c === t) continue
                        val d = hypot(cx - (c.left + c.width / 2f), cy - (c.top + c.height / 2f))
                        if (d < bestD) { bestD = d; best = i }
                    }
                    val cur = indexOfChild(t)
                    if (best >= 0 && bestD < rowH * 0.6f && best != cur) {
                        TransitionManager.beginDelayedTransition(this, ChangeBounds().setDuration(180).setInterpolator(EMPHASIZED))
                        removeView(t); addView(t, best)
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val t = target
                if (dragging && t != null) {
                    t.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(200)
                        .withEndAction { t.elevation = 0f }.start()
                    dragging = false
                    onReordered?.invoke()
                } else if (!moved && e.actionMasked == MotionEvent.ACTION_UP && t != null) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onTap?.invoke(t, e.x - t.left, e.y - t.top)
                }
                target = null
            }
        }
        return true
    }
}

/**
 * Pixel 10-style tile editor: current tiles (tap = big/small, hold and drag = move,
 * minus = remove) and the tiles you can add.
 */
@SuppressLint("ViewConstructor")
class TileEditor(
    ctx: Context, private val pal: Palette,
    private val layout: MutableList<Pair<String, Boolean>>,
    private val onDone: (List<Pair<String, Boolean>>) -> Unit,
) : FrameLayout(ctx) {

    private val gap = ctx.dpi(8f)
    private val rowH = ctx.dpi(68f)
    private val active = TileGrid(ctx, gap, rowH).apply { reorderable = true }
    private val available = TileGrid(ctx, gap, rowH)

    init {
        setBackgroundColor(pal.surface.withAlpha(if (pal.night) 240 else 245))
        isClickable = true
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(16f), ctx.statusBarHeight() + ctx.dpi(8f), ctx.dpi(16f), ctx.dpi(48f))
        }
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(SymbolView(ctx, Sym.ARROW_BACK, pal.onSurface, 24f).apply { setOnClickListener { done() } },
            LinearLayout.LayoutParams(ctx.dpi(44f), ctx.dpi(44f)))
        bar.addView(ctx.label("Redigera", 22f, pal.onSurface).apply { setPadding(ctx.dpi(8f), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(pill(ctx, pal, "Återställ") {
            layout.clear(); layout.addAll(TileCatalog.defaultLayout); fill()
        })
        col.addView(bar)
        col.addView(ctx.label("Tryck för stor eller liten. Håll in och dra för att flytta. Tryck på − för att ta bort.",
            13f, pal.onSurfaceVariant, lines = 0).apply { setPadding(ctx.dpi(4f), ctx.dpi(12f), 0, ctx.dpi(16f)) })
        col.addView(active, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(ctx.label("Lägg till knappar", 16f, pal.onSurface, medium = true).apply {
            setPadding(ctx.dpi(4f), ctx.dpi(28f), 0, ctx.dpi(12f))
        })
        col.addView(available, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(col) },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        active.onTap = { v, x, y ->
            val id = v.getTag(R.id.tile_id) as String
            val i = layout.indexOfFirst { it.first == id }
            if (x < context.dp(30f) && y < context.dp(30f)) { layout.removeAll { it.first == id }; fill() }
            else if (i >= 0) { layout[i] = id to !layout[i].second; fill() }
        }
        active.onReordered = {
            val order = (0 until active.childCount).map { active.getChildAt(it).getTag(R.id.tile_id) as String }
            val wide = layout.toMap()
            layout.clear(); layout.addAll(order.map { it to (wide[it] ?: false) })
        }
        available.onTap = { v, _, _ ->
            val id = v.getTag(R.id.tile_id) as String
            val info = TileCatalog.byId.getValue(id)
            layout += id to info.defaultWide; fill()
        }
        fill()
    }

    private fun preview(info: TileInfo, wide: Boolean, removable: Boolean): View {
        val ctx = context
        val holder = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        val t = Tile(ctx, pal, info.sym, info.label, "", wide).apply { isClickable = false; isEnabled = false }
        holder.addView(t, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val badge = SymbolView(ctx, if (removable) Sym.REMOVE else Sym.ADD,
            if (removable) pal.onSurface else pal.onPrimary, 16f).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (removable) pal.tileIconOff else pal.primary) }
        }
        holder.addView(badge, LayoutParams(ctx.dpi(24f), ctx.dpi(24f), Gravity.TOP or Gravity.START).apply {
            leftMargin = ctx.dpi(2f); topMargin = ctx.dpi(2f)
        })
        holder.tag = wide
        holder.setTag(R.id.tile_id, info.id)
        return holder
    }

    private fun fill() {
        TransitionManager.beginDelayedTransition(this, ChangeBounds().setDuration(200).setInterpolator(EMPHASIZED))
        active.removeAllViews(); available.removeAllViews()
        val used = layout.map { it.first }.toSet()
        layout.forEach { (id, wide) -> TileCatalog.byId[id]?.let { active.addView(preview(it, wide, true)) } }
        TileCatalog.all.filter { it.id !in used }.forEach { available.addView(preview(it, false, false)) }
    }

    fun done() = onDone(layout.toList())

    @Suppress("unused")
    private fun near(a: Float) = abs(a) < 0.001f
}
