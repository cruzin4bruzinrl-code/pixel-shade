package se.rob.pixelpanel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * "Pixel-hemskärm": puts Lawnchair (Launcher3, the code base Pixel Launcher is built on) in place of
 * One UI Home and swaps Samsung's own apps for the Google apps a Pixel 10 ships with, so the home
 * screen, app drawer, folders, edit mode and app icons match Pixel.
 */
object PixelHome {
    val lawnchairPkgs = listOf("app.lawnchair", "app.lawnchair.play", "app.lawnchair.nightly")

    fun lawnchair(ctx: Context): String? = lawnchairPkgs.firstOrNull { installed(ctx, it) }
    fun installed(ctx: Context, pkg: String) = try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }

    fun currentHome(ctx: Context): String? = ctx.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName

    /** Pixel 10's own apps, paired with the One UI app each one replaces. */
    class GApp(val title: String, val pkg: String, val replaces: String)
    val googleApps = listOf(
        GApp("Telefon", "com.google.android.dialer", "Samsungs Telefon"),
        GApp("Messages", "com.google.android.apps.messaging", "Samsung Meddelanden"),
        GApp("Kontakter", "com.google.android.contacts", "Samsung Kontakter"),
        GApp("Foton", "com.google.android.apps.photos", "Galleri"),
        GApp("Klocka", "com.google.android.deskclock", "Samsungs Klocka"),
        GApp("Kalender", "com.google.android.calendar", "Samsung Kalender"),
        GApp("Kalkylator", "com.google.android.calculator", "Samsung Kalkylator"),
        GApp("Chrome", "com.android.chrome", "Samsung Internet"),
        GApp("Files", "com.google.android.apps.nbu.files", "Mina filer"),
        GApp("Gboard", "com.google.android.inputmethod.latin", "Samsung Keyboard"),
        GApp("Google Keep", "com.google.android.keep", "Samsung Notes"),
    )

    fun play(ctx: Context, pkg: String) {
        ShadeService.allowSamsungSettings()
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { }
    }

    /** Makes Lawnchair the home app through Shizuku (the HOME role), no system dialog needed. */
    fun makeDefault(ctx: Context, done: (String) -> Unit) {
        val pkg = lawnchair(ctx) ?: return done("Installera Lawnchair först")
        if (!Shell.granted()) { Shell.requestPermission(); return done("Kräver Shizuku") }
        Shell.run("cmd role add-role-holder --user 0 android.app.role.HOME $pkg") { ok ->
            if (ok && currentHome(ctx) == pkg) done("Lawnchair är nu din hemskärm")
            else {
                // Fallback: Android's own chooser for the home app
                try { ctx.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                done("Välj Lawnchair som standardhemskärm")
            }
        }
    }

    fun openLawnchairSettings(ctx: Context) {
        val pkg = lawnchair(ctx) ?: return play(ctx, "app.lawnchair.play")
        val i = Intent().setClassName(pkg, "app.lawnchair.ui.preferences.PreferenceActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Pixel 10 Launcher3 defaults, to set in Lawnchair. */
    val checklist = listOf(
        "Allmänt → Ikonform: Cirkel",
        "Allmänt → Teckensnitt: Google Sans Flex",
        "Allmänt → Färg: Systemfärg (följer Pixel-färgerna)",
        "Hemskärm → Rutnät: 4 × 5",
        "Hemskärm → Vid en blick (At a Glance): På",
        "Docka → 4 ikoner, sökfält i dockan: Google, form Piller",
        "Applåda → Sökfält överst, 4 kolumner, inga etiketter dolda",
        "Mappar → Bakgrund följer tema, 3 kolumner",
        "Dölj Samsungs dubbletter: Applåda → Dolda appar",
    )

    fun summary(ctx: Context): String {
        val l = lawnchair(ctx)
        return when {
            l == null -> "Lawnchair är inte installerat"
            currentHome(ctx) == l -> "Lawnchair används som hemskärm"
            else -> "Lawnchair är installerat men One UI Home används"
        }
    }

    /** Roles the Google apps should hold, like on Pixel. */
    private val roles = mapOf("com.google.android.dialer" to "android.app.role.DIALER", "com.google.android.apps.messaging" to "android.app.role.SMS",
        "com.android.chrome" to "android.app.role.BROWSER")

    fun page(a: PixelSettingsActivity): SettingsPages.Spec {
        val items = ArrayList<Item>()
        items += Category(null)
        items += Row("Hemskärmsapp", { summary(a) }) {
            if (lawnchair(a) == null) play(a, "app.lawnchair.play") else makeDefault(a) { toast(a, it) }
        }
        items += Row("Lawnchair-inställningar", { "Rutnät, ikoner, mappar, applåda" }) { openLawnchairSettings(a) }
        items += Category("Ställ in Lawnchair som Pixel 10")
        checklist.forEach { c -> items += Row(c.substringAfter("→ ").ifBlank { c }, { c.substringBefore(" →") }) { openLawnchairSettings(a) } }
        items += Category("Pixel 10:s appar")
        googleApps.forEach { g ->
            val icon = try { a.packageManager.getApplicationIcon(g.pkg) } catch (_: Exception) { null }
            items += Row(g.title, {
                if (installed(a, g.pkg)) (if (roles.containsKey(g.pkg)) "Installerad · tryck för att göra till standard" else "Installerad")
                else "Ersätter ${g.replaces} · tryck för att installera"
            }, icon) {
                val role = roles[g.pkg]
                when {
                    !installed(a, g.pkg) -> play(a, g.pkg)
                    role != null -> a.shell("cmd role add-role-holder --user 0 $role ${g.pkg}") { toast(a, "${g.title} är nu standard") }
                    else -> a.packageManager.getLaunchIntentForPackage(g.pkg)?.let { a.startActivity(it) }
                }
            }
        }
        items += Footer("Lawnchair bygger på samma kod som Pixel Launcher (Launcher3), så hemskärm, applåda, mappar och redigeringsläge blir som på Pixel. Vyn för senaste appar kan bara bytas med root.")
        return SettingsPages.Spec("home", "Pixel-hemskärm", items)
    }

    fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
}
