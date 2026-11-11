package se.rob.pixelpanel

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the latest crash so it can be shown in the app and sent to Claude. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun write(ctx: Context, where: String, t: Throwable) {
        try {
            val sw = StringWriter(); t.printStackTrace(PrintWriter(sw))
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            file(ctx).writeText(
                "v${BuildConfig.VERSION_CODE} · $time · $where · ${Build.MODEL} Android ${Build.VERSION.RELEASE}\n" +
                    sw.toString().lineSequence().take(40).joinToString("\n")
            )
        } catch (_: Throwable) { }
    }

    fun read(ctx: Context): String? = try { file(ctx).takeIf { it.exists() }?.readText() } catch (_: Throwable) { null }
    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Throwable) { } }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            CrashLog.write(this, "krasch i ${th.name}", e)
            prev?.uncaughtException(th, e)
        }
    }
}
