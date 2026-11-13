package se.rob.pixelpanel

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.color.utilities.Hct

/**
 * Pixel 10 Settings (Android 16 QPR1, Material 3 Expressive), rebuilt from AOSP
 * packages/apps/Settings (top_level_settings_expressive.xml, homepage_preference_expressive,
 * search_bar_unified_version) and frameworks/base/packages/SettingsLib (SettingsTheme v36:
 * grouped rows on surfaceBright with 20/4 dp corners, 72 dp rows, 40 dp icon frame,
 * 16 sp titles / 14 sp summaries, primary category titles; CollapsingToolbar: 36 sp title
 * collapsing to 22 sp, back button in a 40 dp surfaceContainerHighest circle).
 */
class PixelSettingsActivity : Activity() {

    lateinit var pal: Palette
    lateinit var sc: SColors
    private lateinit var host: FrameLayout
    private val stack = ArrayList<View>()
    val main = Handler(Looper.getMainLooper())
    private val refreshers = ArrayList<() -> Unit>()
    var topInset = 0
    var bottomInset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        actionBar?.hide()
        pal = Palette(this); sc = SColors(this, pal)
        window.statusBarColor = 0; window.navigationBarColor = 0
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.setSystemBarsAppearance(
            if (pal.night) 0 else (android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS),
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        host = FrameLayout(this).apply { setBackgroundColor(sc.bg) }
        host.setOnApplyWindowInsetsListener { _, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars())
            if (bars.top != topInset || bars.bottom != bottomInset) {
                topInset = bars.top; bottomInset = bars.bottom
                stack.forEach { (it.tag as? PageView)?.applyInsets() }
            }
            ins
        }
        setContentView(host)
        val start = intent.getStringExtra("page")
        push(SettingsPages.home(this), animate = false)
        start?.let { id -> SettingsPages.byId(this, id)?.let { push(it, animate = false) } }
    }

    override fun onResume() {
        super.onResume()
        refreshers.toList().forEach { try { it() } catch (_: Exception) { } }
    }

    fun onRefresh(f: () -> Unit) { refreshers += f }

    // ---- navigation (SettingsTransition: shared axis X) -------------------------------------

    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun push(page: PageView, animate: Boolean = true) {
        val v = page.root
        v.tag = page
        val prev = stack.lastOrNull()
        stack += v
        host.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        page.applyInsets()
        if (!animate || prev == null) return
        val dx = dp(96f)
        v.translationX = dx; v.alpha = 0f
        v.animate().translationX(0f).alpha(1f).setDuration(450).setInterpolator(emphasized).start()
        prev.animate().translationX(-dx).alpha(0f).setDuration(450).setInterpolator(emphasized)
            .withEndAction { prev.visibility = View.INVISIBLE }.start()
    }

    fun pop() {
        if (stack.size <= 1) { leave(); return }
        val top = stack.removeAt(stack.size - 1)
        val prev = stack.last()
        val dx = dp(96f)
        prev.visibility = View.VISIBLE
        prev.translationX = -dx; prev.alpha = 0f
        prev.animate().translationX(0f).alpha(1f).setDuration(400).setInterpolator(emphasized).start()
        top.animate().translationX(dx).alpha(0f).setDuration(400).setInterpolator(emphasized)
            .withEndAction { host.removeView(top) }.start()
        refreshers.toList().forEach { try { it() } catch (_: Exception) { } }
    }

    /** Leaving our root: go home instead of falling back into One UI's Settings. */
    private fun leave() {
        finishAndRemoveTask()
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { pop() }

    /** Opens one of One UI's own pages (allowed through without being redirected). */
    fun openSystem(vararg intents: Intent) {
        ShadeService.allowSamsungSettings()
        for (i in intents) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
        }
        Toast.makeText(this, "Sidan finns inte på den här telefonen", Toast.LENGTH_SHORT).show()
    }

    fun shell(cmd: String, after: (() -> Unit)? = null) {
        if (!Shell.granted()) { Toast.makeText(this, "Kräver Shizuku", Toast.LENGTH_SHORT).show(); Shell.requestPermission(); return }
        Shell.run(cmd) { ok ->
            if (!ok) Toast.makeText(this, "Det gick inte att ändra", Toast.LENGTH_SHORT).show()
            main.postDelayed({ after?.invoke() }, 250)
        }
    }

    private var ringType = 0
    fun pickRingtone(type: Int) {
        ringType = type
        val i = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, type)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, android.media.RingtoneManager.getActualDefaultRingtoneUri(this, type))
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
        ShadeService.allowSamsungSettings()
        try { startActivityForResult(i, 42) } catch (_: Exception) { }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK && data != null) {
            val uri = data.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI, android.net.Uri::class.java)
            try { android.media.RingtoneManager.setActualDefaultRingtoneUri(this, ringType, uri) } catch (_: Exception) { }
        }
    }

    fun dp(v: Float) = (this as Context).dp(v)
}

