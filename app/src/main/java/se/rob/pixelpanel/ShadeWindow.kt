package se.rob.pixelpanel

import android.app.ActivityOptions
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.function.Consumer
import kotlin.math.roundToInt

/**
 * The Pixel-style shade, living in an accessibility overlay window that is
 * added once and kept warm. Hidden = invisible and untouchable.
 */
class ShadeWindow(private val ctx: Context, private val wm: WindowManager) : NotifHost {

    val root = ShadeRoot(ctx)
    private val backdrop = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private var hasBackdrop = false
    private val scrim = View(ctx)
    private var content = LinearLayout(ctx)
    private var diag: TextView? = null
    private var notifList: NotifList? = null
    private var notifScroll: ScrollView? = null
    private var clearAllPill: View? = null
    private var batteryView: TextView? = null
    private var slider: BrightnessSlider? = null
    private var tilePager: TilePager? = null
    private val tiles = HashMap<String, Tile>()
    private val expandedKeys = HashSet<String>()

    var progress = 0f
        private set
    var isOpen = false
        private set
    var onStateChanged: ((Boolean) -> Unit)? = null
    /** Set when we fire a PendingIntent; the service closes the shade if an app then comes to front. */
    var launchedAt = 0L
        private set

    private var attached = false
    private var shown = false
    private var currentBlur = -1
    private var dragStart = 0f
    private var notifsDirty = true
    private var dismissingUntil = 0L
    var blurSupported = wm.isCrossWindowBlurEnabled
        private set

    @Suppress("DEPRECATION")
    private val refreshHz = try { wm.defaultDisplay.refreshRate } catch (_: Exception) { 60f }
    private val meter = FrameMeter(refreshHz)
    private val main = Handler(Looper.getMainLooper())

    private val cameraManager = ctx.getSystemService(CameraManager::class.java)
    private val torchId: String? = try {
        cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (_: Exception) { null }
    private var torchOn = false

    private val baseFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    private val hiddenFlags = baseFlags or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        hiddenFlags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        title = "Pixelpanel"
    }

    // Material 3 Expressive "default spatial" spring for the expressive motion scheme.
    private val prop = object : FloatPropertyCompat<ShadeWindow>("progress") {
        override fun getValue(o: ShadeWindow) = o.progress
        override fun setValue(o: ShadeWindow, v: Float) = o.setProgress(v)
    }
    private val spring = SpringAnimation(this, prop).apply {
        spring = SpringForce().apply { dampingRatio = 0.8f; stiffness = 380f }
        setMinimumVisibleChange(0.0005f)
        addEndListener { _, canceled, value, _ -> if (!canceled) settled(value) }
    }

    private val blurListener = Consumer<Boolean> { enabled ->
        blurSupported = enabled
        applyScrimColor()
    }

    private val storeListener: () -> Unit = {
        notifsDirty = true
        if (shown) scheduleNotifRender()
    }

