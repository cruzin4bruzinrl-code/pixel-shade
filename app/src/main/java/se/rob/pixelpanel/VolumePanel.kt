package se.rob.pixelpanel

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Pixel 10 volume dialog (Android 16 QPR1 "volume_dialog" redesign), rebuilt from AOSP:
 * res/layout/volume_dialog*.xml, values/dimens.xml (volume_dialog_*), VolumeDialogViewBinder
 * (spring 700 / 0.9, slide in from the edge), VolumeDialogSliderViewBinder + SliderTrack
 * (40 dp track, 12/2 dp corners, 6 dp gap, 52x4 dp thumb, 20 dp icon), the ringer drawer
 * (vibrate / silent / normal, selected = primary circle) and a 3 s dismiss timeout.
 */
class VolumePanel(private val ctx: Context, private val wm: WindowManager) {

    private val main = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val vib = ctx.getSystemService(Vibrator::class.java)
    private var pal = Palette(ctx)

    // AOSP dimens
    private val bgW = ctx.dp(60f)              // volume_dialog_width
    private val bgRadius = ctx.dp(30f)         // volume_dialog_background_corner_radius
    private val bgSmallRadius = ctx.dp(12f)    // volume_dialog_background_square_corner_radius
    private val sliderW = ctx.dp(52f)          // volume_dialog_slider_width
    private val sliderH = ctx.dp(254f)         // volume_dialog_slider_height
    private val winMargin = ctx.dp(12f)        // volume_dialog_window_margin
    private val spacing = ctx.dp(4f)           // volume_dialog_components_spacing
    private val btn = ctx.dp(48f)              // volume_dialog_button_size
    private val bgTop = ctx.dp(28f)            // -volume_dialog_background_top_margin
    private val bgBottom = ctx.dp(6f)          // -volume_dialog_background_margin_negative

    private val winW = (sliderW + winMargin * 2).toInt()
    private val sliderTop = btn * 3 + spacing * 2   // room for the opened ringer drawer
    private val winH = (sliderTop + sliderH + spacing + btn + bgBottom + spacing).toInt()

    var isShowing = false
        private set
    private var stream = AudioManager.STREAM_MUSIC
    private var touching = false
    private var drawerOpen = false
    private var attached = false

