package se.rob.pixelpanel

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.TonalPalette
import java.io.File

/**
 * Pixel "Bakgrund och stil" (AOSP packages/apps/ThemePicker): preview, colour options split into
 * "Bakgrundsfärger" (up to 4 wallpaper seeds x TONAL_SPOT / SPRITZ / VIBRANT / EXPRESSIVE, as in
 * ColorProvider.buildBundle) and "Standardfärger", the 4-quadrant swatch from getLightColorPreview,
 * dark theme, and the Lawnchair side (themed icons, app grid, shape) since Lawnchair is the launcher.
 */
object ThemePicker {
    private val presets = listOf(0xFF1A73E8, 0xFF00897B, 0xFF34A853, 0xFFFBBC04, 0xFFFF6D00, 0xFFEA4335, 0xFFE91E63, 0xFF8E24AA, 0xFF757575)
        .map { it.toInt() }
    private val styleNames = listOf("Dynamisk", "Neutral", "Livfull", "Uttrycksfull")
    private const val LAWNCHAIR = "app.lawnchair"

    fun spec(a: PixelSettingsActivity) = SettingsPages.Spec("style", "Bakgrund och stil", listOf(
        Custom { act -> preview(act) },
        Category("Färger"),
        Custom { act -> colors(act) },
        Category(null),
        Row("Mörkt tema", null, toggle = Toggle(
            get = { a.getSystemService(android.app.UiModeManager::class.java).nightMode == android.app.UiModeManager.MODE_NIGHT_YES },
            set = { on -> a.shell("cmd uimode night ${if (on) "yes" else "no"}") })),
        Row("Använd färgerna i hela systemet", { "Gboard, Lawnchair och appar med Material You får samma färger" }, toggle = Toggle(
            get = { Prefs.systemColors(a) },
            set = { on ->
                Prefs.setSystemColors(a, on)
                if (on) SystemColors.apply(a) { Toast.makeText(a, it, Toast.LENGTH_LONG).show() }
                else SystemColors.restore(a) { Toast.makeText(a, it, Toast.LENGTH_SHORT).show() }
            })),
        Category("Hemskärm (Lawnchair)"),
        Row("Temaikoner", { "Appikoner i bakgrundens färger" }) { lawnchair(a) },
        Row("Form och layout", { "Ikonform och apprutnät" }) { lawnchair(a) },
        Row("Byt bakgrund", null) {
            a.openSystem(Intent("com.samsung.intent.action.WALLPAPER_SETTING"),
                Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Bakgrund"))
        },
    ))

    private fun lawnchair(a: PixelSettingsActivity) {
        val pm = a.packageManager
        val i = pm.getLaunchIntentForPackage(LAWNCHAIR)
        if (i == null) {
            a.openSystem(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$LAWNCHAIR")))
            return
        }
        a.openSystem(Intent().setClassName(LAWNCHAIR, "app.lawnchair.ui.preferences.PreferenceActivity"), i)
    }

    // ---- preview -----------------------------------------------------------------------------

    private fun preview(a: PixelSettingsActivity): View {
        val box = FrameLayout(a).apply { setPadding(a.dpi(16f), a.dpi(20f), a.dpi(16f), a.dpi(20f)) }
        val f = File(a.filesDir, "home.jpg")
        val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        val phone = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (bmp != null) setImageBitmap(bmp) else setBackgroundColor(a.sc.highest)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, a.dp(24f)) }
            }
            clipToOutline = true
        }
        val w = a.dpi(150f)
        box.addView(phone, FrameLayout.LayoutParams(w, (w * 2.17f).toInt(), Gravity.CENTER_HORIZONTAL))
        if (bmp == null) box.addView(TextView(a).apply {
            text = "Visa hemskärmen en stund så syns förhandsvisningen här"; textSize = 13f; setTextColor(a.sc.onVariant)
            gravity = Gravity.CENTER; setPadding(a.dpi(24f), 0, a.dpi(24f), 0)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return box
    }

    // ---- colours -----------------------------------------------------------------------------

    private var tab = -1

    private fun colors(a: PixelSettingsActivity): View {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dpi(16f), a.dpi(16f), a.dpi(16f), a.dpi(16f)) }
        if (tab < 0) tab = if (Prefs.seedColor(a) == 0) 0 else 1
        // segmented tabs
        val tabs = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val sub = TextView(a).apply { textSize = 14f; setTextColor(a.sc.onVariant); setPadding(a.dpi(4f), a.dpi(16f), a.dpi(4f), a.dpi(12f)) }
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        lateinit var render: () -> Unit
        listOf("Bakgrundsfärger", "Standardfärger").forEachIndexed { i, t ->
            tabs.addView(TextView(a).apply {
                text = t; textSize = 14f; typeface = Fonts.medium; gravity = Gravity.CENTER
                setPadding(a.dpi(12f), a.dpi(10f), a.dpi(12f), a.dpi(10f))
                setOnClickListener { tab = i; render() }
                tag = i
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = a.dpi(8f) })
        }
        box.addView(tabs); box.addView(sub); box.addView(grid)
        render = {
            for (k in 0 until tabs.childCount) (tabs.getChildAt(k) as TextView).apply {
                val on = k == tab
                background = GradientDrawable().apply { cornerRadius = a.dp(100f); setColor(if (on) a.sc.primaryContainer else a.sc.highest) }
                setTextColor(if (on) a.sc.onPrimaryContainer else a.sc.onSurface)
            }
            sub.text = if (tab == 0) "Ikoner, text med mera matchar bakgrundens färger" else "Använd valfri färg för ikoner, klockan med mera"
            grid.removeAllViews()
            val opts = options(a)
            opts.chunked(4).forEach { rowOpts ->
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, a.dpi(4f), 0, a.dpi(4f)) }
                rowOpts.forEach { o ->
                    row.addView(SwatchView(a, o.preview, o.selected).apply {
                        contentDescription = o.label
                        setOnClickListener { apply(a, o) }
                    }, LinearLayout.LayoutParams(0, a.dpi(72f), 1f))
                }
                repeat(4 - rowOpts.size) { row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f)) }
                grid.addView(row)
            }
            if (opts.isEmpty()) grid.addView(TextView(a).apply {
                text = "Visa hemskärmen en stund så hittas bakgrundens färger"; setTextColor(a.sc.onVariant); textSize = 14f
            })
        }
        render()
        return box
    }

    class Opt(val seed: Int, val wallIndex: Int, val style: Int, val label: String, val preview: IntArray, val selected: Boolean)

    private fun options(a: Context): List<Opt> {
        val curSeed = Prefs.seedColor(a); val curStyle = Prefs.themeStyle(a); val curWall = Prefs.wallChoice(a)
        return if (tab == 0) {
            val seeds = Prefs.wallSeeds(a).take(4)
            // ColorProvider: every wallpaper seed in each of the four styles
            seeds.flatMapIndexed { i, seed ->
                (0..3).map { st ->
                    Opt(seed, i, st, "${styleNames[st]} ${i + 1}", swatch(seed, st), curSeed == 0 && curWall == i && curStyle == st)
                }
            }.sortedBy { it.style * 10 + it.wallIndex }
        } else presets.map { c -> Opt(c, -1, 0, "Färg", swatch(c, 0), curSeed == c && curStyle == 0) }
    }

    /** getLightColorPreview: accent1 600, accent1 600, accent2 500 at L*85, accent3 300. */
    private fun swatch(seed: Int, style: Int): IntArray {
        val hue = Hct.fromInt(seed).hue
        fun tp(h: Double, c: Double) = TonalPalette.fromHueAndChroma(((h % 360) + 360) % 360, c)
        val (p1, p2, p3) = when (style) {
            1 -> Triple(tp(hue, 12.0), tp(hue, 8.0), tp(hue, 16.0))
            2 -> Triple(tp(hue, maxOf(Hct.fromInt(seed).chroma, 48.0)), tp(hue + 15, 24.0), tp(hue + 30, 32.0))
            3 -> Triple(tp(hue + 240, 40.0), tp(hue + 45, 24.0), tp(hue + 120, 32.0))
            else -> Triple(tp(hue, 36.0), tp(hue, 16.0), tp(hue + 60, 24.0))
        }
        return intArrayOf(p1.tone(40), p1.tone(40), p2.tone(85), p3.tone(70))
    }

    private fun apply(a: PixelSettingsActivity, o: Opt) {
        if (o.wallIndex >= 0) {
            Prefs.setSeedColor(a, 0); Prefs.setWallChoice(a, o.wallIndex); Prefs.setWallSeed(a, o.seed); WallSeed.cached = o.seed
        } else Prefs.setSeedColor(a, o.seed)
        Prefs.setThemeStyle(a, o.style)
        Toast.makeText(a, "Färgen har ändrats", Toast.LENGTH_SHORT).show()
        ShadeService.instance?.let { s -> s.rebuildShade(); try { s.volume.recolor() } catch (_: Exception) { } }
        if (Prefs.systemColors(a)) SystemColors.apply(a) { }
        a.intent.putExtra("page", "style")
        a.recreate()
    }

    /** Pixel colour option: circle, top half primary, bottom-left secondary, bottom-right tertiary. */
    class SwatchView(ctx: Context, private val c: IntArray, private val selected: Boolean) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(cv: Canvas) {
            val d = resources.displayMetrics.density
            val size = minOf(width, height) - 8 * d
            val cx = width / 2f; val cy = height / 2f
            val rad = if (selected) size / 2 - 6 * d else size / 2
            r.set(cx - rad, cy - rad, cx + rad, cy + rad)
            p.style = Paint.Style.FILL
            p.color = c[0]; cv.drawArc(r, 180f, 180f, true, p)
            p.color = c[2]; cv.drawArc(r, 90f, 90f, true, p)
            p.color = c[3]; cv.drawArc(r, 0f, 90f, true, p)
            if (selected) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = c[0]
                cv.drawCircle(cx, cy, size / 2 - 1.5f * d, p)
            }
        }
    }
}
