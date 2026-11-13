package se.rob.pixelpanel

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Automated on-device stress test. Drives the phone like a person would (real swipes and key
 * presses injected through Shizuku's `input`) and after every step checks what actually ended up
 * on screen: our shade or One UI's, our volume dialog or Samsung's, frosted or white backdrop,
 * overlapping parts, colour changes. Writes a report that can be copied and sent back.
 */
object StressTest {
    @Volatile var recolors = 0
    @Volatile var keySeen = 0
    @Volatile var pilfered = 0
    var running = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayList<String>()
    private var fails = 0
    private var warns = 0
    private var started = 0L
    private lateinit var steps: ArrayDeque<(() -> Unit) -> Unit>

    fun reportFile(ctx: Context) = File(ctx.filesDir, "stresstest.txt")

    private fun ok(s: String) { lines += "OK    $s" }
    private fun fail(s: String) { lines += "FEL   $s"; fails++ }
    private fun warn(s: String) { lines += "VARN  $s"; warns++ }
    private fun info(s: String) { lines += "      $s" }

    private fun step(f: (() -> Unit) -> Unit) { steps.addLast(f) }
    private fun next() {
        val s = steps.removeFirstOrNull() ?: return finish()
        main.post {
            try { s { next() } } catch (t: Throwable) { fail("krasch i steg: ${t.javaClass.simpleName}: ${t.message}"); next() }
        }
    }
    private fun pause(ms: Long) = step { done -> main.postDelayed(done, ms) }
    private fun sh(cmd: String) = step { done -> Shell.run(cmd) { done() } }

    fun start(ctx: Context) {
        if (running) return
        val svc = ShadeService.instance
        if (svc == null) { Toast.makeText(ctx, "Tjänsten är inte igång", Toast.LENGTH_LONG).show(); return }
        if (!Shell.granted()) { Toast.makeText(ctx, "Stresstestet behöver Shizuku", Toast.LENGTH_LONG).show(); return }
        running = true; lines.clear(); fails = 0; warns = 0; recolors = 0; pilfered = 0
        started = SystemClock.uptimeMillis()
        steps = ArrayDeque()
        Toast.makeText(ctx, "Stresstest startar – rör inte telefonen (ca 3 min)", Toast.LENGTH_LONG).show()
        plan(svc)
        main.postDelayed({ next() }, 2500)
    }

    private var crashBefore: String? = null

    private fun plan(svc: ShadeService) {
        crashBefore = CrashLog.read(svc)
        val shade = svc.shade
        val bounds = try { svc.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds } catch (_: Exception) { null }
        val w = bounds?.width() ?: svc.resources.displayMetrics.widthPixels
        val h = bounds?.height() ?: svc.resources.displayMetrics.heightPixels
        val d = svc.resources.displayMetrics.density
        val home = "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
        // Opened from our own app so it is a deep link (not the launcher) and stays One UI
        val settings = "am start -n ${svc.packageName}/.MainActivity; sleep 1; am start -a android.settings.WIFI_SETTINGS"
        val settingsHome = "am start -a android.settings.SETTINGS"

        step { done ->
            info("Pixelpanel v${BuildConfig.VERSION_CODE} · ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            info("Skärm ${(w / d).toInt()}×${(h / d).toInt()} dp · oskärpa: ${if (shade.blurSupported) "fönster" else "skärmbild"}")
            if (svc.keyFilterActive()) ok("volymknappar fångas av Pixelpanel")
            else fail("volymknappar fångas inte – slå av och på Pixelpanel under Tillgänglighet")
            done()
        }

        // 1. Pull-down from the status bar, at the left, middle and right, over home and over an app.
        val appCmd = "monkey -p com.google.android.youtube -c android.intent.category.LAUNCHER 1 || monkey -p com.android.vending -c android.intent.category.LAUNCHER 1"
        for ((label, launch) in listOf("hemskärm" to home, "Inställningar" to settings, "en app (YouTube/Play)" to appCmd)) {
            sh(launch); pause(1500)
            for ((xl, xf) in listOf("vänster" to 0.08f, "mitten" to 0.5f, "höger" to 0.92f)) {
                val x = (w * xf).toInt()
                step { done ->
                    val t0 = SystemClock.uptimeMillis()
                    var samsungSeen = false
                    val downs0 = ShadeService.stripDowns
                    val cancels0 = ShadeService.stripCancels
                    var maxProg = 0f
                    Shell.run("input swipe $x 2 $x ${(h * 0.6f).toInt()} 220")
                    poll(2200, 50, {
                        if (svc.samsungShadeVisible()) samsungSeen = true
                        maxProg = maxOf(maxProg, shade.progress)
                        shade.isOpen && shade.progress >= 0.99f
                    }) { opened ->
                        val ms = SystemClock.uptimeMillis() - t0
                        val name = "drag ner $xl över $label"
                        when {
                            !opened -> fail("$name: Pixelpanelen öppnades inte" + (if (samsungSeen) " (One UI-panelen kom i stället)" else "") +
                                " · beröringar på remsan: ${ShadeService.stripDowns - downs0}, avbrutna ${ShadeService.stripCancels - cancels0} · max ${"%.2f".format(maxProg)} · ${svc.stripInfo()}" +
                                " · förgrund ${svc.foreground()} · ${svc.systemWindowTitles()}")
                            samsungSeen -> fail("$name: One UI-panelen syntes samtidigt")
                            else -> ok("$name (${ms} ms)")
                        }
                        val diag = shade.diagnostics()
                        diag.forEach { fail("$name: $it") }
                        done()
                    }
                }
                step { done -> shade.close(); done() }
                pause(800)
                step { done ->
                    if (svc.samsungShadeVisible()) fail("One UI-panelen ligger kvar efter stängning")
                    done()
                }
            }
        }

        // 2. Swipe down in the middle of the home screen (One UI Home opens Samsung's panel there).
        sh(home); pause(1500)
        step { done ->
            var samsungSeen = false
            Shell.run("input swipe ${w / 2} ${(h * 0.3f).toInt()} ${w / 2} ${(h * 0.75f).toInt()} 250")
            poll(2500, 60, { if (svc.samsungShadeVisible()) samsungSeen = true; false }) {
                val name = "svep mitt på hemskärmen"
                when {
                    svc.samsungShadeVisible() -> fail("$name: One UI-panelen ligger kvar")
                    shade.isOpen && samsungSeen -> warn("$name: One UI-panelen blinkade förbi innan Pixelpanelen tog över")
                    shade.isOpen -> ok("$name öppnar Pixelpanelen")
                    else -> ok("$name: ingen panel (svep nedåt är avstängt i One UI Home)")
                }
                done()
            }
        }
        step { done -> shade.close(); done() }
        pause(800)

        // 3. Layout through the whole expansion.
        step { done -> svc.openShade(); done() }
        pause(900)
        step { done -> shade.diagnostics().forEach { fail("hopfällt läge: $it") }; shade.expandTo(0.5f); done() }
        pause(700)
        step { done -> shade.diagnostics().forEach { fail("halvvägs utfällt: $it") }; shade.expandTo(1f); done() }
        pause(900)
        step { done ->
            val diag = shade.diagnostics()
            diag.forEach { fail("utfällt läge: $it") }
            if (diag.isEmpty()) ok("layout hopfällt / halvvägs / utfällt utan överlapp")
            shade.close(); done()
        }
        pause(900)

        // 4. Volume keys. Injected key events may bypass the accessibility key filter, so the real
        // test asks for a physical press and watches what appears on screen.
        sh(home); pause(1200)
        val am = svc.getSystemService(AudioManager::class.java)
        var before = 0
        step { done -> before = am.getStreamVolume(AudioManager.STREAM_MUSIC); done() }
        step { done ->
            val keys = keySeen
            val titles = LinkedHashSet<String>()
            var samsungSeen = false
            Toast.makeText(svc, "Tryck på VOLYM UPP en gång nu", Toast.LENGTH_LONG).show()
            try { svc.getSystemService(android.os.Vibrator::class.java)?.vibrate(
                android.os.VibrationEffect.createOneShot(250, android.os.VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) { }
            poll(9000, 40, {
                if (svc.samsungVolumeShowing()) samsungSeen = true
                titles += svc.systemWindowTitles()
                keySeen != keys && SystemClock.uptimeMillis() > 0 && svc.volume.isShowing
            }) { got ->
                // keep watching a moment to catch a late Samsung dialog
                main.postDelayed({
                    if (svc.samsungVolumeShowing()) samsungSeen = true
                    titles += svc.systemWindowTitles()
                    val after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    when {
                        keySeen == keys && after != before -> fail("fysisk volym upp: gick förbi Pixelpanelen till One UI ($before → $after)")
                        keySeen == keys -> warn("fysisk volym upp: ingen knapptryckning registrerades (tryckte du?)")
                        samsungSeen -> fail("fysisk volym upp: Samsungs volymruta syntes också")
                        !got -> fail("fysisk volym upp: Pixels volympanel visades inte")
                        else -> ok("fysisk volym upp visar Pixels volympanel ($before → $after)")
                    }
                    info("systemfönster under volymtestet: $titles")
                    done()
                }, 700)
            }
        }
        pause(3600)
        step { done ->
            if (svc.volume.isShowing) fail("volympanelen stängdes inte efter 3 s") else ok("volympanelen stängs själv efter 3 s")
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != before) am.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
            done()
        }

        // 5. Rapid open/close: the frosted backdrop must never drop to white.
        sh(settings); pause(1500)
        var white = 0
        repeat(10) {
            step { done -> svc.openShade(); done() }
            pause(500)
            step { done ->
                if (!shade.blurSupported && !shade.hasBackdrop) white++
                shade.close(); done()
            }
            pause(350)
        }
        step { done ->
            if (white > 0) fail("snabb öppna/stäng: helvit bakgrund $white av 10 gånger") else ok("snabb öppna/stäng ×10: alltid frostad bakgrund")
            done()
        }

        // 6. One UI's Settings app opens as Pixel Settings; deep links and Back don't loop.
        sh(home); pause(1200)
        sh(settingsHome); pause(2200)
        step { done ->
            val fg = svc.foreground()
            if (!Prefs.pixelSettings(svc)) info("Pixel-inställningar avstängt – hoppar över")
            else if (fg == svc.packageName) ok("Inställningar öppnas som Pixel-inställningar")
            else fail("Inställningar öppnades som One UI ($fg, ${ShadeService.lastSettingsClass}, föregående app ${ShadeService.lastSettingsPrev}, hem ${svc.homePkg})")
            done()
        }
        sh("input keyevent KEYCODE_BACK"); pause(1800)
        step { done ->
            val fg = svc.foreground()
            if (fg == svc.packageName || fg == "com.android.settings") fail("Tillbaka från Inställningar fastnade ($fg)")
            else ok("Tillbaka från Inställningar går till hemskärmen")
            done()
        }
        sh(settings); pause(2000)
        step { done ->
            val fg = svc.foreground()
            if (fg == "com.android.settings") ok("djuplänk (Wi-Fi) går till One UI-sidan") else warn("djuplänk (Wi-Fi) hamnade i $fg")
            done()
        }
        sh(home); pause(800)

        // 7. Pixel Settings: every page opens and nothing crashes.
        val pages = SettingsPages.allIds + listOf("home", "allapps", "appnotifs")
        for (id in pages) {
            sh("am start -n ${svc.packageName}/.PixelSettingsActivity --es page $id")
            step { done ->
                val t0 = SystemClock.uptimeMillis()
                poll(4000, 80, { PixelSettingsActivity.shownPage == id && PixelSettingsActivity.shownAt >= t0 - 3000 &&
                    svc.foreground() == svc.packageName }) { okd ->
                    if (okd) ok("Inställningar → sidan \"$id\" öppnas") else fail("Inställningar → sidan \"$id\" öppnades inte (visar ${PixelSettingsActivity.shownPage}, förgrund ${svc.foreground()})")
                    done()
                }
            }
        }
        sh(home); pause(1200)

        // 8. Theme picker: the four Pixel styles and basic colours give distinct palettes, shade still lays out.
        var saved = Triple(0, 0, 0)
        step { done ->
            saved = Triple(Prefs.seedColor(svc), Prefs.themeStyle(svc), Prefs.wallChoice(svc))
            val seed = SystemColors.seed(svc) ?: 0xFF1A73E8.toInt()
            if (Prefs.seedColor(svc) == 0 && Prefs.wallSeeds(svc).isEmpty()) warn("temaväljaren: inga bakgrundsfärger inlärda än")
            val prim = (0..3).map { st -> Prefs.setSeedColor(svc, seed); Prefs.setThemeStyle(svc, st); Palette(svc).primary }
            if (prim.toSet().size == 4) ok("temaväljaren: Dynamisk, Neutral, Livfull och Uttrycksfull ger fyra olika färger")
            else fail("temaväljaren: stilarna ger bara ${prim.toSet().size} olika primärfärger")
            val presets = listOf(0xFF1A73E8.toInt(), 0xFF34A853.toInt(), 0xFFEA4335.toInt())
            val off = presets.count { c ->
                Prefs.setSeedColor(svc, c); Prefs.setThemeStyle(svc, 0)
                val a = com.google.android.material.color.utilities.Hct.fromInt(Palette(svc).primary).hue
                val b = com.google.android.material.color.utilities.Hct.fromInt(c).hue
                kotlin.math.abs(a - b).let { kotlin.math.min(it, 360 - it) } > 30
            }
            if (off == 0) ok("temaväljaren: standardfärgerna slår igenom i panelen") else fail("temaväljaren: $off standardfärger slog inte igenom")
            Prefs.setSeedColor(svc, 0xFF34A853.toInt()); Prefs.setThemeStyle(svc, 2)
            svc.rebuildShade(); svc.openShade(); done()
        }
        pause(1000)
        step { done ->
            val d = shade.diagnostics(); d.forEach { fail("panel med ny färg: $it") }
            if (d.isEmpty()) ok("panelen byggs om korrekt med ny färg och stil")
            shade.close(); done()
        }
        pause(700)
        step { done ->
            Prefs.setSeedColor(svc, saved.first); Prefs.setThemeStyle(svc, saved.second); Prefs.setWallChoice(svc, saved.third)
            svc.rebuildShade(); done()
        }

        // 9. Gboard and system colours (Gboard's "Dynamisk färg" uses Android's system palette).
        step { done ->
            val ime = try { android.provider.Settings.Secure.getString(svc.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) } catch (_: Exception) { null } ?: ""
            if (ime.startsWith("com.google.android.inputmethod.latin")) ok("Gboard är tangentbordet") else warn("tangentbordet är inte Gboard ($ime)")
            Shell.output("cmd overlay lookup android android:color/system_accent1_500") { out ->
                val got = out?.let { Regex("#?([0-9a-fA-F]{8})").find(it) }?.groupValues?.get(1)?.toLongOrNull(16)?.toInt()
                val seed = SystemColors.seed(svc)
                if (got == null || seed == null) info("systempaletten kunde inte läsas (${out?.take(80)})")
                else {
                    val a = com.google.android.material.color.utilities.Hct.fromInt(got).hue
                    val b = com.google.android.material.color.utilities.Hct.fromInt(seed).hue
                    val dd = kotlin.math.abs(a - b).let { kotlin.math.min(it, 360 - it) }
                    when {
                        dd < 25 -> ok("systempaletten följer Pixel-färgen – Gboard får samma färger som panelen")
                        Prefs.systemColors(svc) -> fail("One UI skrev över Pixel-färgen i systemet (system ${a.toInt()}°, Pixel ${b.toInt()}°)")
                        else -> warn("Gboard använder Samsungs palett (system ${a.toInt()}°, Pixel ${b.toInt()}°) – slå på \"Använd färgerna i hela systemet\" i Bakgrund och stil")
                    }
                }
                done()
            }
        }

        // 10. Pixel home: Lawnchair and the Google apps.
        step { done ->
            val l = PixelHome.lawnchair(svc)
            when {
                l == null -> warn("Lawnchair är inte installerat")
                PixelHome.currentHome(svc) == l -> ok("Lawnchair är hemskärmen")
                else -> warn("Lawnchair är installerat men ${PixelHome.currentHome(svc)} är hemskärm")
            }
            val missing = PixelHome.googleApps.filter { !PixelHome.installed(svc, it.pkg) }.map { it.title }
            if (missing.isEmpty()) ok("alla Pixel 10-appar är installerade") else warn("Pixel 10-appar som saknas: ${missing.joinToString()}")
            done()
        }
        for ((role, pkg, name) in listOf(Triple("DIALER", "com.google.android.dialer", "Telefon"),
                Triple("SMS", "com.google.android.apps.messaging", "Messages"), Triple("BROWSER", "com.android.chrome", "Chrome"))) {
            step { done ->
                Shell.output("cmd role get-role-holders --user 0 android.app.role.$role") { out ->
                    if (out?.contains(pkg) == true) ok("Googles $name är standardapp") else warn("standardapp för ${role.lowercase()}: ${out?.trim()?.ifBlank { "ingen" }} (Pixel: $name)")
                    done()
                }
            }
        }

        // 11. Rotation: landscape shade must still show its tiles and stay a phone-width column.
        var rot = Pair(1, 0)
        step { done ->
            rot = Pair(try { android.provider.Settings.System.getInt(svc.contentResolver, "accelerometer_rotation") } catch (_: Exception) { 1 },
                try { android.provider.Settings.System.getInt(svc.contentResolver, "user_rotation") } catch (_: Exception) { 0 })
            done()
        }
        sh("settings put system accelerometer_rotation 0; settings put system user_rotation 1"); pause(2000)
        step { done -> svc.openShade(); done() }
        pause(1000)
        step { done ->
            val d = shade.diagnostics(); d.forEach { fail("liggande läge: $it") }
            if (d.isEmpty()) ok("liggande läge: panelen med rutor ser rätt ut")
            shade.close(); done()
        }
        pause(600)
        step { done -> Shell.run("settings put system user_rotation ${rot.second}; settings put system accelerometer_rotation ${rot.first}") { done() } }
        pause(2000)
        step { done -> svc.openShade(); done() }
        pause(1000)
        step { done ->
            val d = shade.diagnostics(); d.forEach { fail("tillbaka i stående: $it") }
            if (d.isEmpty()) ok("tillbaka i stående: rutorna syns")
            shade.close(); done()
        }
        pause(600)

        // 12. Crashes during the whole run.
        step { done ->
            val now = CrashLog.read(svc)
            if (now != null && now != crashBefore) fail("Pixelpanel kraschade under testet: " + now.lineSequence().take(2).joinToString(" "))
            else ok("inga krascher under testet")
            done()
        }

        // 7. Colours must not have changed during the whole run.
        step { done ->
            Shell.output("dumpsys wallpaper | grep -i -m6 'color'") { out -> if (!out.isNullOrBlank()) info("dumpsys wallpaper: " + out.replace("\n", " | ").take(600)) }
            if (pilfered > 0) info("One UI tog över draget $pilfered gång(er) – Pixelpanelen tog tillbaka det")
            if (recolors > 0) warn("färgerna byttes $recolors gång(er) under testet (ny bakgrund sedan förra gången?)") else ok("färgerna stabila under hela testet")
            done()
        }
        sh(home)
    }

    /** Polls [cond] every [every] ms for up to [max] ms, then calls [done] with the last result. */
    private fun poll(max: Long, every: Long, cond: () -> Boolean, done: (Boolean) -> Unit) {
        val end = SystemClock.uptimeMillis() + max
        fun tick() {
            val r = try { cond() } catch (_: Exception) { false }
            if (r || SystemClock.uptimeMillis() >= end) done(r) else main.postDelayed({ tick() }, every)
        }
        tick()
    }

    private fun finish() {
        running = false
        val svc = ShadeService.instance ?: return
        val secs = (SystemClock.uptimeMillis() - started) / 1000
        val head = "Pixelpanel stresstest ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())}\n" +
            "Resultat: ${if (fails == 0) "GODKÄNT" else "$fails FEL"}" + (if (warns > 0) ", $warns varningar" else "") + " · ${secs} s\n\n"
        val text = head + lines.joinToString("\n") + "\n\nFönster just nu: " + svc.allWindowTitles() + "\n"
        try { reportFile(svc).writeText(text) } catch (_: Exception) { }
        svc.startActivity(Intent(svc, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("showReport", true))
    }
}
