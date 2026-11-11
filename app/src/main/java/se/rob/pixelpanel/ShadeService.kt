package se.rob.pixelpanel

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
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
        stripLp.height = statusBarHeight()
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

    fun openShade() { if (::shade.isInitialized) shade.open() }

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
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
        strip?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) { } }
        strip = null
        if (::shade.isInitialized) shade.detach()
    }

    /**
     * Touch on the strip owns the whole gesture, so the finger keeps driving the
     * shade after it leaves the strip. Double-tap opens Samsung's own panel.
     */
    private inner class StripTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@ShadeService).scaledTouchSlop
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
