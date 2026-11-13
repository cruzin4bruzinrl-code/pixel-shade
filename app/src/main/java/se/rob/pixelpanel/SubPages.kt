package se.rob.pixelpanel

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pixel 10 Settings sub-pages "Appar" (apps.xml), "Aviseringar" (configure_notification_settings.xml)
 * and "Lagring" (storage_dashboard_fragment.xml), with Pixel's Swedish strings.
 */
object SubPages {

    private fun i(action: String) = Intent(action)
    private fun details(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

    private class AppEntry(val pkg: String, val label: String, val icon: Drawable?, val lastUsed: Long)

    private fun launchable(a: Context): List<AppEntry> {
        val pm = a.packageManager
        val usage = recentUsage(a)
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(q, 0).map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            .filter { it.packageName != a.packageName }
            .map { ai -> AppEntry(ai.packageName, ai.loadLabel(pm).toString(), try { ai.loadIcon(pm) } catch (_: Exception) { null }, usage[ai.packageName] ?: 0L) }
            .sortedBy { it.label.lowercase() }
    }

    private fun recentUsage(a: Context): Map<String, Long> = try {
        val um = a.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        um.queryAndAggregateUsageStats(now - 7 * 24 * 3600_000L, now).mapValues { it.value.lastTimeUsed }
    } catch (_: Exception) { emptyMap() }

    private fun ago(t: Long) = if (t <= 0) "" else DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    // ---- Appar ---------------------------------------------------------------------------------

    fun apps(a: PixelSettingsActivity): SettingsPages.Spec {
        val all = launchable(a)
        val recent = all.filter { it.lastUsed > 0 }.sortedByDescending { it.lastUsed }.take(3)
        val items = ArrayList<Item>()
        items += Category("Senast öppnade appar")
        recent.forEach { e -> items += Row(e.label, { ago(e.lastUsed) }, e.icon) { a.openSystem(details(e.pkg)) } }
        items += Row("Visa alla ${all.size} appar", null) { SettingsPages.open(a, "allapps") }
        items += Category("Allmänt")
        items += Row("Standardappar", { "Assistent, webbläsare, telefon och sms" }) { a.openSystem(i(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        items += Row("Appar som inte används", { "Pausa appaktivitet om den inte används" }) {
            a.openSystem(i("android.intent.action.MANAGE_UNUSED_APPS"), i(Settings.ACTION_APPLICATION_SETTINGS))
        }
        items += Row("Batteriförbrukning för appar", { "Ange batteriförbrukning för appar" }) { a.openSystem(i(Intent.ACTION_POWER_USAGE_SUMMARY)) }
        items += Row("Särskild appåtkomst", { "Appar med särskilda behörigheter" }) {
            a.openSystem(Intent().setClassName("com.android.settings", "com.android.settings.Settings\$SpecialAccessSettingsActivity"),
                i(Settings.ACTION_APPLICATION_SETTINGS))
        }
        items += Category("Avancerat")
        items += Row("Bildformat", { "Helskärmsläge för appar" }) { a.openSystem(i(Settings.ACTION_APPLICATION_SETTINGS)) }
        return SettingsPages.Spec("apps", "Appar", items)
    }

    fun allApps(a: PixelSettingsActivity): SettingsPages.Spec {
        val items = ArrayList<Item>()
        items += Category(null)
        launchable(a).forEach { e -> items += Row(e.label, { appSize(a, e.pkg) }, e.icon) { a.openSystem(details(e.pkg)) } }
        return SettingsPages.Spec("allapps", "Alla appar", items)
    }

    private fun appSize(a: Context, pkg: String): String? = try {
        val ssm = a.getSystemService(StorageStatsManager::class.java)
        val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
        Formatter.formatShortFileSize(a, st.appBytes + st.dataBytes + st.cacheBytes)
    } catch (_: Exception) { null }

    // ---- Aviseringar -----------------------------------------------------------------------------

    private fun secInt(c: Context, k: String, d: Int) = try { Settings.Secure.getInt(c.contentResolver, k, d) } catch (_: Exception) { d }
    private fun secToggle(a: PixelSettingsActivity, key: String, def: Int) = Toggle(
        get = { secInt(a, key, def) != 0 },
        set = { on ->
            val ok = try { Settings.Secure.putInt(a.contentResolver, key, if (on) 1 else 0) } catch (_: Exception) { false }
            if (!ok) a.shell("settings put secure $key ${if (on) 1 else 0}")
        })

    fun notifications(a: PixelSettingsActivity) = SettingsPages.Spec("notifs", "Aviseringar", listOf(
        Category("Hantera"),
        Row("Appaviseringar", { "Styr aviseringar från individuella appar" }) { SettingsPages.open(a, "appnotifs") },
        Row("Aviseringshistorik", { "Visa de senaste aviseringarna och uppskjutna aviseringar" }) {
            a.openSystem(i("android.settings.NOTIFICATION_HISTORY"), i("android.settings.NOTIFICATION_SETTINGS"))
        },
        Category("Konversation"),
        Row("Konversationer", { "Prioriterade och ändrade konversationer" }) {
            a.openSystem(i("android.settings.CONVERSATION_SETTINGS"), i("android.settings.NOTIFICATION_SETTINGS"))
        },
        Row("Bubblor", { "Konversationer kan visas som flytande ikoner" }, toggle = secToggle(a, "notification_bubbles", 1)),
        Category("Integritet"),
        Row("Läsa, svara på och kontrollera aviseringar", { "Styr vilka appar och enheter som kan läsa aviseringar" }) {
            a.openSystem(i(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        Row("Aviseringar på låsskärmen", { lockSummary(a) }) { lockScreen(a) },
        Row("Känsliga aviseringar", { "Visa känsligt innehåll på låsskärmen" }, toggle = secToggle(a, "lock_screen_allow_private_notifications", 1)),
        Category("Allmänt"),
        Row("Tillåt att aviseringar snoozas", null, toggle = secToggle(a, "show_notification_snooze", 0)),
        Row("Aviseringsprick på appikonen", null, toggle = secToggle(a, "notification_badging", 1)),
        Row("Blinkande aviseringar", { "Blinka med kameralampan eller skärmen" }) {
            a.openSystem(i("android.settings.FLASH_NOTIFICATION_SETTINGS"), i(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        Row("Trådlösa varningar vid nödsituationer", null) {
            a.openSystem(i("android.settings.EMERGENCY_ALERT_SETTINGS"), i("android.settings.NOTIFICATION_SETTINGS"))
        },
    ))

    private fun lockSummary(a: Context) = when {
        secInt(a, "lock_screen_show_notifications", 1) == 0 -> "Visa inga aviseringar"
        secInt(a, "lock_screen_show_silent_notifications", 1) == 0 -> "Dölj tysta konversationer och aviseringar"
        else -> "Visa konversationer, standard och tysta"
    }

    private fun lockScreen(a: PixelSettingsActivity) {
        val cur = when {
            secInt(a, "lock_screen_show_notifications", 1) == 0 -> 2
            secInt(a, "lock_screen_show_silent_notifications", 1) == 0 -> 1
            else -> 0
        }
        PDialog.choose(a, "Aviseringar på låsskärmen", listOf(
            "Visa konversationer, standard och tysta", "Dölj tysta konversationer och aviseringar", "Visa inga aviseringar"), cur) { sel ->
            val show = if (sel == 2) 0 else 1; val silent = if (sel == 0) 1 else 0
            a.shell("settings put secure lock_screen_show_notifications $show; settings put secure lock_screen_show_silent_notifications $silent")
        }
    }

    fun appNotifs(a: PixelSettingsActivity): SettingsPages.Spec {
        val pm = a.packageManager
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        val items = ArrayList<Item>()
        items += Category("Senaste")
        val apps = launchable(a).sortedByDescending { it.lastUsed }
        apps.forEach { e ->
            items += Row(e.label, { ago(e.lastUsed) }, e.icon, toggle = Toggle(
                get = { pm.checkPermission(perm, e.pkg) == PackageManager.PERMISSION_GRANTED },
                set = { on ->
                    // Android 13+: an app's notifications are its POST_NOTIFICATIONS grant (what Settings toggles)
                    a.shell(if (on) "pm grant ${e.pkg} $perm; pm set-permission-flags ${e.pkg} $perm user-set"
                            else "pm revoke ${e.pkg} $perm; pm set-permission-flags ${e.pkg} $perm user-set user-fixed")
                })) {
                a.openSystem(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, e.pkg))
            }
        }
        return SettingsPages.Spec("appnotifs", "Appaviseringar", items)
    }

    // ---- Lagring ---------------------------------------------------------------------------------

    fun storage(a: PixelSettingsActivity): SettingsPages.Spec {
        val ssm = a.getSystemService(StorageStatsManager::class.java)
        val uuid = StorageManager.UUID_DEFAULT
        val user = Process.myUserHandle()
        val total = try { ssm.getTotalBytes(uuid) } catch (_: Exception) { 0L }
        val free = try { ssm.getFreeBytes(uuid) } catch (_: Exception) { 0L }
        val ext = try { ssm.queryExternalStatsForUser(uuid, user) } catch (_: Exception) { null }
        val appsBytes = try { ssm.queryStatsForUser(uuid, user).let { it.appBytes + it.dataBytes } } catch (_: Exception) { 0L }
        val images = ext?.imageBytes ?: 0L; val videos = ext?.videoBytes ?: 0L; val audio = ext?.audioBytes ?: 0L
        val other = ((ext?.totalBytes ?: 0L) - images - videos - audio).coerceAtLeast(0L)
        val used = (total - free).coerceAtLeast(0L)
        val system = (used - appsBytes - (ext?.totalBytes ?: 0L)).coerceAtLeast(0L)
        val fmt = { b: Long -> Formatter.formatShortFileSize(a, b) }
        fun item(title: String, bytes: Long, go: () -> Unit) = Custom { act -> storageRow(act, title, fmt(bytes), if (total > 0) bytes.toFloat() / total else 0f, go) }
        val files = { a.openSystem(a.packageManager.getLaunchIntentForPackage("com.google.android.apps.nbu.files")
            ?: a.packageManager.getLaunchIntentForPackage("com.sec.android.app.myfiles") ?: i(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
        return SettingsPages.Spec("storage", "Lagring", listOf(
            Custom { act -> storageHeader(act, fmt(used), fmt(total), if (total > 0) used.toFloat() / total else 0f) },
            Category(null),
            Row("Lagringshanteraren", { "Tar bort säkerhetskopierade foton och videor från enheten" }, toggle = secToggle(a, "automatic_storage_manager_enabled", 0)),
            Row("Frigör utrymme", { "Öppna Files-appen för att hantera och frigöra utrymme" }) { files() },
            Category(null),
            item("Bilder", images) { files() },
            item("Videor", videos) { files() },
            item("Ljud", audio) { files() },
            item("Appar", appsBytes) { SettingsPages.open(a, "allapps") },
            item("Dokument och annat", other) { files() },
            Category("System"),
            item("Android ${android.os.Build.VERSION.RELEASE}", system) { a.openSystem(i(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) },
        ))
    }

    private fun storageHeader(a: PixelSettingsActivity, used: String, total: String, f: Float): View {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dpi(20f), a.dpi(20f), a.dpi(20f), a.dpi(20f)) }
        box.addView(TextView(a).apply { text = used; textSize = 36f; typeface = Fonts.display; setTextColor(a.sc.onSurface) })
        box.addView(TextView(a).apply { text = "använt av $total"; textSize = 14f; setTextColor(a.sc.onVariant) })
        box.addView(bar(a, f, a.dpi(16f)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dpi(16f)).apply { topMargin = a.dpi(16f) })
        return box
    }

    /** StorageItemPreference: title, size on the right, thin progress bar below. */
    private fun storageRow(a: PixelSettingsActivity, title: String, size: String, f: Float, go: () -> Unit): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(a.dpi(16f), a.dpi(16f), a.dpi(16f), a.dpi(16f))
            minimumHeight = a.dpi(72f)
            setOnClickListener { go() }
        }
        val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        line.addView(TextView(a).apply { text = title; textSize = 16f; setTextColor(a.sc.onSurface) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(TextView(a).apply { text = size; textSize = 14f; setTextColor(a.sc.onVariant) })
        box.addView(line)
        box.addView(bar(a, f, a.dpi(6f)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dpi(6f)).apply { topMargin = a.dpi(10f) })
        return box
    }

    private fun bar(a: PixelSettingsActivity, f: Float, h: Int) = object : View(a) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = height / 2f
            p.color = a.sc.highest; c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, p)
            p.color = a.sc.primary
            val w = (width * f.coerceIn(0f, 1f)).coerceAtLeast(if (f > 0f) height.toFloat() else 0f)
            c.drawRoundRect(0f, 0f, w, height.toFloat(), r, r, p)
        }
    }
}
