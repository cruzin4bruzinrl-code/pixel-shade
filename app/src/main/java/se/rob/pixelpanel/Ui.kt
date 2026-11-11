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
    private val seed = Prefs.seedColor(ctx)

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
    /** Notification cards and pills: near-white in light mode, raised in dark mode. */
    val card = if (night) t(4, 800) else t(4, 10)
    /** Inactive QS tile: translucent so the blurred backdrop shows through, like Pixel 10. */
    val tileOff = if (night) t(4, 800).withAlpha(215) else t(4, 10).withAlpha(190)
    /** Inactive part of the brightness slider: secondary container, clearly visible over the blur. */
    val sliderTrack = if (night) t(2, 800) else t(2, 100)
    /** Icon container inside an inactive wide tile. */
    val tileIconOff = if (night) t(5, 700).withAlpha(200) else t(5, 100).withAlpha(220)
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
    fun samsungCorner(ctx: Context) = sp(ctx).getBoolean("samsungCorner", true)
    fun setSamsungCorner(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("samsungCorner", v).apply()

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
    fun hideIcon(ctx: Context) = sp(ctx).getBoolean("hideIcon", false)
    fun setHideIcon(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("hideIcon", v).apply()
    fun suggested(ctx: Context): Set<String> = sp(ctx).getStringSet("bankSuggested", null) ?: emptySet()
    fun addSuggested(ctx: Context, pkg: String) = sp(ctx).edit().putStringSet("bankSuggested", HashSet(suggested(ctx) + pkg)).apply()

    fun lastStats(ctx: Context) = sp(ctx).getString("lastStats", null)
    fun setLastStats(ctx: Context, v: String) = sp(ctx).edit().putString("lastStats", v).apply()
}