/** Expressive SettingsLib colour roles derived from the shade palette. */
class SColors(ctx: Context, private val pal: Palette) {
    private fun n(l: Double) = Hct.fromInt(pal.surface).let { Hct.from(it.hue, it.chroma.coerceAtMost(6.0), l).toInt() }
    val night = pal.night
    val bg = n(if (night) 12.0 else 94.0)            // surfaceContainer (window background)
    val row = n(if (night) 24.0 else 98.0)           // surfaceBright (preference rows)
    val pressed = n(if (night) 17.0 else 92.0)       // surfaceContainerHigh
    val highest = pal.surfaceContainerHighest
    val search = n(if (night) 24.0 else 98.0)
    val onSurface = pal.onSurface
    val onVariant = pal.onSurfaceVariant
    val primary = pal.primary
    val onPrimary = pal.onPrimary
    val primaryContainer = pal.primaryContainer
    val onPrimaryContainer = if (night) Hct.fromInt(pal.primary).let { Hct.from(it.hue, it.chroma, 90.0).toInt() }
        else Hct.fromInt(pal.primary).let { Hct.from(it.hue, it.chroma, 10.0).toInt() }
    val outline = n(if (night) 60.0 else 50.0)
    val dialog = n(if (night) 17.0 else 92.0)
}

// ---- items ----------------------------------------------------------------------------------

