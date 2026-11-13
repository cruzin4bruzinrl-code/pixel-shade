package se.rob.pixelpanel

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** "Stäng av allt": turns every Pixel feature off and puts One UI back as it was. */
object Revert {
    private const val SAMSUNG_HOME = "com.sec.android.app.launcher"
    private const val SAMSUNG_KB = "com.samsung.android.honeyboard/.service.HoneyBoardService"

    fun all(ctx: Context, done: (String) -> Unit) {
        val app = ctx.applicationContext
        // Our own switches
        Prefs.setPixelVolume(app, false)
        Prefs.setPixelSettings(app, false)
        Prefs.setKeepGboard(app, false)
        Prefs.setSystemColors(app, false)
        app.getSharedPreferences("pixelpanel", Context.MODE_PRIVATE).edit().putBoolean("allOff", true).apply()
        // Hide the extra "Inställningar" and wallpaper-style launchers
        setAliases(app, false)

        if (!Shell.granted()) {
            ShadeService.instance?.disableSelf()
            return done("Pixel-funktionerna är av. Shizuku saknas, så färger, hemskärm och tangentbord måste återställas för hand.")
        }
        SystemColors.restore(app) { _ ->
            Shell.run("cmd role add-role-holder --user 0 android.app.role.HOME $SAMSUNG_HOME")
            Shell.run("ime enable $SAMSUNG_KB")
            Shell.run("ime set $SAMSUNG_KB")
            val me = ComponentName(app, ShadeService::class.java).flattenToString()
            val cur = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val rest = cur.split(':').filter { it.isNotBlank() && !it.equals(me, true) &&
                !it.equals("${app.packageName}/.ShadeService", true) }.joinToString(":")
            Shell.run(if (rest.isEmpty()) "settings delete secure enabled_accessibility_services"
                      else "settings put secure enabled_accessibility_services '$rest'") { ok ->
                if (!ok) ShadeService.instance?.disableSelf()
                done("Klart! Allt Pixel är avstängt: Samsungs färger, One UI-hemskärm, Samsung Keyboard och One UI-panelen är tillbaka.")
            }
        }
    }

    fun isOff(ctx: Context) = ctx.getSharedPreferences("pixelpanel", Context.MODE_PRIVATE).getBoolean("allOff", false)

    fun undo(ctx: Context) {
        ctx.getSharedPreferences("pixelpanel", Context.MODE_PRIVATE).edit().putBoolean("allOff", false).apply()
        setAliases(ctx, true)
    }

    private fun setAliases(ctx: Context, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        listOf(".SettingsLauncher", ".WallpaperStyle").forEach {
            try { ctx.packageManager.setComponentEnabledSetting(ComponentName(ctx.packageName, ctx.packageName + it), state, PackageManager.DONT_KILL_APP) }
            catch (_: Exception) { }
        }
    }
}