    private val root = object : FrameLayout(ctx) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> { dismiss(); return true }
                MotionEvent.ACTION_DOWN -> { touching = true; main.removeCallbacks(timeout) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false; resetTimeout() }
            }
            return super.dispatchTouchEvent(ev)
        }
    }.apply { clipChildren = false; clipToPadding = false }

    private val bg = View(ctx)
    private val ringerBg = View(ctx)
    private val slider = VolumeSlider(ctx)
    private val settings = ImageView(ctx)
    private val ringerButtons = ArrayList<Pair<Int, ImageView>>()

    private val lp = WindowManager.LayoutParams(
        winW, winH,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        title = "PixelpanelVolume"
    }

    // Visibility spring from VolumeDialogViewBinder
    private val fraction = FloatValueHolder(0f)
    private val spring = SpringAnimation(fraction).apply {
        spring = SpringForce().setStiffness(700f).setDampingRatio(0.9f)
        setMinimumVisibleChange(0.01f)
        addUpdateListener { _, v, _ -> applyFraction(v) }
        addEndListener { _, _, v, _ -> if (v <= 0.001f && !isShowing) detach() }
    }

    private val timeout = Runnable { if (!touching) dismiss() }

    init {
        build()
    }

    private fun build() {
        root.removeAllViews(); ringerButtons.clear()
        val bgLeft = (winW - bgW) / 2f
        bg.background = GradientDrawable().apply { setColor(pal.surfaceSolid); cornerRadius = bgRadius }
        root.addView(bg, FrameLayout.LayoutParams(bgW.toInt(), 0).apply {
            leftMargin = bgLeft.toInt()
            topMargin = (sliderTop - bgTop).toInt()
            height = (bgTop + sliderH + spacing + btn + bgBottom).toInt()
        })
        ringerBg.background = GradientDrawable().apply { setColor(pal.surfaceSolid); cornerRadius = bgRadius }
        ringerBg.visibility = View.INVISIBLE
        root.addView(ringerBg, FrameLayout.LayoutParams(bgW.toInt(), (btn * 3 + ctx.dp(12f)).toInt()).apply {
            leftMargin = bgLeft.toInt(); topMargin = (sliderTop - spacing - btn * 3 - ctx.dp(6f)).toInt()
        })
        // Ringer drawer: top to bottom vibrate, silent, normal (children are added in reverse of
        // availableModes in VolumeDialogRingerViewBinder); closed shows only the selected one.
        val modes = buildList {
            if (vib?.hasVibrator() == true) add(AudioManager.RINGER_MODE_VIBRATE)
            add(AudioManager.RINGER_MODE_SILENT); add(AudioManager.RINGER_MODE_NORMAL)
        }
        modes.forEachIndexed { i, mode ->
            val v = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER
                setOnClickListener { onRinger(mode) }
            }
            ringerButtons += mode to v
            root.addView(v, FrameLayout.LayoutParams(btn.toInt(), btn.toInt()).apply {
                leftMargin = ((winW - btn) / 2f).toInt()
                topMargin = (sliderTop - spacing - btn * (modes.size - i)).toInt()
            })
        }
        root.addView(slider, FrameLayout.LayoutParams(sliderW.toInt(), sliderH.toInt()).apply {
            leftMargin = winMargin.toInt(); topMargin = sliderTop.toInt()
        })
        // Bottom: the "audio bars" button that opens the volume panel (VolumeDialogSettingsButton)
        settings.apply {
            setImageResource(R.drawable.aosp_audio_bars_idle)
            setColorFilter(pal.primary)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = ctx.dpi(4f); setPadding(p, p, p, p)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(pal.onSurface.withAlpha(30)), null,
                GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = ctx.dp(20f) })
            setOnClickListener { openVolumeSettings() }
        }
        root.addView(settings, FrameLayout.LayoutParams(btn.toInt(), btn.toInt()).apply {
            leftMargin = ((winW - btn) / 2f).toInt(); topMargin = (sliderTop + sliderH + spacing).toInt()
        })
        slider.onUser = { v -> setVolume(v) }
        refreshRinger()
    }

    fun recolor() { pal = Palette(ctx); slider.pal = pal; build(); if (isShowing) refresh() }

    // ---- key handling --------------------------------------------------------------------------

    /** Volume key pressed: change the active stream by one step and show the dialog. */
    fun onVolumeKey(up: Boolean) {
        stream = activeStream()
        am.adjustStreamVolume(stream, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        show()
    }

    private fun activeStream(): Int = when (am.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_MUSIC   // Pixel: volume keys control media by default
    }

    fun show() {
        refresh()
        if (!attached) {
            val sh = try { wm.currentWindowMetrics.bounds.height() } catch (_: Exception) { ctx.resources.displayMetrics.heightPixels }
            // Slider centred vertically on screen (vertical bias 0.5)
            lp.y = (sh / 2f - sliderTop - sliderH / 2f).toInt()
            try { wm.addView(root, lp); attached = true } catch (_: Exception) { return }
            applyFraction(0f)
        }
        isShowing = true
        spring.animateToFinalPosition(1f)
        resetTimeout()
    }

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        main.removeCallbacks(timeout)
        setDrawer(false)
        spring.animateToFinalPosition(0f)
    }

    private fun detach() {
        if (attached) { try { wm.removeView(root) } catch (_: Exception) { }; attached = false }
    }

    private fun resetTimeout() {
        main.removeCallbacks(timeout)
        if (!isShowing) return
        val t = Settings.Secure.getInt(ctx.contentResolver, "volume_dialog_dismiss_timeout", 3000)
        main.postDelayed(timeout, t.toLong())
    }

    private fun applyFraction(f: Float) {
        // VolumeDialogViewBinder.applyAnimationProgress: alpha = ceil(fraction), slide from the edge
        root.alpha = if (f > 0f) 1f else 0f
        root.translationX = (1f - f) * winW
    }

    // ---- state ---------------------------------------------------------------------------------

    private fun refresh() {
        val min = am.getStreamMinVolume(stream); val max = am.getStreamMaxVolume(stream)
        slider.setRange(min, max)
        if (!slider.dragging) slider.setValue(am.getStreamVolume(stream))
        slider.icon = streamIcon(am.getStreamVolume(stream) <= min)
        slider.invalidate()
        refreshRinger()
    }

    private fun streamIcon(muted: Boolean): Drawable? = ctx.getDrawable(when (stream) {
        AudioManager.STREAM_VOICE_CALL -> R.drawable.aosp_ic_speaker_on
        AudioManager.STREAM_RING -> if (muted) R.drawable.aosp_ic_ring_volume_off else R.drawable.aosp_ic_ring_volume
        AudioManager.STREAM_ALARM -> if (muted) R.drawable.aosp_ic_volume_alarm_mute else R.drawable.aosp_ic_alarm
        else -> if (muted) R.drawable.aosp_ic_volume_media_mute else R.drawable.aosp_ic_volume_media
    })?.mutate()

    private var lastTick = 0
    private fun setVolume(v: Int) {
        if (v != lastTick) {
            lastTick = v
            try { vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) } catch (_: Exception) { }
        }
        am.setStreamVolume(stream, v, 0)
        slider.icon = streamIcon(v <= am.getStreamMinVolume(stream))
        resetTimeout()
    }

    private fun ringerIcon(mode: Int) = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> R.drawable.aosp_ic_speaker_mute
        AudioManager.RINGER_MODE_VIBRATE -> R.drawable.aosp_ic_volume_ringer_vibrate
        else -> R.drawable.aosp_ic_speaker_on
    }

    private fun refreshRinger() {
        val cur = am.ringerMode
        ringerButtons.forEach { (mode, v) ->
            val selected = mode == cur
            v.setImageResource(ringerIcon(mode))
            v.setColorFilter(if (selected) pal.onPrimary else pal.onSurface)
            // volume_drawer_selection_bg (primary, r 20) vs volume_ringer_item_bg (r 12), both inset 4 dp
            v.background = InsetDrawable(GradientDrawable().apply {
                setColor(if (selected) pal.primary else pal.surfaceContainerHighest)
                cornerRadius = if (selected) ctx.dp(20f) else ctx.dp(12f)
            }, ctx.dpi(4f))
            v.visibility = if (drawerOpen || selected) View.VISIBLE else View.INVISIBLE
            if (!drawerOpen && selected) {
                // Closed drawer: the selected mode sits right above the slider.
                v.translationY = (ringerButtons.size - 1 - ringerButtons.indexOfFirst { it.first == mode }) * btn
            } else v.translationY = 0f
        }
        ringerBg.visibility = if (drawerOpen) View.VISIBLE else View.INVISIBLE
        (bg.background as GradientDrawable).cornerRadii = if (drawerOpen) floatArrayOf(
            bgSmallRadius, bgSmallRadius, bgSmallRadius, bgSmallRadius, bgRadius, bgRadius, bgRadius, bgRadius,
        ) else FloatArray(8) { bgRadius }
    }

    private fun setDrawer(open: Boolean) { drawerOpen = open; refreshRinger() }

    private fun onRinger(mode: Int) {
        resetTimeout()
        if (!drawerOpen) { setDrawer(true); return }
        if (mode != am.ringerMode) {
            try { am.ringerMode = mode } catch (_: SecurityException) {
                Shell.run("cmd audio set-ringer-mode " + when (mode) {
                    AudioManager.RINGER_MODE_SILENT -> "SILENT"; AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"; else -> "NORMAL"
                })
            }
            try {
                vib?.vibrate(VibrationEffect.createPredefined(
                    if (mode == AudioManager.RINGER_MODE_VIBRATE) VibrationEffect.EFFECT_DOUBLE_CLICK else VibrationEffect.EFFECT_CLICK))
            } catch (_: Exception) { }
        }
        main.postDelayed({ setDrawer(false) }, 120)
    }

    private fun openVolumeSettings() {
        dismiss()
        val i = Intent(Settings.Panel.ACTION_VOLUME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {
            try { ctx.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
    }

    /** Diagnostics for the stress test. */
    fun stream() = stream

    // ---- slider --------------------------------------------------------------------------------

    /** Vertical M3 Expressive slider as in VolumeDialogSliderTrack (value grows upwards). */
    inner class VolumeSlider(ctx: Context) : View(ctx) {
        var pal = this@VolumePanel.pal
        var icon: Drawable? = null
        var onUser: ((Int) -> Unit)? = null
        var dragging = false
            private set
        private var min = 0; private var max = 15
        private var value = 0f
        private val track = ctx.dp(40f)
        private val outer = ctx.dp(12f)
        private val inner = ctx.dp(2f)
        private val gap = ctx.dp(6f)
        private val thumbW = ctx.dp(52f); private val thumbH = ctx.dp(4f)
        private val iconSize = ctx.dp(20f)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val r = RectF()
        private var anim: android.animation.ValueAnimator? = null

        fun setRange(a: Int, b: Int) { min = a; max = b.coerceAtLeast(a + 1) }
        fun setValue(v: Int) {
            val target = v.toFloat()
            anim?.cancel()
            if (!isShowing || !isAttachedToWindow) { value = target; invalidate(); return }
            anim = android.animation.ValueAnimator.ofFloat(value, target).apply {
                duration = 150
                addUpdateListener { value = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        private fun frac() = ((value - min) / (max - min)).coerceIn(0f, 1f)
        private fun thumbY() = height - (thumbH / 2f + frac() * (height - thumbH))

        private fun roundRect(l: Float, t: Float, rr: Float, b: Float, topR: Float, bottomR: Float, color: Int) {
            if (b - t <= 0f) return
            r.set(l, t, rr, b)
            path.reset()
            path.addRoundRect(r, floatArrayOf(topR, topR, topR, topR, bottomR, bottomR, bottomR, bottomR), Path.Direction.CW)
            paint.color = color
            canvasRef?.drawPath(path, paint)
        }
        private var canvasRef: Canvas? = null

        override fun onDraw(c: Canvas) {
            canvasRef = c
            val l = (width - track) / 2f; val rr = l + track
            val ty = thumbY()
            // active (bottom) and inactive (top) track with a 6 dp gap around the 4 dp thumb
            roundRect(l, ty + thumbH / 2f + gap, rr, height.toFloat(), inner, outer, pal.primary)
            roundRect(l, 0f, rr, ty - thumbH / 2f - gap, outer, inner, pal.surfaceContainerHighest)
            paint.color = pal.primary
            r.set((width - thumbW) / 2f, ty - thumbH / 2f, (width + thumbW) / 2f, ty + thumbH / 2f)
            c.drawRoundRect(r, thumbH / 2f, thumbH / 2f, paint)
            // icon: at the top end of the active track when it fits, else at the top of the inactive track
            icon?.let { d ->
                val activeLen = height - (ty + thumbH / 2f + gap)
                val onActive = activeLen >= track
                val cy = if (onActive) ty + thumbH / 2f + gap + track / 2f else track / 2f
                d.setTint(if (onActive) pal.surfaceContainerHighest else pal.onSecondaryContainer)
                d.setBounds((width / 2f - iconSize / 2f).toInt(), (cy - iconSize / 2f).toInt(),
                    (width / 2f + iconSize / 2f).toInt(), (cy + iconSize / 2f).toInt())
                d.draw(c)
            }
            canvasRef = null
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    dragging = true; parent.requestDisallowInterceptTouchEvent(true)
                    val f = ((height - thumbH / 2f - e.y) / (height - thumbH)).coerceIn(0f, 1f)
                    anim?.cancel()
                    value = min + f * (max - min)
                    val step = Math.round(value)
                    onUser?.invoke(step)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    value = Math.round(value).toFloat(); invalidate()
                }
            }
            return true
        }
    }
}
