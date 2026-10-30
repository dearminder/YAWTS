package com.dearminder.yawts

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Installs the crash reporter before anything else in the process runs. */
class YawtsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DevTools.installCrashHandler(this)
    }
}

/**
 * Developer options (unlocked by tapping the version panel in Settings): crash reports and server
 * logs. They go to Documents/YAWTS/logs in shared storage, so they can be fetched from the phone
 * through the web page itself.
 */
object DevTools {
    val logDir: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "YAWTS/logs")

    // Request threads only queue lines; one thread does the (slow, shared-storage) file writes.
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "yawts-log").apply { isDaemon = true } }

    private fun crashReportsOn(context: Context) = context.appPrefs().getBoolean(Prefs.CRASH_REPORTS, false)
    private fun serverLogsOn(context: Context) = context.appPrefs().getBoolean(Prefs.SERVER_LOGS, false)

    fun installCrashHandler(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Written right here - the process dies as soon as the previous handler returns.
            if (crashReportsOn(app)) try { writeCrashReport(app, thread.name, error, fatal = true) } catch (e: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A line in today's server log (e.g. "Server started"), if server logs are on. */
    fun log(context: Context, message: String) {
        if (!serverLogsOn(context)) return
        val line = "${timestamp()} $message\n"
        writer.execute { append(File(logDir, "server-${day()}.log"), line) }
    }

    /** Feeds [HttpServer]'s requests and errors into the server log and crash reports. */
    fun serverLog(context: Context): ServerLog {
        val app = context.applicationContext
        return object : ServerLog {
            override fun request(remote: InetAddress?, method: String, target: String, status: Int, millis: Long) {
                log(app, "${remote?.hostAddress ?: "-"} $method $target $status ${millis}ms")
            }

            override fun error(message: String, error: Throwable?) {
                log(app, "ERROR $message" + (error?.let { "\n" + stackTrace(it) } ?: ""))
                // The server survives these now, but they're exactly what crash reports are for.
                if (error != null && crashReportsOn(app)) {
                    val thread = Thread.currentThread().name
                    writer.execute { try { writeCrashReport(app, thread, error, fatal = false, note = message) } catch (e: Exception) { } }
                }
            }
        }
    }

    private fun writeCrashReport(context: Context, thread: String, error: Throwable, fatal: Boolean, note: String? = null) {
        val version = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "?" }
        val report = buildString {
            append(if (fatal) "YAWTS crash" else "YAWTS server error (caught, the app kept running)").append('\n')
            append("Time: ").append(timestamp()).append('\n')
            append("App: ").append(version).append('\n')
            append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Thread: ").append(thread).append('\n')
            note?.let { append("While: ").append(it).append('\n') }
            append('\n').append(stackTrace(error))
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        val file = File(logDir, "${if (fatal) "crash" else "error"}-$stamp.txt")
        logDir.mkdirs()
        file.writeText(report)
    }

    private fun append(file: File, text: String) {
        try {
            file.parentFile?.mkdirs()
            file.appendText(text)
        } catch (e: Exception) {
            // No storage access or the disk is full - logging must never break the server.
        }
    }

    private fun stackTrace(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    private fun day(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