sealed class Item
class Category(val title: String?) : Item()
class Footer(val text: String) : Item()
class Custom(val make: (PixelSettingsActivity) -> View) : Item()
class Row(
    val title: String,
    val summary: (() -> String?)? = null,
    val icon: Drawable? = null,
    val iconBg: Int? = null,
    val homepage: Boolean = false,
    val toggle: Toggle? = null,
    val onClick: (() -> Unit)? = null,
) : Item()
class Toggle(val get: () -> Boolean, val set: (Boolean) -> Unit)
class Slider(val title: String, val icon: Drawable?, val min: Int, val max: Int, val get: () -> Int, val set: (Int) -> Unit) : Item()
class MainSwitch(val title: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : Item()

/** One settings page: collapsing large title over grouped expressive rows. */
class PageView(val a: PixelSettingsActivity, val title: String?, val items: List<Item>, val isHome: Boolean = false) {
    val root = FrameLayout(a)
    private val scroll = ScrollView(a)
    private val content = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val bar = FrameLayout(a)
    private val barTitle = TextView(a)
    private val bigTitle = TextView(a)
    private val sc = a.sc
    private val r20 = a.dp(20f); private val r4 = a.dp(4f)

    init {
        root.setBackgroundColor(sc.bg)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scroll.clipToPadding = false
        scroll.addView(content)
        root.addView(scroll)
        if (!isHome) {
            // Large title (36 sp display-small emphasized), expanded toolbar 179 dp tall
            bigTitle.apply {
                text = title; textSize = 36f; typeface = Fonts.display; setTextColor(sc.onSurface)
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(a.dpi(24f), 0, a.dpi(24f), a.dpi(20f))
                gravity = Gravity.BOTTOM
            }
            content.addView(bigTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dpi(179f - 64f)))
            // Collapsed toolbar: back button + 22 sp title that fades in
            bar.setBackgroundColor(sc.bg)
            val back = ImageView(a).apply {
                setImageDrawable(backIcon())
                scaleType = ImageView.ScaleType.CENTER
                background = RippleDrawable(android.content.res.ColorStateList.valueOf(sc.onSurface.withAlpha(30)), null,
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(-1) })
                setOnClickListener { a.pop() }
                contentDescription = "Tillbaka"
            }
            bar.addView(back, FrameLayout.LayoutParams(a.dpi(48f), a.dpi(48f), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = a.dpi(12f)
            })
            barTitle.apply {
                text = title; textSize = 22f; typeface = Fonts.medium; setTextColor(sc.onSurface)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; alpha = 0f
            }
            bar.addView(barTitle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL).apply { leftMargin = a.dpi(72f); rightMargin = a.dpi(16f) })
            root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dpi(64f)))
            scroll.setOnScrollChangeListener { _, _, y, _, _ ->
                val collapse = bigTitle.height - a.dp(40f)
                val f = ((y - collapse * 0.55f) / (collapse * 0.45f)).coerceIn(0f, 1f)
                barTitle.alpha = f
                bigTitle.alpha = 1f - f
            }
        }
        build()
    }

    private fun backIcon(): Drawable {
        // settingslib_expressive_icon_back: 40 dp surfaceContainerHighest circle with a 16 dp arrow
        val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(sc.highest); setSize(a.dpi(40f), a.dpi(40f)) }
        val arrow = a.getDrawable(R.drawable.pset_back)!!.mutate().apply { setTint(sc.onVariant) }
        return LayerDrawable(arrayOf(circle, arrow)).apply {
            setLayerSize(1, a.dpi(16f), a.dpi(16f)); setLayerGravity(1, Gravity.CENTER)
        }
    }

    fun applyInsets() {
        (bar.layoutParams as? FrameLayout.LayoutParams)?.let { it.height = a.dpi(64f) + a.topInset; bar.layoutParams = it }
        bar.setPadding(0, a.topInset, 0, 0)
        content.setPadding(0, if (isHome) a.topInset else a.dpi(64f) + a.topInset, 0, a.bottomInset + a.dpi(24f))
    }

    private fun build() {
        if (isHome) content.addView(searchBar())
        // Split into groups: a Category starts a new group; rows inside a group share rounded corners.
        var group = ArrayList<Item>()
        fun flush() {
            if (group.isEmpty()) return
            val rows = group.toList(); group = ArrayList()
            rows.forEachIndexed { i, it ->
                val first = i == 0; val last = i == rows.size - 1
                val v = makeRow(it, first, last)
                content.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = a.dpi(16f); rightMargin = a.dpi(16f)
                    topMargin = if (first) 0 else a.dpi(2f)
                })
            }
        }
        var firstGroup = true
        for (it in items) {
            when (it) {
                is Category -> {
                    flush()
                    if (it.title != null) {
                        content.addView(TextView(a).apply {
                            text = it.title; textSize = 14f; typeface = Fonts.medium; setTextColor(sc.primary)
                            setPadding(a.dpi(32f), a.dpi(if (firstGroup) 8f else 24f), a.dpi(32f), a.dpi(8f))
                        })
                    } else if (!firstGroup) content.addView(View(a), LinearLayout.LayoutParams(1, a.dpi(16f)))
                    firstGroup = false
                }
                is Footer -> {
                    flush()
                    content.addView(LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(a.dpi(32f), a.dpi(24f), a.dpi(32f), a.dpi(8f))
                        addView(ImageView(a).apply {
                            setImageDrawable(a.getDrawable(R.drawable.pset_info)?.mutate()?.apply { setTint(sc.onVariant) })
                        }, LinearLayout.LayoutParams(a.dpi(24f), a.dpi(24f)))
                        addView(TextView(a).apply {
                            text = it.text; textSize = 14f; typeface = Fonts.regular; setTextColor(sc.onVariant)
                            setPadding(0, a.dpi(16f), 0, 0); setLineSpacing(a.dp(4f), 1f)
                        })
                    })
                }
                is MainSwitch -> { flush(); content.addView(mainSwitch(it)); firstGroup = false }
                else -> { if (firstGroup && group.isEmpty() && !isHome) Unit; group += it; firstGroup = false }
            }
        }
        flush()
    }

    private fun bgFor(first: Boolean, last: Boolean): Drawable {
        val t = if (first) r20 else r4; val b = if (last) r20 else r4
        val shape = GradientDrawable().apply { setColor(sc.row); cornerRadii = floatArrayOf(t, t, t, t, b, b, b, b) }
        val mask = GradientDrawable().apply { setColor(-1); cornerRadii = floatArrayOf(t, t, t, t, b, b, b, b) }
        return RippleDrawable(android.content.res.ColorStateList.valueOf(sc.onSurface.withAlpha(28)), shape, mask)
    }

    private fun makeRow(item: Item, first: Boolean, last: Boolean): View = when (item) {
        is Row -> rowView(item, first, last)
        is Slider -> sliderRow(item, first, last)
        is Custom -> item.make(a).apply { background = bgFor(first, last) }
        else -> View(a)
    }

    private fun rowView(r: Row, first: Boolean, last: Boolean): View {
        val v = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dpi(if (r.homepage) 72f else 72f)
            setPadding(a.dpi(16f), a.dpi(16f), a.dpi(16f), a.dpi(16f))
            background = bgFor(first, last)
        }
        r.icon?.let { ic ->
            val iv = ImageView(a).apply {
                scaleType = ImageView.ScaleType.CENTER
                if (r.iconBg != null) {
                    // AdaptiveIconShapeDrawable (circle) with a 24 dp foreground inset 8 dp
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(r.iconBg) }
                }
                setImageDrawable(ic)
            }
            v.addView(iv, LinearLayout.LayoutParams(a.dpi(40f), a.dpi(40f)).apply { marginEnd = a.dpi(16f) })
        }
        val textCol = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(a).apply {
            text = r.title; textSize = 16f; typeface = Fonts.regular; setTextColor(sc.onSurface)
            setLineSpacing(0f, 1.1f)
        }
        textCol.addView(title)
        val summary = TextView(a).apply {
            textSize = 14f; typeface = Fonts.regular; setTextColor(sc.onVariant); maxLines = 4
            setPadding(0, a.dpi(2f), 0, 0)
        }
        textCol.addView(summary)
        v.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var sw: M3Switch? = null
        r.toggle?.let { t ->
            if (r.onClick != null) {
                // PrimarySwitchPreference: divider then switch
                v.addView(View(a).apply { setBackgroundColor(sc.outline.withAlpha(90)) },
                    LinearLayout.LayoutParams(a.dpi(1f), a.dpi(32f)).apply { marginStart = a.dpi(12f); marginEnd = a.dpi(16f) })
            } else v.addView(View(a), LinearLayout.LayoutParams(a.dpi(16f), 1))
            sw = M3Switch(a, sc).also { s ->
                s.setChecked(t.get(), false)
                s.onUser = { on -> t.set(on) }
                v.addView(s, LinearLayout.LayoutParams(a.dpi(52f), a.dpi(32f)))
            }
        }
        val refresh = {
            val s = r.summary?.invoke()
            summary.text = s ?: ""
            summary.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            sw?.setChecked(r.toggle!!.get(), true)
            Unit
        }
        refresh()
        a.onRefresh(refresh)
        v.setOnClickListener {
            when {
                r.onClick != null -> r.onClick.invoke()
                sw != null -> sw!!.toggleByUser()
            }
            a.main.postDelayed({ refresh() }, 400)
        }
        return v
    }

    private fun sliderRow(s: Slider, first: Boolean, last: Boolean): View {
        val v = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dpi(16f), a.dpi(14f), a.dpi(16f), a.dpi(10f))
            background = GradientDrawable().apply {
                setColor(sc.row)
                val t = if (first) r20 else r4; val b = if (last) r20 else r4
                cornerRadii = floatArrayOf(t, t, t, t, b, b, b, b)
            }
        }
        v.addView(TextView(a).apply {
            text = s.title; textSize = 16f; typeface = Fonts.regular; setTextColor(sc.onSurface)
            setPadding(if (s.icon != null) a.dpi(56f) else 0, 0, 0, 0)
        })
        val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        s.icon?.let { ic ->
            line.addView(ImageView(a).apply { setImageDrawable(ic.mutate().apply { setTint(sc.onVariant) }); scaleType = ImageView.ScaleType.CENTER },
                LinearLayout.LayoutParams(a.dpi(40f), a.dpi(40f)).apply { marginEnd = a.dpi(16f) })
        }
        val bar = M3Slider(a, sc, s.min, s.max).apply {
            value = s.get().toFloat()
            onUser = { s.set(it) }
        }
        line.addView(bar, LinearLayout.LayoutParams(0, a.dpi(44f), 1f))
        v.addView(line)
        a.onRefresh { if (!bar.dragging) { bar.value = s.get().toFloat(); bar.invalidate() } }
        return v
    }

    private fun mainSwitch(m: MainSwitch): View {
        // SettingsLib MainSwitchBar (expressive): large rounded bar, primaryContainer when on
        val sw = M3Switch(a, sc)
        val title = TextView(a).apply { text = m.title; textSize = 20f; typeface = Fonts.medium }
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dpi(24f), a.dpi(20f), a.dpi(20f), a.dpi(20f))
            minimumHeight = a.dpi(80f)
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(sw, LinearLayout.LayoutParams(a.dpi(52f), a.dpi(32f)))
        fun paint(on: Boolean) {
            bar.background = GradientDrawable().apply { cornerRadius = a.dp(28f); setColor(if (on) sc.primaryContainer else sc.highest) }
            title.setTextColor(if (on) sc.onPrimaryContainer else sc.onSurface)
        }
        val refresh = { val on = m.get(); sw.setChecked(on, true); paint(on) }
        sw.setChecked(m.get(), false); paint(m.get())
        sw.onUser = { on -> m.set(on); paint(on); a.main.postDelayed({ refresh() }, 600) }
        bar.setOnClickListener { sw.toggleByUser() }
        a.onRefresh(refresh)
        return FrameLayout(a).apply {
            setPadding(a.dpi(16f), a.dpi(8f), a.dpi(16f), a.dpi(16f))
            addView(bar)
        }
    }

    private fun searchBar(): View {
        // search_bar_unified_version: 72 dp, radius 40, 24 dp padding, search icon + "Sök i inställningar"
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = a.dpi(72f)
            setPadding(a.dpi(24f), 0, a.dpi(24f), 0)
            background = RippleDrawable(android.content.res.ColorStateList.valueOf(sc.onSurface.withAlpha(28)),
                GradientDrawable().apply { cornerRadius = a.dp(40f); setColor(sc.search) },
                GradientDrawable().apply { cornerRadius = a.dp(40f); setColor(-1) })
            setOnClickListener { SettingsPages.openSearch(a) }
        }
        bar.addView(ImageView(a).apply {
            setImageDrawable(a.getDrawable(R.drawable.pset_search)?.mutate()?.apply { setTint(sc.onSurface) })
        }, LinearLayout.LayoutParams(a.dpi(24f), a.dpi(24f)))
        bar.addView(TextView(a).apply {
            text = "Sök i inställningar"; textSize = 16f; typeface = Fonts.regular; setTextColor(sc.onVariant)
            setPadding(a.dpi(8f), 0, a.dpi(8f), 0)
        })
        return FrameLayout(a).apply {
            setPadding(a.dpi(16f), a.dpi(8f), a.dpi(16f), a.dpi(16f))
            addView(bar)
        }
    }
}

