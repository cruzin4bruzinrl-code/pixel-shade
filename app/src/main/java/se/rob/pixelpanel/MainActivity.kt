package se.rob.pixelpanel

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.function.Consumer
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var pal: Palette
    private lateinit var list: LinearLayout
    private lateinit var serviceStatus: TextView
    private lateinit var serviceButtons: LinearLayout
    private lateinit var blurStatus: TextView
    private lateinit var notifStatus: TextView
    private lateinit var shizukuStatus: TextView
    private lateinit var bankStatus: TextView
    private val shizukuResult = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refreshShizuku() } }
    private val shizukuBinder = rikka.shizuku.Shizuku.OnBinderReceivedListener { runOnUiThread { refreshShizuku() } }
    private lateinit var statsText: TextView
    private lateinit var updateText: TextView
    private lateinit var updateButton: Button
    private var pending: Updater.Release? = null

    private val blurListener = Consumer<Boolean> { refreshBlur() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        pal = Palette(this)
        window.statusBarColor = pal.surface
        window.navigationBarColor = pal.surface

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(24f), dpi(16f), dpi(32f))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(pal.surface); addView(list) }
        setContentView(scroll)

        list.addView(text("Pixelpanel", 32f, pal.onSurface, bold = false))
        list.addView(text("Version ${BuildConfig.VERSION_NAME}", 14f, pal.onSurfaceVariant).apply {
            setPadding(0, dpi(4f), 0, dpi(16f))
        })

        // Last crash, if any
        CrashLog.read(this)?.let { log ->
            card("Senaste krasch") { c ->
                c.addView(text("Skärmdumpa eller kopiera och skicka till Claude.", 13f, pal.onSurfaceVariant).apply {
                    setPadding(0, 0, 0, dpi(8f))
                })
                c.addView(text(log, 11f, pal.onSurface).apply {
                    setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE
                })
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(10f), 0, 0) }
                row.addView(button("Kopiera", filled = true) {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Pixelpanel krasch", log))
                })
                row.addView(space(dpi(8f)))
                row.addView(button("Rensa", filled = false) { CrashLog.clear(this); recreate() })
                c.addView(row)
            }
        }

        // Gboard: the same app Pixel 10 ships; set it as the keyboard instead of Samsung Keyboard
        card("Tangentbord (Gboard som på Pixel)") { c ->
            val gb = "com.google.android.inputmethod.latin"
            val ime = "$gb/com.android.inputmethod.latin.LatinIME"
            val installed = try { packageManager.getPackageInfo(gb, 0); true } catch (_: Exception) { false }
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
            val active = current.startsWith(gb)
            c.addView(text(when {
                !installed -> "Gboard är inte installerat."
                active -> "Gboard är ditt tangentbord. Välj tema \"Dynamisk färg\" i Gboard så följer det bakgrunden som på Pixel."
                else -> "Samsung Keyboard används nu."
            }, 13f, pal.onSurfaceVariant).apply { setPadding(0, 0, 0, dpi(8f)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (!installed) row.addView(button("Installera Gboard", filled = true) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$gb"))) } catch (_: Exception) { }
            }) else {
                if (!active) row.addView(button("Använd Gboard", filled = true) {
                    if (!Shell.granted()) { Shell.requestPermission(); return@button }
                    Shell.run("ime enable $ime; ime set $ime") { ok -> recreate() }
                })
                if (!active) row.addView(space(dpi(8f)))
                row.addView(button("Gboard-tema", filled = active) {
                    try {
                        startActivity(Intent().setClassName(gb, "com.google.android.apps.inputmethod.latin.preference.SettingsActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        try { startActivity(packageManager.getLaunchIntentForPackage(gb)) } catch (_: Exception) { }
                    }
                })
            }
            c.addView(row)
            if (installed) {
                val status = text("", 13f, pal.onSurface).apply { setPadding(0, dpi(8f), 0, 0) }
                val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(10f), 0, 0) }
                row2.addView(button("Pixel-färger i systemet", filled = true) {
                    if (!Shell.granted()) { Shell.requestPermission(); return@button }
                    status.text = "Skriver Pixel-färgen och kontrollerar…"
                    SystemColors.apply(this) { status.text = it }
                })
                row2.addView(space(dpi(8f)))
                row2.addView(button("Återställ", filled = false) { SystemColors.restore(this) { status.text = it } })
                c.addView(row2)
                c.addView(status)
            }
        }

        // Stress test: drives the phone itself and reports what really showed up on screen
        card("Stresstest") { c ->
            c.addView(text("Telefonen sveper, trycker på volymknapparna och öppnar panelen i olika appar " +
                "av sig själv (ca 1,5 min, rör den inte). Efteråt syns rapporten här – kopiera den till Claude.",
                13f, pal.onSurfaceVariant).apply { setPadding(0, 0, 0, dpi(8f)) })
            val report = try { StressTest.reportFile(this).takeIf { it.exists() }?.readText() } catch (_: Exception) { null }
            report?.let { r ->
                c.addView(text(r, 11f, pal.onSurface).apply {
                    setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(0, 0, 0, dpi(8f))
                })
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(4f), 0, 0) }
            row.addView(button("Kör stresstest", filled = true) { StressTest.start(this) })
            report?.let { r ->
                row.addView(space(dpi(8f)))
                row.addView(button("Kopiera", filled = false) {
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Pixelpanel stresstest", r))
                })
                row.addView(space(dpi(8f)))
                row.addView(button("Dela", filled = false) {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, r), "Dela rapport"))
                })
            }
            c.addView(row)
            c.addView(switch("Pixels volympanel", Prefs.pixelVolume(this)) { on -> Prefs.setPixelVolume(this, on) })
            c.addView(switch("Pixels Inställningar", Prefs.pixelSettings(this)) { on -> Prefs.setPixelSettings(this, on) })
            c.addView(button("Öppna Pixels Inställningar", filled = false) {
                startActivity(Intent(this, PixelSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }

        // 1. Service
        card("1 · Slå på tjänsten") {
            serviceStatus = text("", 15f, pal.onSurface)
            it.addView(serviceStatus)
            it.addView(text(
                "Tryck på Tillgänglighet → Installerade appar → Pixelpanel → På. " +
                    "Är reglaget grått: öppna Appinfo → ⋮ uppe till höger → Tillåt begränsade inställningar, och försök igen.",
                13f, pal.onSurfaceVariant
            ).apply { setPadding(0, dpi(6f), 0, dpi(10f)) })
            serviceButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            serviceButtons.addView(button("Tillgänglighet", filled = true) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            serviceButtons.addView(space(dpi(8f)))
            serviceButtons.addView(button("Appinfo", filled = false) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
            it.addView(serviceButtons)
        }

        // 1b. Notification access + system settings
        card("2 · Notiser och inställningar") {
            notifStatus = text("", 15f, pal.onSurface)
            it.addView(notifStatus)
            it.addView(text(
                "Notisåtkomst låter panelen visa, svara på och rensa dina aviseringar. " +
                    "Ändra systeminställningar behövs för ljusstyrka och autorotering.",
                13f, pal.onSurfaceVariant
            ).apply { setPadding(0, dpi(6f), 0, dpi(10f)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Notisåtkomst", filled = true) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            row.addView(space(dpi(8f)))
            row.addView(button("Systeminställningar", filled = false) {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            })
            it.addView(row)
        }

        // Shizuku: real on/off for Wi-Fi, Bluetooth, data, airplane, battery saver, location, dark theme, NFC
        card("Shizuku · riktiga av/på-knappar") {
            shizukuStatus = text("", 15f, pal.onSurface)
            it.addView(shizukuStatus)
            it.addView(text(
                "Starta Shizuku (öppna appen → Starta via trådlös felsökning) och tryck sedan Ge behörighet. " +
                    "Shizuku måste startas om efter varje omstart av telefonen.",
                13f, pal.onSurfaceVariant
            ).apply { setPadding(0, dpi(6f), 0, dpi(10f)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Ge behörighet", filled = true) { Shell.requestPermission(); refreshShizuku() })
            row.addView(space(dpi(8f)))
            row.addView(button("Öppna Shizuku", filled = false) {
                packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { i -> startActivity(i) }
            })
            it.addView(row)
        }

        // Bank mode
        card("Bankläge") {
            bankStatus = text("", 15f, pal.onSurface)
            it.addView(bankStatus)
            it.addView(text(
                "Bankappar vägrar starta när Pixelpanel är på. Bankläget stänger av Pixelpanel när en vald app öppnas " +
                    "och slår på den igen när du lämnar appen. Shizuku behövs bara en gång för att ge behörigheten.",
                13f, pal.onSurfaceVariant
            ).apply { setPadding(0, dpi(6f), 0, dpi(10f)) })
            it.addView(button("Ge behörighet via Shizuku", filled = true) {
                if (!Shell.granted()) { bankStatus.text = "Starta Shizuku och ge den behörighet först (kortet ovan)."; return@button }
                BankMode.grant(this) { ok -> refreshBank(); if (!ok) bankStatus.text = "Det gick inte – är Shizuku igång?" }
            })
            it.addView(switch("Bankläge på", Prefs.bankModeOn(this)) { on -> Prefs.setBankModeOn(this, on) })
            it.addView(text("Appar som stänger av Pixelpanel:", 14f, pal.onSurface, bold = true).apply {
                setPadding(0, dpi(10f), 0, dpi(4f))
            })
            val cands = BankMode.candidates(this)
            if (!Prefs.hasBankApps(this)) {
                Prefs.setBankApps(this, cands.filter { c -> BankMode.looksLikeBank(c.second) }.map { c -> c.first }.toSet())
            }
            val chosen = Prefs.bankApps(this).toMutableSet()
            cands.forEach { (pkg, label) ->
                it.addView(android.widget.CheckBox(this).apply {
                    text = label; textSize = 15f; setTextColor(pal.onSurface)
                    isChecked = pkg in chosen
                    setOnCheckedChangeListener { _, c ->
                        if (c) chosen += pkg else chosen -= pkg
                        Prefs.setBankApps(this@MainActivity, chosen)
                    }
                })
            }
        }

        // Colours, like Pixel's Wallpaper & style → Colours
        card("Färger") {
            it.addView(text("Som på Pixel: färger från bakgrundsbilden, Samsungs färgpalett eller en grundfärg.", 13f, pal.onSurfaceVariant).apply {
                setPadding(0, 0, 0, dpi(12f))
            })
            val seeds = Prefs.wallSeeds(this)
            val choice = Prefs.wallChoice(this)
            val current = Prefs.seedColor(this)
            // Options: one swatch per wallpaper colour (Pixel's "Wallpaper colours"), Samsung's palette, basic colours.
            val options = ArrayList<Triple<Int, Int, String>>() // (seedColor pref, wallChoice, label) ; swatch colour resolved below
            if (seeds.isEmpty()) options += Triple(0, 0, "Bakgrund")
            else seeds.forEachIndexed { i, _ -> options += Triple(0, i, if (i == 0) "Bakgrund" else "Bakgrund ${i + 1}") }
            options += Triple(1, 0, "Samsung")
            listOf(0xFF1A73E8.toInt() to "Blå", 0xFF00897B.toInt() to "Turkos", 0xFF34A853.toInt() to "Grön",
                0xFFFBBC04.toInt() to "Gul", 0xFFFF6D00.toInt() to "Orange", 0xFFEA4335.toInt() to "Röd",
                0xFFE91E63.toInt() to "Rosa", 0xFF8E24AA.toInt() to "Lila", 0xFF757575.toInt() to "Grå")
                .forEach { (c, n) -> options += Triple(c, 0, n) }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            options.forEach { (color, idx, name) ->
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                val selected = color == current && (color != 0 || seeds.isEmpty() || idx == choice)
                val swatch = View(this).apply {
                    val fill = when (color) {
                        0 -> seeds.getOrNull(idx) ?: WallSeed.get(this@MainActivity).let { w -> if (w == 1) getColor(android.R.color.system_accent1_400) else w }
                        1 -> getColor(android.R.color.system_accent1_400)
                        else -> color
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(fill)
                        if (selected) setStroke(dpi(3f), pal.onSurface)
                    }
                    setOnClickListener {
                        Prefs.setSeedColor(this@MainActivity, color)
                        if (color == 0) {
                            Prefs.setWallChoice(this@MainActivity, idx)
                            seeds.getOrNull(idx)?.let { s0 -> Prefs.setWallSeed(this@MainActivity, s0); WallSeed.cached = s0 }
                        }
                        ShadeService.instance?.rebuildShade()
                        recreate()
                    }
                }
                col.addView(swatch, LinearLayout.LayoutParams(dpi(44f), dpi(44f)))
                col.addView(text(name, 11f, pal.onSurfaceVariant).apply { setPadding(0, dpi(4f), 0, 0) })
                row.addView(col, LinearLayout.LayoutParams(dpi(64f), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            it.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }

        // App icon
        card("Appikon") {
            it.addView(switch("Dölj Pixelpanel i applådan", Prefs.hideIcon(this)) { on ->
                Prefs.setHideIcon(this, on)
                packageManager.setComponentEnabledSetting(
                    android.content.ComponentName(this, "se.rob.pixelpanel.Launcher"),
                    if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            })
            it.addView(text(
                "Inställningarna når du sedan genom att hålla in kugghjulet i panelen. " +
                    "För Lawnchair: välj appen \"Öppna panelen\" som gest för Svep nedåt, så öppnas Pixelpanel i stället för Samsungs panel.",
                13f, pal.onSurfaceVariant
            ).apply { setPadding(0, dpi(6f), 0, 0) })
        }

        // 2. Blur
        card("Oskärpa") {
            blurStatus = text("", 15f, pal.onSurface)
            it.addView(blurStatus)
            val radiusLabel = text("", 13f, pal.onSurfaceVariant).apply { setPadding(0, dpi(12f), 0, 0) }
            fun setLabel(v: Int) { radiusLabel.text = "Styrka: $v dp" }
            setLabel(Prefs.blurRadius(this))
            it.addView(radiusLabel)
            it.addView(SeekBar(this).apply {
                max = 120; progress = Prefs.blurRadius(this@MainActivity)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) { Prefs.setBlurRadius(this@MainActivity, p); setLabel(p) }
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            })
            it.addView(switch("Oskärpan växer med svepet (som Pixel)", Prefs.blurScales(this)) { on ->
                Prefs.setBlurScales(this, on)
            })
        }

        // 3. Swipe
        card("Svep") {
            it.addView(switch("Fånga svepet nedåt", Prefs.captureEnabled(this)) { on ->
                Prefs.setCaptureEnabled(this, on); ShadeService.instance?.updateStrip()
            })
            it.addView(switch("Lämna högra hörnet till Samsungs panel", Prefs.samsungCorner(this)) { on ->
                Prefs.setSamsungCorner(this, on); ShadeService.instance?.updateStrip()
            })
            it.addView(text(
                "Dubbeltryck på statusraden öppnar alltid Samsungs egen panel. På låsskärmen används Samsungs panel.",
                13f, pal.onSurfaceVariant
            ).apply { setPadding(0, dpi(8f), 0, dpi(12f)) })
            it.addView(button("Testa panelen här", filled = true) {
                val s = ShadeService.instance
                if (s == null) serviceStatus.text = "Slå på tjänsten först (steg 1)."
                else s.openShade()
            })
        }

        // 4. Last measurement
        card("Senaste mätning") {
            statsText = text("", 14f, pal.onSurface)
            it.addView(statsText)
            it.addView(text(
                "Dra ner panelen inne i några olika appar. Skärmdumpa siffrorna och skicka till Claude.",
                13f, pal.onSurfaceVariant
            ).apply { setPadding(0, dpi(6f), 0, 0) })
        }

        // 5. Update
        card("Uppdatering") {
            updateText = text("Söker…", 14f, pal.onSurface)
            it.addView(updateText)
            updateButton = button("Uppdatera", filled = true) { doUpdate() }
            updateButton.visibility = View.GONE
            it.addView(updateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpi(10f)
            })
        }

        windowManager.addCrossWindowBlurEnabledListener(mainExecutor, blurListener)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuResult)
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(shizukuBinder)
        } catch (_: Throwable) { }
    }

    private fun refreshBank() {
        bankStatus.text = if (BankMode.ready(this)) "Bankläge redo ✓  Fungerar nu även utan Shizuku."
        else "Behörighet saknas – tryck på knappen nedan med Shizuku igång."
        bankStatus.setTextColor(if (BankMode.ready(this)) pal.primary else pal.onSurface)
    }

    private fun refreshShizuku() {
        shizukuStatus.text = when {
            packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") == null -> "Shizuku är inte installerat."
            !Shell.binderAlive() -> "Shizuku är installerat men inte startat."
            !Shell.granted() -> "Shizuku körs – behörighet saknas."
            else -> "Shizuku: på ✓  Knapparna slår av och på direkt."
        }
        shizukuStatus.setTextColor(if (Shell.granted()) pal.primary else pal.onSurface)
    }

    override fun onDestroy() {
        try { windowManager.removeCrossWindowBlurEnabledListener(blurListener) } catch (_: Exception) { }
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuResult)
            rikka.shizuku.Shizuku.removeBinderReceivedListener(shizukuBinder)
        } catch (_: Throwable) { }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("showReport", false)) recreate()
    }

    override fun onResume() {
        super.onResume()
        val on = ShadeService.instance != null
        serviceStatus.text = if (on) "Tjänsten är på ✓  Svep ner från statusraden." else "Tjänsten är av."
        serviceStatus.setTextColor(if (on) pal.primary else pal.onSurface)
        val nOn = NotifStore.service != null
        val wOn = Settings.System.canWrite(this)
        notifStatus.text = (if (nOn) "Notisåtkomst: på ✓" else "Notisåtkomst: av") + "\n" +
            (if (wOn) "Systeminställningar: på ✓" else "Systeminställningar: av")
        refreshShizuku()
        refreshBank()
        refreshBlur()
        statsText.text = Prefs.lastStats(this) ?: "Ingen mätning än."
        checkUpdate()
    }

    private fun refreshBlur() {
        val enabled = (getSystemService(WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled
        blurStatus.text = if (enabled) "Oskärpa mellan fönster: stöds ✓"
        else "Samsung har stängt av fönsteroskärpan. Pixelpanel suddar i stället en skärmbild av bakgrunden.\n\nVill du prova äkta oskärpa: Inställningar → Hjälpmedel → Synlighetsförbättringar → stäng av \"Minska genomskinlighet och oskärpa\". " +
            "Stäng av energisparläge. Utvecklaralternativ → \"Tillåt oskärpa på fönsternivå\" ska vara på."
    }

    private fun checkUpdate() {
        thread {
            val r = Updater.check()
            runOnUiThread {
                pending = r
                if (r == null) {
                    updateText.text = "Version ${BuildConfig.VERSION_CODE} – senaste."
                    updateButton.visibility = View.GONE
                } else {
                    updateText.text = "Version ${r.version} finns."
                    updateButton.text = "Uppdatera till v${r.version}"
                    updateButton.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun doUpdate() {
        val r = pending ?: return
        updateButton.isEnabled = false
        thread {
            try {
                val f = Updater.download(this, r) { p -> runOnUiThread { updateText.text = "Laddar ner… $p %" } }
                runOnUiThread {
                    updateButton.isEnabled = true
                    if (!Updater.install(this, f)) updateText.text = "Tillåt installation från Pixelpanel och tryck igen."
                    else updateText.text = "Installerar…"
                }
            } catch (e: Exception) {
                runOnUiThread { updateButton.isEnabled = true; updateText.text = "Nedladdning misslyckades: ${e.message}" }
            }
        }
    }

    // ---- small UI helpers --------------------------------------------------------------

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun space(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(w, 1) }

    private fun card(title: String, body: (LinearLayout) -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20f), dpi(18f), dpi(20f), dpi(18f))
            background = GradientDrawable().apply { setColor(pal.surfaceContainer); cornerRadius = dp(28f) }
        }
        c.addView(text(title, 16f, pal.onSurface, bold = true).apply { setPadding(0, 0, 0, dpi(8f)) })
        body(c)
        list.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(12f)
        })
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        setTextColor(if (filled) pal.onPrimary else pal.primary)
        background = GradientDrawable().apply {
            cornerRadius = dp(100f)
            if (filled) setColor(pal.primary) else { setColor(0); setStroke(dpi(1f), pal.primary) }
        }
        stateListAnimator = null
        minHeight = dpi(44f); minimumHeight = dpi(44f)
        setPadding(dpi(20f), 0, dpi(20f), 0)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    @Suppress("DEPRECATION")
    private fun switch(label: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; textSize = 15f; setTextColor(pal.onSurface)
        isChecked = initial
        setPadding(0, dpi(8f), 0, dpi(8f))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }
}
