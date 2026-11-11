package se.rob.pixelpanel

import android.view.Choreographer
import java.util.Locale

/** Measures frame pacing while the shade moves, and finger-to-frame latency. */
class FrameMeter(private val refreshHz: Float) {
    private val deltas = ArrayList<Long>(512)
    private var last = 0L
    private var running = false
    private var pendingEventUptimeMs = 0L
    var latencyMs = -1.0
        private set

    private val cb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (pendingEventUptimeMs > 0) {
                // Choreographer and MotionEvent both use CLOCK_MONOTONIC.
                latencyMs = frameTimeNanos / 1e6 - pendingEventUptimeMs
                pendingEventUptimeMs = 0
            }
            if (last > 0) deltas.add(frameTimeNanos - last)
            last = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start(eventUptimeMs: Long) {
        if (running) return
        deltas.clear(); last = 0; latencyMs = -1.0
        pendingEventUptimeMs = eventUptimeMs
        running = true
        Choreographer.getInstance().postFrameCallback(cb)
    }

    /** Returns a short Swedish summary, or null if too few frames were seen. */
    fun stop(): String? {
        if (!running) return null
        running = false
        Choreographer.getInstance().removeFrameCallback(cb)
        if (deltas.size < 5) return null
        val budget = 1e9 / refreshHz
        val avg = deltas.average()
        val worst = deltas.max()
        val dropped = deltas.count { it > budget * 1.5 }
        val l = Locale("sv", "SE")
        val lat = if (latencyMs >= 0) String.format(l, " · finger→bild ≈ %.0f ms", latencyMs) else ""
        return String.format(
            l, "%.0f fps snitt (mål %.0f) · värsta %.1f ms · tappade %d av %d%s",
            1e9 / avg, refreshHz, worst / 1e6, dropped, deltas.size, lat
        )
    }
}
