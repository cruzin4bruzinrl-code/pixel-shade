package se.rob.pixelpanel

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue

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
class Palette(ctx: Context) {
    private fun c(id: Int) = ctx.getColor(id)
    val night = ctx.isNight()

    val primary = if (night) c(android.R.color.system_accent1_200) else c(android.R.color.system_accent1_600)
    val onPrimary = if (night) c(android.R.color.system_accent1_800) else c(android.R.color.system_accent1_0)
    val primaryContainer = if (night) c(android.R.color.system_accent1_700) else c(android.R.color.system_accent1_100)
    val surface = if (night) c(android.R.color.system_neutral1_900) else c(android.R.color.system_neutral1_50)
    val surfaceContainer = if (night) c(android.R.color.system_neutral1_800) else c(android.R.color.system_neutral1_100)
    val surfaceContainerHigh = if (night) c(android.R.color.system_neutral2_800) else c(android.R.color.system_neutral2_100)
    val onSurface = if (night) c(android.R.color.system_neutral1_100) else c(android.R.color.system_neutral1_900)
    val onSurfaceVariant = if (night) c(android.R.color.system_neutral2_200) else c(android.R.color.system_neutral2_700)
    val scrim = if (night) c(android.R.color.system_neutral1_1000) else c(android.R.color.system_neutral1_900)
    val tertiary = if (night) c(android.R.color.system_accent3_200) else c(android.R.color.system_accent3_600)
    val secondary = if (night) c(android.R.color.system_accent2_200) else c(android.R.color.system_accent2_600)
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

    fun lastStats(ctx: Context) = sp(ctx).getString("lastStats", null)
    fun setLastStats(ctx: Context, v: String) = sp(ctx).edit().putString("lastStats", v).apply()
}
