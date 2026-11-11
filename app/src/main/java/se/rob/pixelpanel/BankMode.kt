package se.rob.pixelpanel

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings

/**
 * Bank apps refuse to run while a sideloaded accessibility service is on.
 * Bank mode switches Pixelpanel's service off as soon as a chosen app comes to the
 * front, watches the foreground app while it is off, and switches it back on once
 * you leave. Needs WRITE_SECURE_SETTINGS and usage access, granted once via Shizuku.
 */
object BankMode {
    private val main = Handler(Looper.getMainLooper())
    private var watching = false
    private var awayTicks = 0

    private const val SERVICE = "se.rob.pixelpanel/se.rob.pixelpanel.ShadeService"

    /** Words in an app's name that suggest it is a bank or ID app (pre-ticked in the list). */
    private val hints = listOf("bank", "bankid", "swish", "swedbank", "handelsbanken", "seb", "nordea",
        "länsförsäkringar", "lf ", "skandia", "avanza", "nordnet", "klarna", "revolut", "ica banken",
        "sparbank", "danske", "freja", "mobilt bankid", "kivra", "pension")

    fun canSwitch(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun ready(ctx: Context) = canSwitch(ctx) && hasUsageAccess(ctx)

    /** Grants both permissions through Shizuku. They survive reboots and updates. */
    fun grant(ctx: Context, done: (Boolean) -> Unit) {
        val pkg = ctx.packageName
        Shell.run("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS && appops set $pkg GET_USAGE_STATS allow") { done(it) }
    }

    fun isBank(ctx: Context, pkg: String?) =
        pkg != null && Prefs.bankModeOn(ctx) && pkg in Prefs.bankApps(ctx)

    /** Launcher apps, banks first. */
    fun candidates(ctx: Context): List<Pair<String, CharSequence>> {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm) }
            .distinctBy { it.first }
            .filter { it.first != ctx.packageName }
        return apps.sortedWith(compareBy<Pair<String, CharSequence>> { !looksLikeBank(it.second) }
            .thenBy { it.second.toString().lowercase() })
    }

    fun looksLikeBank(label: CharSequence): Boolean {
        val l = label.toString().lowercase()
        return hints.any { l.contains(it) }
    }

    private fun services(ctx: Context) =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }

    private fun isOurs(s: String) =
        ComponentName.unflattenFromString(s)?.let { it.packageName == "se.rob.pixelpanel" } == true

    /** Turn Pixelpanel's accessibility service off and start watching for the bank app to close. */
    fun suspend(ctx: Context) {
        if (!canSwitch(ctx)) return
        try {
            val rest = services(ctx).filterNot { isOurs(it) }
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, rest.joinToString(":"))
            Prefs.setBankSuspended(ctx, true)
            startWatching(ctx.applicationContext)
        } catch (t: Throwable) { CrashLog.write(ctx, "bankläge av", t) }
    }

    fun resume(ctx: Context) {
        try {
            val list = services(ctx).filterNot { isOurs(it) } + SERVICE
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (t: Throwable) { CrashLog.write(ctx, "bankläge på", t) }
        Prefs.setBankSuspended(ctx, false)
    }

    /** Polls the foreground app once a second, only while the service is suspended. */
    fun startWatching(ctx: Context) {
        if (watching) return
        watching = true; awayTicks = 0
        val tick = object : Runnable {
            override fun run() {
                if (!Prefs.bankSuspended(ctx)) { watching = false; return }
                val fg = foreground(ctx)
                if (fg != null && !isBank(ctx, fg)) awayTicks++ else awayTicks = 0
                if (awayTicks >= 2) { watching = false; resume(ctx); return }
                main.postDelayed(this, 1000)
            }
        }
        main.postDelayed(tick, 1500)
    }

    /** Most recent app to come to the foreground, from usage events. */
    private fun foreground(ctx: Context): String? {
        if (!hasUsageAccess(ctx)) return null
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - 60_000, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }
}
