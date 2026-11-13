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
    private fun wait(ms: Long) = step { done -> main.postDelayed(done, ms) }
    private fun sh(cmd: String) = step { done -> Shell.run(cmd) { done() } }

    fun start(ctx: Context) {
        if (running) return
        val svc = ShadeService.instance
        if (svc == null) { Toast.makeText(ctx, "Tjänsten är inte igång", Toast.LENGTH_LONG).show(); return }
        if (!Shell.granted()) { Toast.makeText(ctx, "Stresstestet behöver Shizuku", Toast.LENGTH_LONG).show(); return }
        running = true; lines.clear(); fails = 0; warns = 0; recolors = 0
        started = SystemClock.uptimeMillis()
        steps = ArrayDeque()
        Toast.makeText(ctx, "Stresstest startar – rör inte telefonen (ca 1,5 min)", Toast.LENGTH_LONG).show()
        plan(svc)
        main.postDelayed({ next() }, 2500)
    }

    private fun plan(svc: ShadeService) {
        val shade = svc.shade
        val bounds = try { svc.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds } catch (_: Exception) { null }
        val w = bounds?.width() ?: svc.resources.displayMetrics.widthPixels
        val h = bounds?.height() ?: svc.resources.displayMetrics.heightPixels
        val d = svc.resources.displayMetrics.density
        val home = "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
        val settings = "am start -a android.settings.SETTINGS"

        step { done ->
            info("Pixelpanel v${BuildConfig.VERSION_CODE} · ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            info("Skärm ${(w / d).toInt()}×${(h / d).toInt()} dp · oskärpa: ${if (shade.blurSupported) "fönster" else "skärmbild"}")
            if (svc.keyFilterActive()) ok("volymknappar fångas av Pixelpanel")
            else fail("volymknappar fångas inte – slå av och på Pixelpanel under Tillgänglighet")
            done()
        }

        // 1. Pull-down from the status bar, at the left, middle and right, over home and over an app.
        for ((label, launch) in listOf("hemskärm" to home, "Inställningar" to settings)) {
            sh(launch); wait(1500)
            for ((xl, xf) in listOf("vänster" to 0.08f, "mitten" to 0.5f, "höger" to 0.92f)) {
                val x = (w * xf).toInt()
                step { done ->
                    val t0 = SystemClock.uptimeMillis()
                    var samsungSeen = false
                    Shell.run("input swipe $x 2 $x ${(h * 0.6f).toInt()} 220")
                    poll(2200, 50, {
                        if (svc.samsungShadeVisible()) samsungSeen = true
                        shade.isOpen && shade.progress >= 0.99f
                    }) { opened ->
                        val ms = SystemClock.uptimeMillis() - t0
                        val name = "drag ner $xl över $label"
                        when {
                            !opened -> fail("$name: Pixelpanelen öppnades inte" + if (samsungSeen) " (One UI-panelen kom i stället)" else "")
                            samsungSeen -> fail("$name: One UI-panelen syntes samtidigt")
                            else -> ok("$name (${ms} ms)")
                        }
                        val diag = shade.diagnostics()
                        diag.forEach { fail("$name: $it") }
                        done()
                    }
                }
                step { done -> shade.close(); done() }
                wait(800)
                step { done ->
                    if (svc.samsungShadeVisible()) fail("One UI-panelen ligger kvar efter stängning")
                    done()
                }
            }
        }

        // 2. Swipe down in the middle of the home screen (One UI Home opens Samsung's panel there).
        sh(home); wait(1500)
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
        wait(800)

        // 3. Layout through the whole expansion.
        step { done -> svc.openShade(); done() }
        wait(900)
        step { done -> shade.diagnostics().forEach { fail("hopfällt läge: $it") }; shade.expandTo(0.5f); done() }
        wait(700)
        step { done -> shade.diagnostics().forEach { fail("halvvägs utfällt: $it") }; shade.expandTo(1f); done() }
        wait(900)
        step { done ->
            val diag = shade.diagnostics()
            diag.forEach { fail("utfällt läge: $it") }
            if (diag.isEmpty()) ok("layout hopfällt / halvvägs / utfällt utan överlapp")
            shade.close(); done()
        }
        wait(900)

        // 4. Volume keys.
        sh(home); wait(1200)
        val am = svc.getSystemService(AudioManager::class.java)
        var before = 0; var keys = 0
        step { done -> before = am.getStreamVolume(AudioManager.STREAM_MUSIC); keys = keySeen; done() }
        sh("input keyevent KEYCODE_VOLUME_UP")
        step { done ->
            var samsungSeen = false
            poll(600, 40, { if (svc.samsungVolumeShowing()) samsungSeen = true; false }) {
                val after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                when {
                    keySeen == keys -> fail("volym upp: knappen nådde inte Pixelpanelen")
                    samsungSeen -> fail("volym upp: Samsungs volymruta syntes")
                    !svc.volume.isShowing -> fail("volym upp: Pixels volympanel visades inte")
                    after != before + 1 && before < max -> fail("volym upp: mediavolymen ändrades inte ($before → $after)")
                    else -> ok("volym upp visar Pixels volympanel ($before → $after av $max)")
                }
                done()
            }
        }
        sh("input keyevent KEYCODE_VOLUME_DOWN")
        wait(400)
        step { done ->
            val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (now != before) fail("volym ner: tillbaka till $now, väntat $before") else ok("volym ner återställer ($now)")
            done()
        }
        step { done ->
            var samsungSeen = false
            val cmd = (1..6).joinToString("; ") { "input keyevent KEYCODE_VOLUME_${if (it % 2 == 0) "DOWN" else "UP"}" }
            Shell.run(cmd)
            poll(1800, 40, { if (svc.samsungVolumeShowing()) samsungSeen = true; false }) {
                if (samsungSeen) fail("snabba volymtryck: Samsungs volymruta syntes") else ok("6 snabba volymtryck utan Samsungs ruta")
                done()
            }
        }
        wait(3600)
        step { done ->
            if (svc.volume.isShowing) fail("volympanelen stängdes inte efter 3 s") else ok("volympanelen stängs själv efter 3 s")
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != before) am.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
            done()
        }

        // 5. Rapid open/close: the frosted backdrop must never drop to white.
        sh(settings); wait(1500)
        var white = 0
        repeat(10) {
            step { done -> svc.openShade(); done() }
            wait(500)
            step { done ->
                if (!shade.blurSupported && !shade.hasBackdrop) white++
                shade.close(); done()
            }
            wait(350)
        }
        step { done ->
            if (white > 0) fail("snabb öppna/stäng: helvit bakgrund $white av 10 gånger") else ok("snabb öppna/stäng ×10: alltid frostad bakgrund")
            done()
        }

        // 6. Colours must not have changed during the whole run.
        step { done ->
            if (recolors > 0) fail("färgerna byttes $recolors gång(er) under testet") else ok("färgerna stabila under hela testet")
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
