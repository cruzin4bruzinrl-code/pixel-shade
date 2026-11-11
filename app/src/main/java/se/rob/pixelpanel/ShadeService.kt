package se.rob.pixelpanel

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Owns two overlay windows: a thin touch strip over the status bar that catches
 * the pull-down, and the warm shade window it drives.
 */
class ShadeService : AccessibilityService() {

    companion object {
        @Volatile var instance: ShadeService? = null
            private set

        /** Pixelpanel is off (e.g. bank mode): open Samsung's own panel instead. */
        @android.annotation.SuppressLint("WrongConstant")
        fun fallbackPanel(ctx: Context) {
            try {
                val sbm = ctx.getSystemService("statusbar")
                sbm.javaClass.getMethod("expandNotificationsPanel").invoke(sbm)
            } catch (_: Throwable) { }
        }
    }

    // Quick-exit detection for bank mode suggestions
    private var lastPkg: String? = null
    private val quickExits = HashMap<String, Int>()
    private var lastPkgAt = 0L
    private val homePkg by lazy {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    }
    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            i.data?.schemeSpecificPart?.let { BankSuggest.onInstalled(this@ShadeService, it) }
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var shade: ShadeWindow
    private var strip: View? = null
    private val stripLp = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        title = "Pixelpanel-strip"
    }
    private var locked = false
    private var lastConfig: Configuration? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { locked = true; if (::shade.isInitialized) shade.close(); updateStrip() }
                Intent.ACTION_USER_PRESENT -> { locked = false; updateStrip() }
                Intent.ACTION_SCREEN_ON -> { locked = isKeyguardLocked(); updateStrip() }
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> if (::shade.isInitialized && shade.isOpen) shade.close()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try { start() } catch (t: Throwable) {
            // Never crash-loop: record why and stay idle so Samsung's shade keeps working.
            CrashLog.write(this, "start", t)
            cleanup()
        }
    }

    private fun start() {
        instance = this
        wm = getSystemService(WindowManager::class.java)
        shade = ShadeWindow(this, wm)
        shade.attach()
        shade.onStateChanged = { updateStrip() }
        addStrip()
        locked = isKeyguardLocked()
        updateStrip()
        lastConfig = Configuration(resources.configuration)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, installReceiver,
            IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun isKeyguardLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    @SuppressLint("ClickableViewAccessibility")
    private fun addStrip() {
        val v = View(this)
        v.setOnTouchListener(StripTouch())
        strip = v
        sizeStrip()
        wm.addView(v, stripLp)
    }

    private fun sizeStrip() {
        val w = resources.displayMetrics.widthPixels
        stripLp.width = if (Prefs.samsungCorner(this)) (w * 0.8f).toInt() else w
        stripLp.height = statusBarHeight() + dpi(4f)
    }

    /** Strip is touchable only when capture is on, the shade is closed and the phone is unlocked. */
    fun updateStrip() {
        val v = strip ?: return
        val active = Prefs.captureEnabled(this) && !locked && !(::shade.isInitialized && shade.isOpen)
        val flags = stripLp.flags
        stripLp.flags = if (active) flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        sizeStrip()
        try { wm.updateViewLayout(v, stripLp) } catch (_: Exception) { }
    }

    fun openShade() {
        if (!::shade.isInitialized) return
        captureBackdrop { shade.open() }
    }

    private var lastShot = 0L

    /**
     * Grabs what is on screen right now so the shade can blur it when Samsung's
     * cross-window blur is off. Calls [then] when done (or failed).
     */
    private var lastColorCheck = 0L
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val learnFromHome = Runnable {
        if (lastPkg != homePkg || shade.isOpen) return@Runnable
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastShot < 400) return@Runnable
        lastShot = now; lastColorCheck = now
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                result.hardwareBuffer.close()
                if (bmp != null) WallSeed.learn(this@ShadeService, bmp) { shade.recolor() }
            }
            override fun onFailure(errorCode: Int) {}
        })
    }

    fun captureBackdrop(then: (() -> Unit)? = null) {
        val now = android.os.SystemClock.uptimeMillis()
        // On the home screen the screenshot shows the wallpaper: learn its colours from it (Samsung
        // doesn't hand wallpaper colours to other apps), at most every 20 s.
        val learnColours = lastPkg != null && lastPkg == homePkg && now - lastColorCheck > 20_000
        if (shade.blurSupported && !learnColours) { then?.invoke(); return }
        if (now - lastShot < 400) { then?.invoke(); return } // system rate limit
        lastShot = now
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                result.hardwareBuffer.close()
                if (!shade.blurSupported) shade.setBackdrop(bmp)
                if (learnColours && bmp != null) {
                    lastColorCheck = now
                    WallSeed.learn(this@ShadeService, bmp) { shade.recolor() }
                }
                then?.invoke()
            }
            override fun onFailure(errorCode: Int) {
                shade.setBackdrop(null)
                then?.invoke()
            }
        })
    }

    fun rebuildShade() { if (::shade.isInitialized) shade.rebuild() }

    fun samsungQuickPanel() { performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) }

    fun powerMenu() { performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) }

    fun lockScreen() { performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }

    fun blurSupported() = ::shade.isInitialized && shade.blurSupported

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val diff = lastConfig?.diff(newConfig) ?: -1
        lastConfig = Configuration(newConfig)
        updateStrip()
        if (diff and (android.content.pm.ActivityInfo.CONFIG_UI_MODE or android.content.pm.ActivityInfo.CONFIG_ORIENTATION or
                android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE or android.content.pm.ActivityInfo.CONFIG_DENSITY) != 0
        ) {
            if (::shade.isInitialized) shade.rebuild()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Watchdog: make sure the pull-down strip is still there and touchable after every app switch.
        if (!::wm.isInitialized || instance == null) return
        strip?.let { if (!it.isAttachedToWindow) try { wm.addView(it, stripLp) } catch (_: Exception) { } }
        updateStrip()
        // An app that bounced straight back to the home screen probably refused to run with us on.
        val now = android.os.SystemClock.uptimeMillis()
        val prev = lastPkg
        if (pkg == homePkg && prev != null && prev != homePkg && prev != packageName &&
            prev != "com.android.systemui" && now - lastPkgAt < 2500 && !BankMode.isBank(this, prev)) {
            val n = (quickExits[prev] ?: 0) + 1
            quickExits[prev] = n
            if (n >= 2) BankSuggest.onQuickExit(this, prev)
        }
        if (pkg != "com.android.systemui" && !pkg.contains("inputmethod") && !pkg.contains("honeyboard")) {
            if (pkg != prev) { lastPkg = pkg; lastPkgAt = now }
        }
        // Back on the home screen with unknown (or stale) wallpaper colours: learn them quietly now,
        // so the shade already has the right colours the next time it is pulled down.
        if (pkg == homePkg && Prefs.seedColor(this) == 0 && ::shade.isInitialized && !shade.isOpen &&
            (Prefs.wallSeed(this) == 0 || now - lastColorCheck > 120_000)) {
            mainHandler.removeCallbacks(learnFromHome)
            mainHandler.postDelayed(learnFromHome, 700)
        }
        // Bank mode: step aside before the bank app's security check notices us.
        if (BankMode.isBank(this, pkg) && BankMode.canSwitch(this)) {
            if (::shade.isInitialized) shade.close()
            BankMode.suspend(this)
        }
    }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance == null) return
        instance = null
        try { unregisterReceiver(receiver) } catch (_: Exception) { }
        try { unregisterReceiver(installReceiver) } catch (_: Exception) { }
        strip?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) { } }
        strip = null
        if (::shade.isInitialized) shade.detach()
    }

    /**
     * Touch on the strip owns the whole gesture, so the finger keeps driving the
     * shade after it leaves the strip. Double-tap opens Samsung's own panel.
     */
    private inner class StripTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@ShadeService).scaledTouchSlop / 2
        private var downY = 0f
        private var dragging = false
        private var vt: VelocityTracker? = null
        private var lastTapUp = 0L

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY; dragging = false
                    vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                    captureBackdrop()
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dy = e.rawY - downY
                    if (!dragging && dy > slop) {
                        dragging = true
                        downY += slop // no jump when the drag kicks in
                        shade.beginDrag(e.eventTime)
                    }
                    if (dragging) shade.dragTo(e.rawY - downY)
                }
                MotionEvent.ACTION_UP -> {
                    vt?.addMovement(e)
                    vt?.computeCurrentVelocity(1000)
                    if (dragging) {
                        shade.release(vt?.yVelocity ?: 0f)
                    } else {
                        if (e.eventTime - lastTapUp < ViewConfiguration.getDoubleTapTimeout()) {
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                            lastTapUp = 0
                        } else lastTapUp = e.eventTime
                    }
                    dragging = false
                    vt?.recycle(); vt = null
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) shade.release(0f)
                    dragging = false
                }
            }
            return true
        }
    }
}
