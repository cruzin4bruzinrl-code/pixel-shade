package se.rob.pixelpanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Keeps bank mode up to date without manual work:
 *  - a newly installed app whose name looks like a bank/ID app is added straight away;
 *  - any app that closes itself right after opening (what bank apps do when they spot
 *    Pixelpanel) gets a one-tap "add to bank mode" notification.
 */
object BankSuggest {
    private const val CHANNEL = "bankmode"

    private fun nm(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bankläge", NotificationManager.IMPORTANCE_DEFAULT))
        }
        return nm
    }

    private fun label(ctx: Context, pkg: String): CharSequence = AppInfo.label(ctx, pkg)

    fun onInstalled(ctx: Context, pkg: String) {
        if (pkg in Prefs.bankApps(ctx)) return
        val name = label(ctx, pkg)
        if (BankMode.looksLikeBank(name)) {
            Prefs.setBankApps(ctx, Prefs.bankApps(ctx) + pkg)
            post(ctx, pkg, "$name lades till i bankläge", "Pixelpanel stängs av när $name öppnas.", withAction = false)
        }
    }

    /** Only apps you installed yourself; never Samsung/Google/system components. */
    private fun userApp(ctx: Context, pkg: String): Boolean {
        if (pkg.startsWith("com.samsung.") || pkg.startsWith("com.sec.") || pkg.startsWith("com.android.") ||
            pkg.startsWith("com.google.android.") || pkg == "android") return false
        return try {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            ai.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 &&
                ctx.packageManager.getLaunchIntentForPackage(pkg) != null
        } catch (_: Exception) { false }
    }

    fun onQuickExit(ctx: Context, pkg: String) {
        if (pkg in Prefs.bankApps(ctx) || pkg in Prefs.suggested(ctx) || !userApp(ctx, pkg)) return
        Prefs.addSuggested(ctx, pkg)
        val name = label(ctx, pkg)
        post(ctx, pkg, "Stängdes $name direkt?", "Bankappar stänger sig när Pixelpanel är på. Lägg till $name i bankläge.", withAction = true)
    }

    private fun post(ctx: Context, pkg: String, title: String, text: String, withAction: Boolean) {
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (withAction) {
            val pi = PendingIntent.getBroadcast(
                ctx, pkg.hashCode(),
                Intent(ctx, BankActionReceiver::class.java).putExtra("pkg", pkg),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(Notification.Action.Builder(null, "Lägg till i bankläge", pi).build())
        }
        try { nm(ctx).notify(pkg.hashCode(), b.build()) } catch (_: SecurityException) { }
    }
}

class BankActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg") ?: return
        Prefs.setBankApps(ctx, Prefs.bankApps(ctx) + pkg)
        ctx.getSystemService(NotificationManager::class.java).cancel(pkg.hashCode())
    }
}

/** Opens Pixelpanel's shade. Point Lawnchair's "swipe down" gesture at this so it opens Pixelpanel, not Samsung's panel. */
class OpenShadeActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val s = ShadeService.instance
        if (s != null) s.openShade() else ShadeService.fallbackPanel(this)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
