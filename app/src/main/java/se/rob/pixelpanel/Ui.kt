package se.rob.pixelpanel

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.TonalPalette

fun Context.dp(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun Context.dpi(v: Float): Int = dp(v).toInt()

fun Context.statusBarHeight(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else dpi(32f)
}

fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/**
 * Material 3 colour roles taken from the system's dynamic (Monet) tonal palettes.
 * Palette index -> tone: _0=100, _10=99, _50=95, _100=90, _200=80, _300=70,
 * _400=60, _500=50, _600=40, _700=30, _800=20, _900=10, _1000=0.
 */
class Palette(private val ctx: Context) {
    val night = ctx.isNight()
    // 0 = take the seed from the wallpaper ourselves (like Pixel), 1 = Samsung's own palette, else a basic colour.
    private val seed = Prefs.seedColor(ctx).let { if (it == 0) WallSeed.get(ctx) else it }.let { if (it == 1) 0 else it }

    // Pixel's "basic colours": a fixed seed run through the same tonal-spot recipe Monet uses.
    private val palettes: Array<TonalPalette>? = if (seed == 0) null else {
        val hue = Hct.fromInt(seed).hue
        val grey = Hct.fromInt(seed).chroma < 8.0
        arrayOf(
            TonalPalette.fromHueAndChroma(hue, if (grey) 4.0 else 36.0),
            TonalPalette.fromHueAndChroma(hue, if (grey) 4.0 else 16.0),
            TonalPalette.fromHueAndChroma((hue + 60.0) % 360.0, if (grey) 4.0 else 24.0),
            TonalPalette.fromHueAndChroma(hue, if (grey) 2.0 else 6.0),
            TonalPalette.fromHueAndChroma(hue, if (grey) 3.0 else 8.0),
        )
    }

    /** group 1..5 = accent1, accent2, accent3, neutral1, neutral2; step = system palette step (0..1000). */
    private fun t(group: Int, step: Int): Int {
        val pals = palettes
        if (pals == null) {
            val name = "system_" + arrayOf("accent1", "accent2", "accent3", "neutral1", "neutral2")[group - 1] + "_" + step
            return ctx.getColor(ctx.resources.getIdentifier(name, "color", "android"))
        }
        val tone = when (step) { 0 -> 100; 10 -> 99; 50 -> 95; else -> 100 - step / 10 }
        return pals[group - 1].tone(tone)
    }

    val primary = if (night) t(1, 200) else t(1, 600)
    val onPrimary = if (night) t(1, 800) else t(1, 0)
    val primaryContainer = if (night) t(1, 700) else t(1, 100)
    val surface = if (night) t(4, 900) else t(4, 50)
    /** Notification rows: surfaceEffect1 when blur is on (AOSP notificationRowTransparency), like the tiles. */
    val card get() = effect1
    /** Inactive QS tile: translucent so the blurred backdrop shows through, like Pixel 10. */
    // AOSP Android 16 QPR1 "surface effect" tokens (frameworks/base core/res/res/color[-night]/surface_effect_N_color.xml)
    private fun lstar(c: Int, l: Double) = Hct.fromInt(c).let { Hct.from(it.hue, it.chroma, l).toInt() }
    /** surface_effect_1: neutral1_500 at L*98 (night L*6), 54 % — inactive tiles, slider track. */
    val effect1 = lstar(t(4, 500), if (night) 6.0 else 98.0).withAlpha((0.54f * 255).toInt())
    /** surface_effect_2: accent1_0 at 32 % (night accent1_100 at 15 %) — inactive dual-target icon box. */
    val effect2 = if (night) t(1, 100).withAlpha((0.15f * 255).toInt()) else t(1, 0).withAlpha((0.32f * 255).toInt())
    val tileOff = effect1
    /** Inactive part of the brightness slider: secondary container, clearly visible over the blur. */
    val sliderTrack = effect1
    /** Icon container inside an inactive wide tile. */
    val tileIconOff = effect2
    val surfaceContainer = if (night) t(4, 800) else t(4, 100)
    val surfaceContainerHigh = if (night) t(5, 800) else t(5, 100)
    val onSurface = if (night) t(4, 100) else t(4, 900)
    val onSurfaceVariant = if (night) t(5, 200) else t(5, 700)
    val scrim = if (night) t(4, 1000) else t(4, 900)
    val tertiary = if (night) t(3, 200) else t(3, 600)
    val secondary = if (night) t(2, 200) else t(2, 600)
}

object Prefs {
    private const val NAME = "pixelpanel"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun captureEnabled(ctx: Context) = sp(ctx).getBoolean("capture", true)
    fun setCaptureEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("capture", v).apply()

    fun blurScales(ctx: Context) = sp(ctx).getBoolean("blurScales", true)
    fun setBlurScales(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("blurScales", v).apply()

    /** Blur radius in dp. */
    fun blurRadius(ctx: Context) = sp(ctx).getInt("blurRadius", 40)
    fun setBlurRadius(ctx: Context, v: Int) = sp(ctx).edit().putInt("blurRadius", v).apply()

    /** Fraction of the screen width, from the right, left to Samsung's own panel. */
    fun samsungCorner(ctx: Context) = sp(ctx).getBoolean("samsungCorner2", false)
    fun setSamsungCorner(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("samsungCorner2", v).apply()

    /** Quick settings layout: "id:w,id:s,…" (w = wide). Null = default. */
    fun tileLayout(ctx: Context): String? = sp(ctx).getString("tileLayout", null)
    fun setTileLayout(ctx: Context, v: String) = sp(ctx).edit().putString("tileLayout", v).apply()

    fun bankModeOn(ctx: Context) = sp(ctx).getBoolean("bankMode", true)
    fun setBankModeOn(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("bankMode", v).apply()
    fun bankApps(ctx: Context): Set<String> = sp(ctx).getStringSet("bankApps", null) ?: emptySet()
    fun setBankApps(ctx: Context, v: Set<String>) = sp(ctx).edit().putStringSet("bankApps", HashSet(v)).apply()
    fun hasBankApps(ctx: Context) = sp(ctx).contains("bankApps")
    fun bankSuspended(ctx: Context) = sp(ctx).getBoolean("bankSuspended", false)
    fun setBankSuspended(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("bankSuspended", v).commit()

    /** 0 = follow the wallpaper (system Monet); otherwise a seed colour like Pixel's basic colours. */
    fun seedColor(ctx: Context) = sp(ctx).getInt("seedColor", 0)
    fun setSeedColor(ctx: Context, v: Int) = sp(ctx).edit().putInt("seedColor", v).apply()
    fun wallSeed(ctx: Context) = sp(ctx).getInt("wallSeed", 0)
    fun wallSeeds(ctx: Context): List<Int> = sp(ctx).getString("wallSeeds", "")!!.split(',').mapNotNull { it.toIntOrNull() }
    fun setWallSeeds(ctx: Context, v: List<Int>) = sp(ctx).edit().putString("wallSeeds", v.joinToString(",")).apply()
    fun wallChoice(ctx: Context) = sp(ctx).getInt("wallChoice", 0)
    fun setWallChoice(ctx: Context, v: Int) = sp(ctx).edit().putInt("wallChoice", v).apply()
    fun setWallSeed(ctx: Context, v: Int) = sp(ctx).edit().putInt("wallSeed", v).apply()
    fun hideIcon(ctx: Context) = sp(ctx).getBoolean("hideIcon", false)
    fun setHideIcon(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("hideIcon", v).apply()
    fun suggested(ctx: Context): Set<String> = sp(ctx).getStringSet("bankSuggested", null) ?: emptySet()
    fun addSuggested(ctx: Context, pkg: String) = sp(ctx).edit().putStringSet("bankSuggested", HashSet(suggested(ctx) + pkg)).apply()

    fun lastStats(ctx: Context) = sp(ctx).getString("lastStats", null)
    fun setLastStats(ctx: Context, v: String) = sp(ctx).edit().putString("lastStats", v).apply()
}


/**
 * The wallpaper's main colour. Learned from home-screen screenshots (Samsung keeps wallpaper
 * colours from other apps), else the system's WallpaperColors, else 1 (= system palette).
 */
object WallSeed {
    @Volatile var cached: Int? = null
    private var pending = 0
    private var pendingCount = 0
    private fun near(a: Int, b: Int): Boolean {
        val x = Hct.fromInt(a); val y = Hct.fromInt(b)
        val d = kotlin.math.abs(x.hue - y.hue).let { kotlin.math.min(it, 360 - it) }
        return d < 15 && kotlin.math.abs(x.chroma - y.chroma) < 12
    }
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun get(ctx: Context): Int = cached ?: run {
        val learned = Prefs.wallSeed(ctx)
        val c = if (learned != 0) learned else try {
            android.app.WallpaperManager.getInstance(ctx)
                .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
        } catch (_: Throwable) { null }
        (c ?: 1).also { cached = it }
    }

    /** Picks a seed the way Monet does (quantize + score) from a screenshot of the home screen. */
    fun learn(ctx: Context, shot: android.graphics.Bitmap, changed: () -> Unit) {
        worker.execute {
            try {
                val soft = shot.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return@execute
                val w = 112; val h = (soft.height * w / soft.width).coerceAtLeast(1)
                val small = android.graphics.Bitmap.createScaledBitmap(soft, w, h, true)
                // Skip the status bar strip and the dock area, which are mostly icons.
                val top = (h * 0.06f).toInt(); val bottom = (h * 0.80f).toInt()
                val px = IntArray(w * (bottom - top))
                small.getPixels(px, 0, w, 0, top, w, bottom - top)
                val counts = com.google.android.material.color.utilities.QuantizerCelebi.quantize(px, 128)
                // Up to four wallpaper colours, like Pixel's "Wallpaper colours" options.
                val seeds = com.google.android.material.color.utilities.Score.score(counts, 4)
                if (seeds.isEmpty()) return@execute
                Prefs.setWallSeeds(ctx, seeds)
                val seed = seeds[Prefs.wallChoice(ctx).coerceIn(0, seeds.size - 1)]
                val old = Prefs.wallSeed(ctx)
                val oldH = if (old == 0) null else Hct.fromInt(old)
                val newH = Hct.fromInt(seed)
                val dh = oldH?.let { kotlin.math.abs(it.hue - newH.hue).let { d -> kotlin.math.min(d, 360 - d) } } ?: 999.0
                val moved = oldH == null || dh > 15 || kotlin.math.abs(oldH.chroma - newH.chroma) > 12
                // A changed colour has to be seen twice in a row before the shade recolours, so a
                // folder, the app drawer or a widget on screen can't make the colours flicker.
                val confirmed = oldH == null || run {
                    if (pending != 0 && near(pending, seed)) pendingCount++ else { pending = seed; pendingCount = 1 }
                    pendingCount >= 2
                }
                if (!moved) { pending = 0; pendingCount = 0 }
                if (moved && confirmed) {
                    pending = 0; pendingCount = 0
                    Prefs.setWallSeed(ctx, seed)
                    cached = seed
                    main.post { changed() }
                }
            } catch (_: Throwable) { }
        }
    }
}
