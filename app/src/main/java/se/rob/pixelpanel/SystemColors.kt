package se.rob.pixelpanel

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.material.color.utilities.Hct

/**
 * Writes the Pixel wallpaper colour into Android's own palette setting, the way Pixel's wallpaper
 * picker does (Settings.Secure theme_customization_overlay_packages, see AOSP ThemeOverlayApplier:
 * system_palette + accent_color, theme_style TONAL_SPOT, color_source "preset"). Gboard's
 * "Dynamic colour" theme and every other Material You app then use the same colours as on Pixel.
 * Afterwards it reads back system_accent1_500 to check that One UI really applied it.
 */
object SystemColors {
    private const val KEY = "theme_customization_overlay_packages"
    private const val GBOARD = "com.google.android.inputmethod.latin"
    private val main = Handler(Looper.getMainLooper())

    fun seed(ctx: Context): Int? {
        val s = Prefs.seedColor(ctx).let { if (it == 0) WallSeed.get(ctx) else it }
        return if (s == 0 || s == 1) null else s
    }

    fun apply(ctx: Context, done: (String) -> Unit) {
        val seed = seed(ctx) ?: return done("Ingen Pixel-färg ännu – visa hemskärmen en stund först.")
        val hex = String.format("%08X", seed or 0xFF000000.toInt())
        Shell.output("settings get secure $KEY") { old ->
            val sp = ctx.getSharedPreferences("pixelpanel", Context.MODE_PRIVATE)
            if (!sp.contains("savedThemeJson")) sp.edit().putString("savedThemeJson", old ?: "null").apply()
            val json = "{\"android.theme.customization.color_source\":\"preset\"," +
                "\"android.theme.customization.system_palette\":\"$hex\"," +
                "\"android.theme.customization.accent_color\":\"$hex\"," +
                "\"android.theme.customization.theme_style\":\"TONAL_SPOT\"," +
                "\"_applied_timestamp\":${System.currentTimeMillis()}}"
            Shell.run("settings put secure $KEY '$json'") { ok ->
                if (!ok) return@run done("Kunde inte skriva inställningen (Shizuku?).")
                main.postDelayed({ verify(ctx, seed, done) }, 3000)
            }
        }
    }

    private fun verify(ctx: Context, seed: Int, done: (String) -> Unit) {
        Shell.output("cmd overlay lookup android android:color/system_accent1_500") { out ->
            val m = out?.let { Regex("#?([0-9a-fA-F]{8})").find(it) }
            val got = m?.groupValues?.get(1)?.toLongOrNull(16)?.toInt()
            Shell.run("am force-stop $GBOARD")
            if (got == null) return@output done("Skrivet. Kunde inte läsa tillbaka systemfärgen: ${out ?: "inget svar"}")
            val a = Hct.fromInt(got).hue; val b = Hct.fromInt(seed).hue
            val d = kotlin.math.abs(a - b).let { kotlin.math.min(it, 360 - it) }
            done(if (d < 25) "Klart – systemets palett följer nu Pixel-färgen (nyans ${b.toInt()}°). Gboard startas om."
                 else "One UI behöll sin egen palett (system ${a.toInt()}°, Pixel ${b.toInt()}°). Samsung skriver över inställningen.")
        }
    }

    fun restore(ctx: Context, done: (String) -> Unit) {
        val sp = ctx.getSharedPreferences("pixelpanel", Context.MODE_PRIVATE)
        val old = sp.getString("savedThemeJson", null) ?: return done("Inget att återställa.")
        val cmd = if (old == "null" || old.isBlank()) "settings delete secure $KEY" else "settings put secure $KEY '$old'"
        Shell.run(cmd) { ok ->
            if (ok) sp.edit().remove("savedThemeJson").apply()
            Shell.run("am force-stop $GBOARD")
            done(if (ok) "Samsungs färger återställda." else "Kunde inte återställa.")
        }
    }
}