    init {
        Fonts.init(ctx)
        root.addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        build()
        root.visibility = View.INVISIBLE
        root.onDragStart = { beginDrag(0) }
        root.onDrag = { dy -> dragTo(dy) }
        root.onRelease = { vy -> release(vy) }
        root.onBack = { close() }
        root.childScrolls = { x, y ->
            val s = notifScroll
            if (s == null) false else {
                val loc = IntArray(2); s.getLocationOnScreen(loc)
                x >= loc[0] && x <= loc[0] + s.width && y >= loc[1] && y <= loc[1] + s.height && s.canScrollVertically(1)
            }
        }

        torchId?.let { id ->
            try {
                cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId == id) { torchOn = enabled; if (shown) refreshTiles() }
                    }
                }, main)
            } catch (_: Exception) { }
        }
        NotifStore.addListener(storeListener)
        NotifStore.addDndListener { if (shown) refreshTiles() }
    }

    fun attach() {
        if (attached) return
        wm.addView(root, lp)
        wm.addCrossWindowBlurEnabledListener(ctx.mainExecutor, blurListener)
        attached = true
    }

    fun detach() {
        if (!attached) return
        spring.cancel()
        NotifStore.removeListener(storeListener)
        try { wm.removeCrossWindowBlurEnabledListener(blurListener) } catch (_: Exception) { }
        try { wm.removeViewImmediate(root) } catch (_: Exception) { }
        attached = false
    }

    /**
     * Fallback blur when the system's cross-window blur is off: a screenshot of
     * what is behind the shade, blurred on the GPU. Null clears it.
     */
    fun setBackdrop(bmp: Bitmap?) {
        backdrop.setImageBitmap(bmp)
        hasBackdrop = bmp != null
        if (bmp != null) {
            val r = ctx.dp(Prefs.blurRadius(ctx).toFloat())
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        } else backdrop.setRenderEffect(null)
        backdrop.alpha = progress.coerceIn(0f, 1f)
        applyScrimColor()
        diag?.text = diagText(Prefs.lastStats(ctx))
    }

    /** Rebuild content after a theme or orientation change. */
    private var rebuildPending = false

    fun rebuild() {
        if (isOpen || shown) { rebuildPending = true; return }
        rebuildPending = false
        root.removeView(content)
        content = LinearLayout(ctx)
        tiles.clear()
        build()
        notifsDirty = true
    }

    private val height: Float
        get() = content.height.takeIf { it > 0 }?.toFloat() ?: screenHeight().toFloat()

    private fun screenHeight(): Int = try { wm.currentWindowMetrics.bounds.height() }
        catch (_: Exception) { ctx.resources.displayMetrics.heightPixels }

    // ---- Gesture API used by the service's top strip --------------------------------

    fun beginDrag(eventUptimeMs: Long) {
        spring.cancel()
        show()
        dragStart = progress
        meter.start(eventUptimeMs)
    }

    fun dragTo(dy: Float) {
        var p = dragStart + dy / height
        if (p > 1f) p = 1f + (p - 1f) * 0.35f // resistance when pulled past fully open
        setProgress(p.coerceAtLeast(0f))
    }

    fun release(vy: Float) {
        val v = vy / height
        val open = when {
            v > 0.6f -> true
            v < -0.6f -> false
            else -> progress > 0.4f
        }
        animateTo(if (open) 1f else 0f, v)
    }

    fun open() {
        beginDrag(0)
        animateTo(1f, 0f)
    }

    fun close() {
        if (!shown) return
        hideIme()
        meter.start(0)
        animateTo(0f, 0f)
    }

    private fun animateTo(target: Float, velocity: Float) {
        spring.setStartVelocity(velocity)
        spring.animateToFinalPosition(target)
    }

    // ---- NotifHost ------------------------------------------------------------------------

    override fun launch(pi: PendingIntent?, fill: Intent?, closeShade: Boolean) {
        if (pi == null) return
        try {
            val opts = if (Build.VERSION.SDK_INT >= 34) {
                @Suppress("DEPRECATION")
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            pi.send(ctx, 0, fill, null, null, null, opts)
            launchedAt = SystemClock.uptimeMillis()
        } catch (_: Exception) { }
        if (closeShade) close()
    }

    override fun dismiss(keys: List<String>) {
        dismissingUntil = SystemClock.uptimeMillis() + 600
        NotifStore.cancel(keys)
    }

    override fun focusInput(edit: EditText) {
        edit.post {
            edit.requestFocus()
            ctx.getSystemService(InputMethodManager::class.java).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun isExpanded(key: String) = key in expandedKeys
    override fun setExpanded(key: String, v: Boolean) { if (v) expandedKeys += key else expandedKeys -= key }

    private fun hideIme() {
        try { ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0) } catch (_: Exception) { }
    }

    private fun startSettings(intent: Intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            launchedAt = SystemClock.uptimeMillis()
        } catch (_: ActivityNotFoundException) { } catch (_: SecurityException) { }
        close()
    }

    private fun scheduleNotifRender() {
        main.removeCallbacks(renderRunnable)
        val wait = (dismissingUntil - SystemClock.uptimeMillis()).coerceAtLeast(0)
        main.postDelayed(renderRunnable, wait)
    }

    private val renderRunnable = Runnable { renderNotifs() }

    private fun renderNotifs() {
        val list = notifList ?: return
        val keys = NotifStore.entries.map { it.key }.toSet() + NotifStore.entries.map { it.sbn.groupKey }
        expandedKeys.retainAll(keys)
        try { list.render(NotifStore.entries, NotifStore.service != null) }
        catch (t: Throwable) { CrashLog.write(ctx, "render", t) }
        clearAllPill?.visibility = if (list.hasClearable) View.VISIBLE else View.INVISIBLE
        notifsDirty = false
    }

    // ---- Rendering --------------------------------------------------------------------

    private fun setProgress(p: Float) {
        progress = p
        val h = height
        content.translationY = if (p <= 1f) (p - 1f) * h else (p - 1f) * h * 0.3f
        val clamped = p.coerceIn(0f, 1f)
        scrim.alpha = clamped
        backdrop.alpha = clamped
        content.alpha = (clamped * 1.6f).coerceAtMost(1f)
        updateBlur(clamped)
    }

    private fun updateBlur(clamped: Float) {
        if (!blurSupported || !shown) return
        val max = ctx.dpi(Prefs.blurRadius(ctx).toFloat())
        val target = if (Prefs.blurScales(ctx)) ((clamped * max) / 4f).roundToInt() * 4 else if (clamped > 0f) max else 0
        if (target == currentBlur) return
        currentBlur = target
        lp.blurBehindRadius = target
        if (attached) wm.updateViewLayout(root, lp)
    }

    private fun show() {
        if (shown) return
        shown = true
        applyScrimColor()
        refreshTiles()
        try { refreshBattery(); refreshBrightness() } catch (t: Throwable) { CrashLog.write(ctx, "batteri/ljus", t) }
        if (notifsDirty) renderNotifs()
        notifScroll?.scrollTo(0, 0)
        tilePager?.reset()
        root.visibility = View.VISIBLE
        lp.flags = hiddenFlags or (if (blurSupported) WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0)
        currentBlur = -1
        lp.blurBehindRadius = 0
        if (attached) wm.updateViewLayout(root, lp)
    }

    private fun settled(value: Float) {
        val stats = meter.stop()
        if (stats != null) {
            Prefs.setLastStats(ctx, stats)
            diag?.text = diagText(stats)
        }
        if (value <= 0.001f) {
            shown = false; isOpen = false
            root.visibility = View.INVISIBLE
            setBackdrop(null)
            lp.flags = hiddenFlags
            lp.blurBehindRadius = 0
            currentBlur = -1
            if (attached) wm.updateViewLayout(root, lp)
            onStateChanged?.invoke(false)
            if (rebuildPending) rebuild()
        } else if (!isOpen) {
            isOpen = true
            lp.flags = baseFlags or (if (blurSupported) WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0)
            if (attached) wm.updateViewLayout(root, lp)
            root.requestFocus()
            onStateChanged?.invoke(true)
        }
    }

    private fun applyScrimColor() {
        val pal = Palette(ctx)
        val alpha = when {
            blurSupported -> if (pal.night) 120 else 140
            hasBackdrop -> if (pal.night) 140 else 160
            else -> 245
        }
        scrim.setBackgroundColor(pal.surface.withAlpha(alpha))
    }

    private fun diagText(stats: String?): String {
        val blur = when {
            blurSupported -> "Oskärpa: äkta fönsteroskärpa (${Prefs.blurRadius(ctx)} dp)"
            hasBackdrop -> "Oskärpa: skärmbild (${Prefs.blurRadius(ctx)} dp)"
            else -> "Oskärpa: ingen (skyddad app eller ingen skärmbild)"
        }
        return if (stats == null) blur else "$blur · $stats"
    }

    private fun refreshBattery() {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        batteryView?.text = "$pct %"
    }

    // ---- System tiles -------------------------------------------------------------------

    private fun refreshTiles() {
        try { refreshTilesUnsafe() } catch (t: Throwable) { CrashLog.write(ctx, "knappar", t) }
    }

    private fun refreshTilesUnsafe() {
        tiles["internet"]?.let { t ->
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> { t.symbol = Sym.WIFI; t.secondary = "Wi‑Fi"; t.setOn(true) }
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> { t.symbol = Sym.SIGNAL_CELLULAR_ALT; t.secondary = "Mobildata"; t.setOn(true) }
                else -> { t.symbol = Sym.WIFI_OFF; t.secondary = "Ej ansluten"; t.setOn(false) }
            }
        }
        tiles["bluetooth"]?.let { t ->
            val on = try { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true } catch (_: Exception) { false }
            t.symbol = if (on) Sym.BLUETOOTH else Sym.BLUETOOTH_DISABLED
            t.secondary = if (on) "På" else "Av"; t.setOn(on)
        }
        tiles["dnd"]?.let { t ->
            val on = NotifStore.dndOn()
            t.secondary = if (NotifStore.service == null) "Kräver notisåtkomst" else if (on) "På" else "Av"; t.setOn(on)
        }
        tiles["torch"]?.let { t ->
            t.symbol = Sym.FLASHLIGHT_ON
            t.secondary = if (torchOn) "På" else "Av"; t.setOn(torchOn)
        }
        tiles["airplane"]?.setOn(Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        tiles["saver"]?.setOn(ctx.getSystemService(PowerManager::class.java).isPowerSaveMode)
        tiles["rotate"]?.let { t ->
            val on = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            t.symbol = if (on) Sym.SCREEN_ROTATION else Sym.SCREEN_LOCK_ROTATION; t.setOn(on)
        }
        tiles["location"]?.let { t ->
            val on = ctx.getSystemService(LocationManager::class.java).isLocationEnabled
            t.symbol = Sym.LOCATION_ON; t.setOn(on)
        }
        tiles["data"]?.let { t ->
            val on = Settings.Global.getInt(ctx.contentResolver, "mobile_data", 1) == 1
            t.symbol = if (on) Sym.SIGNAL_CELLULAR_ALT else Sym.SIGNAL_CELLULAR_OFF
            t.secondary = if (on) "På" else "Av"; t.setOn(on)
        }
        tiles["dark"]?.let { t ->
            val on = ctx.isNight(); t.secondary = if (on) "På" else "Av"; t.setOn(on)
        }
        tiles["hotspot"]?.let { t -> t.secondary = "Inställningar"; t.setOn(false) }
        tiles["nfc"]?.let { t ->
            val on = try { android.nfc.NfcAdapter.getDefaultAdapter(ctx)?.isEnabled == true } catch (_: Exception) { false }
            t.secondary = if (on) "På" else "Av"; t.setOn(on)
        }
        listOf("camera", "alarm", "cast", "screenoff").forEach { tiles[it]?.setOn(false) }
    }

    private fun refreshBrightness() {
        val raw = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        slider?.setLinear(raw / 255f)
    }

    private fun writeBrightness(linear: Float) {
        if (!Settings.System.canWrite(ctx)) return
        val v = (1 + linear * 254).roundToInt().coerceIn(1, 255)
        try { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v) } catch (_: Exception) { }
    }

    private fun askWriteSettings() = startSettings(
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
    )

    /** Flip a system toggle through Shizuku, or fall back to its settings page. */
    private fun shell(tile: Tile, cmd: (Boolean) -> String, fallback: Intent) {
        if (!Shell.granted()) { startSettings(fallback); return }
        val target = !tile.on
        tile.setOn(target)
        Shell.run(cmd(target)) { ok ->
            if (!ok) CrashLog.write(ctx, "shizuku", RuntimeException("Kommandot misslyckades: ${cmd(target)}"))
            main.postDelayed({ refreshTiles() }, 600)
        }
    }

    private fun makeTiles(pal: Palette) {
        fun t(id: String, sym: String, label: String, wide: Boolean) =
            Tile(ctx, pal, sym, label, "", wide).also { tiles[id] = it }

        // Page 1
        t("internet", Sym.WIFI, "Internet", true).apply {
            onToggle = { shell(this, { on -> "cmd wifi set-wifi-enabled ${if (on) "enabled" else "disabled"}" }, Intent(Settings.ACTION_WIFI_SETTINGS)) }
            onTap = { startSettings(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) }
            onLong = { startSettings(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
        t("bluetooth", Sym.BLUETOOTH, "Bluetooth", true).apply {
            onToggle = { shell(this, { on -> "cmd bluetooth_manager ${if (on) "enable" else "disable"} || svc bluetooth ${if (on) "enable" else "disable"}" }, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            onTap = { startSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            onLong = onTap
        }
        t("dnd", Sym.DO_NOT_DISTURB_ON, "Stör ej", true).apply {
            onToggle = {
                if (NotifStore.service == null) startSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                else { val on = !NotifStore.dndOn(); if (NotifStore.setDnd(on)) { setOn(on); secondary = if (on) "På" else "Av" } }
            }
            onTap = { startSettings(Intent("android.settings.ZEN_MODE_SETTINGS")) }
            onLong = onTap
        }
        t("torch", Sym.FLASHLIGHT_ON, "Ficklampa", true).apply {
            onTap = { torchId?.let { try { cameraManager.setTorchMode(it, !torchOn) } catch (_: Exception) { } } }
        }
        t("airplane", Sym.AIRPLANEMODE_ACTIVE, "Flygplansläge", false).apply {
            onTap = { shell(this, { on -> "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}" }, Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
            onLong = { startSettings(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
        }
        t("saver", Sym.BATTERY_SAVER, "Batterisparläge", false).apply {
            onTap = { shell(this, { on -> "cmd power set-mode ${if (on) 1 else 0}" }, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
            onLong = { startSettings(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
        }
        t("rotate", Sym.SCREEN_ROTATION, "Rotera automatiskt", false).apply {
            onTap = {
                if (!Settings.System.canWrite(ctx)) askWriteSettings()
                else {
                    val on = !this.on
                    Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
                    refreshTiles()
                }
            }
        }
        t("location", Sym.LOCATION_ON, "Plats", false).apply {
            onTap = { shell(this, { on -> "cmd location set-location-enabled $on" }, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            onLong = { startSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }

        // Page 2
        t("data", Sym.SIGNAL_CELLULAR_ALT, "Mobildata", true).apply {
            onToggle = { shell(this, { on -> "svc data ${if (on) "enable" else "disable"}" }, Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)) }
            onTap = { startSettings(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)) }
        }
        t("dark", Sym.DARK_MODE, "Mörkt tema", true).apply {
            onTap = { shell(this, { on -> "cmd uimode night ${if (on) "yes" else "no"}" }, Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
            onLong = { startSettings(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
        }
        t("hotspot", Sym.WIFI_TETHERING, "Surfzon", true).apply {
            onTap = { startSettings(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }
        t("nfc", Sym.NFC, "NFC", true).apply {
            onToggle = { shell(this, { on -> "svc nfc ${if (on) "enable" else "disable"}" }, Intent(Settings.ACTION_NFC_SETTINGS)) }
            onTap = { startSettings(Intent(Settings.ACTION_NFC_SETTINGS)) }
        }
        t("camera", Sym.PHOTO_CAMERA, "Kamera", false).apply {
            onTap = { startSettings(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
        }
        t("alarm", Sym.ALARM, "Alarm", false).apply {
            onTap = { startSettings(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) }
        }
        t("cast", Sym.CAST, "Casta skärmen", false).apply {
            onTap = { startSettings(Intent(Settings.ACTION_CAST_SETTINGS)) }
        }
        t("screenoff", Sym.POWER_SETTINGS_NEW, "Lås skärmen", false).apply {
            onTap = { close(); main.postDelayed({ ShadeService.instance?.lockScreen() }, 350) }
        }
    }

    // ---- Layout -----------------------------------------------------------------------

    private fun navBarHeight(): Int = try {
        wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
    } catch (_: Exception) { 0 }

    private fun build() {
        val pal = Palette(ctx)
        val pad = ctx.dpi(16f)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(pad, ctx.statusBarHeight() + ctx.dpi(8f), pad, navBarHeight() + ctx.dpi(10f))
        // Must stay true: clipChildren=false here would stop the notification ScrollView
        // from clipping its cards, so they bled under the tiles and the bottom pills.
        content.clipChildren = true
        content.clipToPadding = false

        // Header: clock + date, battery on the right
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val clockCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        clockCol.addView(TextClock(ctx).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 34f; setTextColor(pal.onSurface); typeface = Fonts.display; includeFontPadding = false
        })
        clockCol.addView(TextClock(ctx).apply {
            format24Hour = "EEE d MMM"; format12Hour = "EEE d MMM"
            textSize = 14f; setTextColor(pal.onSurfaceVariant); typeface = Fonts.regular
            setPadding(0, ctx.dpi(4f), 0, 0)
        })
        header.addView(clockCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val settingsBtn = SymbolView(ctx, Sym.SETTINGS, pal.onSurface, 22f, filled = false).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(pal.card)
            }
            setOnClickListener { startSettings(Intent(Settings.ACTION_SETTINGS)) }
        }
        batteryView = ctx.label("", 14f, pal.onSurfaceVariant).apply { setPadding(0, 0, ctx.dpi(12f), ctx.dpi(10f)) }
        header.addView(batteryView)
        header.addView(settingsBtn, LinearLayout.LayoutParams(ctx.dpi(40f), ctx.dpi(40f)))
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = ctx.dpi(6f)
        })

        // Test-build diagnostics, kept near the top so it is never cut off.
        diag = ctx.label(diagText(Prefs.lastStats(ctx)), 10f, pal.onSurfaceVariant, lines = 2)
        content.addView(diag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = ctx.dpi(10f)
        })

        // Tiles: two pages, swipe sideways like Pixel
        makeTiles(pal)
        val gap = ctx.dpi(8f)
        val tileH = ctx.dpi(68f)
        fun row(vararg ids: String): LinearLayout {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            ids.forEachIndexed { i, id ->
                val t = tiles.getValue(id)
                r.addView(t, LinearLayout.LayoutParams(0, tileH, if (t.wide) 2f else 1f).apply { if (i > 0) marginStart = gap })
            }
            return r
        }
        fun pageOf(vararg rows: LinearLayout) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            rows.forEachIndexed { i, r ->
                addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileH).apply { if (i > 0) topMargin = gap })
            }
        }
        val pager = TilePager(ctx, pal, listOf(
            pageOf(row("internet", "bluetooth"), row("dnd", "torch"), row("airplane", "saver", "rotate", "location")),
            pageOf(row("data", "dark"), row("hotspot", "nfc"), row("camera", "alarm", "cast", "screenoff")),
        ))
        tilePager = pager
        content.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(pager.dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(6f)).apply {
            topMargin = ctx.dpi(10f)
        })


        // Brightness
        val s = BrightnessSlider(ctx, pal)
        s.onFirstTouch = { if (Settings.System.canWrite(ctx)) true else { askWriteSettings(); false } }
        s.onChange = { linear, _ -> writeBrightness(linear) }
        slider = s
        content.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(52f)).apply {
            topMargin = ctx.dpi(10f)
        })

        // Notifications: scrolls inside the remaining space
        val list = NotifList(ctx, pal, this)
        notifList = list
        val scroll = object : ScrollView(ctx) {
            val tap = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    // Tap on the empty space below the last card closes the shade, like Pixel.
                    if (e.y + scrollY > list.height) close()
                    return false
                }
            })
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean { tap.onTouchEvent(ev); return super.dispatchTouchEvent(ev) }
        }.apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, ctx.dpi(16f), 0, ctx.dpi(8f))
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        notifScroll = scroll
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(pill(ctx, pal, "Hantera") {
            startSettings(Intent(if (Build.VERSION.SDK_INT >= 32) Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS else "android.settings.NOTIFICATION_SETTINGS"))
        })
        actions.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        clearAllPill = pill(ctx, pal, "Rensa allt") { list.clearAll() }
        actions.addView(clearAllPill)
        content.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(8f)
        })

        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, screenHeight(), Gravity.TOP))
        content.translationY = -10000f
        scrim.alpha = 0f
        applyScrimColor()
    }
}
