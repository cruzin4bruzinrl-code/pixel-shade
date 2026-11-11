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

        // 2. Blur
        card("3 · Oskärpa") {
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
        card("4 · Svep") {
            it.addView(switch("Fånga svepet nedåt", Prefs.captureEnabled(this)) { on ->
                Prefs.setCaptureEnabled(this, on); ShadeService.instance?.updateStrip()
            })
            it.addView(switch("Lämna högra hörnet till Samsung", Prefs.samsungCorner(this)) { on ->
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
        card("5 · Senaste mätning") {
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
    }

    override fun onDestroy() {
        try { windowManager.removeCrossWindowBlurEnabledListener(blurListener) } catch (_: Exception) { }
        super.onDestroy()
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