/** Material 3 Expressive switch (52x32): primary track + onPrimary thumb with a check when on. */
class M3Switch(ctx: Context, private val sc: SColors) : View(ctx) {
    var onUser: ((Boolean) -> Unit)? = null
    private var checked = false
    private var pos = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var anim: ValueAnimator? = null
    private val check = ctx.getDrawable(R.drawable.pset_check)?.mutate()

    fun setChecked(on: Boolean, animate: Boolean) {
        if (on == checked && (pos == if (on) 1f else 0f)) return
        checked = on
        anim?.cancel()
        if (!animate || !isAttachedToWindow) { pos = if (on) 1f else 0f; invalidate(); return }
        anim = ValueAnimator.ofFloat(pos, if (on) 1f else 0f).apply {
            duration = 250; interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    fun toggleByUser() {
        setChecked(!checked, true)
        performHapticFeedback(if (checked) android.view.HapticFeedbackConstants.TOGGLE_ON else android.view.HapticFeedbackConstants.TOGGLE_OFF)
        onUser?.invoke(checked)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) toggleByUser()
        return true
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val ia = 1 - t
        return android.graphics.Color.argb(
            (android.graphics.Color.alpha(a) * ia + android.graphics.Color.alpha(b) * t).toInt(),
            (android.graphics.Color.red(a) * ia + android.graphics.Color.red(b) * t).toInt(),
            (android.graphics.Color.green(a) * ia + android.graphics.Color.green(b) * t).toInt(),
            (android.graphics.Color.blue(a) * ia + android.graphics.Color.blue(b) * t).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = context.resources.displayMetrics.density
        // track
        r.set(0f, 0f, w, h)
        p.style = Paint.Style.FILL; p.color = mix(sc.highest, sc.primary, pos)
        c.drawRoundRect(r, h / 2, h / 2, p)
        if (pos < 1f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = sc.outline.withAlpha(((1 - pos) * 255).toInt())
            r.inset(d, d); c.drawRoundRect(r, h / 2, h / 2, p)
        }
        // thumb 16 dp off -> 24 dp on
        val tr = (8 + 4 * pos) * d
        val cx = h / 2 + (w - h) * pos
        p.style = Paint.Style.FILL; p.color = mix(sc.outline, sc.onPrimary, pos)
        c.drawCircle(cx, h / 2, tr, p)
        if (pos > 0.5f && check != null) {
            val s = (16 * d).toInt()
            check.setTint(sc.primary); check.alpha = ((pos - 0.5f) * 2 * 255).toInt()
            check.setBounds((cx - s / 2).toInt(), (h / 2 - s / 2).toInt(), (cx + s / 2).toInt(), (h / 2 + s / 2).toInt())
            check.draw(c)
        }
    }
}

/** Horizontal Material 3 Expressive slider (16 dp track, 4x44 dp handle, 6 dp gaps). */
class M3Slider(ctx: Context, private val sc: SColors, private val min: Int, private val max: Int) : View(ctx) {
    var value = 0f
    var onUser: ((Int) -> Unit)? = null
    var dragging = false; private set
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var last = Int.MIN_VALUE

    private fun frac() = if (max == min) 0f else ((value - min) / (max - min)).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        val d = context.resources.displayMetrics.density
        val h = height.toFloat(); val w = width.toFloat()
        val track = 16 * d; val gap = 6 * d; val thumbW = 4 * d; val thumbH = 44 * d
        val x = thumbW / 2 + frac() * (w - thumbW)
        val top = (h - track) / 2
        p.color = sc.primary
        if (x - gap - thumbW / 2 > 0) { r.set(0f, top, x - gap - thumbW / 2, top + track); c.drawRoundRect(r, track / 2, track / 2, p) }
        p.color = sc.highest
        if (x + gap + thumbW / 2 < w) { r.set(x + gap + thumbW / 2, top, w, top + track); c.drawRoundRect(r, track / 2, track / 2, p) }
        p.color = sc.primary
        r.set(x - thumbW / 2, (h - thumbH) / 2, x + thumbW / 2, (h + thumbH) / 2)
        c.drawRoundRect(r, thumbW / 2, thumbW / 2, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                dragging = true; parent.requestDisallowInterceptTouchEvent(true)
                val f = (e.x / width).coerceIn(0f, 1f)
                value = min + f * (max - min)
                val step = Math.round(value)
                if (step != last) {
                    last = step; onUser?.invoke(step)
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; value = Math.round(value).toFloat(); invalidate() }
        }
        return true
    }
}

/** M3 dialog (28 dp corners, surfaceContainerHigh) with a radio list or a text field. */
object PDialog {
    fun choose(a: PixelSettingsActivity, title: String, options: List<String>, selected: Int, done: (Int) -> Unit) {
        val d = android.app.Dialog(a)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dpi(24f), a.dpi(24f), a.dpi(24f), a.dpi(16f))
            background = GradientDrawable().apply { cornerRadius = a.dp(28f); setColor(a.sc.dialog) }
        }
        box.addView(TextView(a).apply {
            text = title; textSize = 24f; typeface = Fonts.regular; setTextColor(a.sc.onSurface)
            setPadding(0, 0, 0, a.dpi(16f))
        })
        options.forEachIndexed { i, o ->
            box.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = a.dpi(48f)
                addView(RadioDot(a, a.sc, i == selected), LinearLayout.LayoutParams(a.dpi(20f), a.dpi(20f)).apply { marginEnd = a.dpi(16f) })
                addView(TextView(a).apply { text = o; textSize = 16f; typeface = Fonts.regular; setTextColor(a.sc.onSurface) })
                setOnClickListener { d.dismiss(); done(i) }
            })
        }
        box.addView(TextView(a).apply {
            text = "Avbryt"; textSize = 14f; typeface = Fonts.medium; setTextColor(a.sc.primary)
            gravity = Gravity.END; setPadding(a.dpi(12f), a.dpi(16f), a.dpi(12f), a.dpi(8f))
            setOnClickListener { d.dismiss() }
        })
        show(d, box)
    }

    fun edit(a: PixelSettingsActivity, title: String, value: String, done: (String) -> Unit) {
        val d = android.app.Dialog(a)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dpi(24f), a.dpi(24f), a.dpi(24f), a.dpi(16f))
            background = GradientDrawable().apply { cornerRadius = a.dp(28f); setColor(a.sc.dialog) }
        }
        box.addView(TextView(a).apply { text = title; textSize = 24f; setTextColor(a.sc.onSurface); setPadding(0, 0, 0, a.dpi(16f)) })
        val et = EditText(a).apply { setText(value); setTextColor(a.sc.onSurface); textSize = 16f; setSelection(value.length) }
        box.addView(et)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, a.dpi(16f), 0, 0) }
        row.addView(TextView(a).apply {
            text = "Avbryt"; textSize = 14f; typeface = Fonts.medium; setTextColor(a.sc.primary); setPadding(a.dpi(12f), a.dpi(10f), a.dpi(12f), a.dpi(10f))
            setOnClickListener { d.dismiss() }
        })
        row.addView(TextView(a).apply {
            text = "OK"; textSize = 14f; typeface = Fonts.medium; setTextColor(a.sc.primary); setPadding(a.dpi(12f), a.dpi(10f), a.dpi(12f), a.dpi(10f))
            setOnClickListener { d.dismiss(); done(et.text.toString()) }
        })
        box.addView(row)
        show(d, box)
    }

    private fun show(d: android.app.Dialog, box: View) {
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(box)
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.window?.setLayout((box.context.resources.displayMetrics.widthPixels * 0.86f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
    }

    class RadioDot(ctx: Context, private val sc: SColors, private val on: Boolean) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = if (on) sc.primary else sc.onVariant
            c.drawCircle(width / 2f, height / 2f, width / 2f - d, p)
            if (on) { p.style = Paint.Style.FILL; c.drawCircle(width / 2f, height / 2f, 5 * d, p) }
        }
    }
}
