package se.rob.pixelpanel

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Runs shell-level commands through Shizuku (adb shell privileges, no root).
 * Used for the toggles Android doesn't let normal apps flip: Wi-Fi, Bluetooth,
 * mobile data, airplane mode, battery saver, location, dark theme, NFC.
 */
object Shell {
    const val REQUEST_CODE = 4711
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun binderAlive(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun granted(): Boolean = try {
        binderAlive() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun requestPermission() {
        try { if (binderAlive()) Shizuku.requestPermission(REQUEST_CODE) } catch (_: Throwable) { }
    }

    /** Runs [cmd] with `sh -c` as the shell user. [done] gets true on exit code 0, on the main thread. */
    fun run(cmd: String, done: ((Boolean) -> Unit)? = null) {
        exec.execute {
            val ok = try {
                val p = newProcess(arrayOf("sh", "-c", cmd))
                p.inputStream.readBytes(); p.errorStream.readBytes()
                p.waitFor() == 0
            } catch (_: Throwable) { false }
            if (done != null) main.post { done(ok) }
        }
    }

    // Shizuku.newProcess is hidden in API 13 but still present; it is the simplest way to run a command.
    private fun newProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }
}
