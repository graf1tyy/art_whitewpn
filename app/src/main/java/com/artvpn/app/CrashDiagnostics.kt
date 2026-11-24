package com.artvpn.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

/**
 * Saves VPN startup stages and a fatal exception so the next launch can
 * display a copyable diagnostic report after the process has terminated.
 */
object CrashDiagnostics {
    private const val PREFS = "artvpn_crash_diagnostics"
    private const val KEY_STAGE = "last_stage"
    private const val KEY_CRASH = "last_crash"
    private const val KEY_TIME = "last_stage_time"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val trace = StringWriter().also { writer ->
                error.printStackTrace(PrintWriter(writer))
            }.toString()
            val report = buildString {
                appendLine("ART VPN fatal exception")
                appendLine("Thread: " + thread.name)
                appendLine("Android SDK: " + Build.VERSION.SDK_INT)
                appendLine("Device: " + Build.MANUFACTURER + " " + Build.MODEL)
                appendLine("Stage: " + stage(app))
                appendLine()
                append(trace)
            }
            runCatching {
                File(app.filesDir, "artvpn-last-crash.txt").writeText(report)
                prefs(app).edit()
                    .putString(KEY_CRASH, report)
                    .putString(KEY_STAGE, "CRASH:" + error.javaClass.simpleName)
                    .putLong(KEY_TIME, System.currentTimeMillis())
                    .commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun mark(context: Context, value: String) {
        runCatching {
            prefs(context).edit()
                .putString(KEY_STAGE, value.take(240))
                .putLong(KEY_TIME, System.currentTimeMillis())
                .commit()
        }
    }

    fun previousFailureSummary(context: Context): String? {
        val savedCrash = prefs(context).getString(KEY_CRASH, null)
        if (!savedCrash.isNullOrBlank()) {
            val firstLines = savedCrash.lineSequence().take(7).joinToString("\n")
            return "Обнаружено аварийное завершение приложения.\n\n" + firstLines
        }
        val lastStage = stage(context)
        if (lastStage.startsWith("RUNNING:") || lastStage.startsWith("FAILED:")) {
            val time = prefs(context).getLong(KEY_TIME, 0L)
            return "Последний запуск подключения оборвался на этапе:\n" + lastStage +
                if (time > 0) "\nВремя: " + time else ""
        }
        return null
    }

    fun buildReport(context: Context): String {
        val savedCrash = prefs(context).getString(KEY_CRASH, null).orEmpty()
        val lastStage = stage(context)
        val time = prefs(context).getLong(KEY_TIME, 0L)
        val logs = readLogcat()
        return buildString {
            appendLine("=== ART VPN DIAGNOSTIC REPORT ===")
            appendLine("Android SDK: " + Build.VERSION.SDK_INT)
            appendLine("Android: " + Build.VERSION.RELEASE)
            appendLine("Device: " + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("Package: " + context.packageName)
            appendLine("Stage: " + lastStage)
            appendLine("Stage time: " + time)
            appendLine()
            appendLine("=== SAVED CRASH ===")
            appendLine(savedCrash.ifBlank { "No Java/Kotlin uncaught exception was saved." })
            appendLine()
            appendLine("=== RELEVANT LOGCAT ===")
            append(logs)
        }.takeLast(24000)
    }

    fun clear(context: Context) {
        runCatching {
            File(context.filesDir, "artvpn-last-crash.txt").delete()
            prefs(context).edit()
                .remove(KEY_CRASH)
                .putString(KEY_STAGE, "IDLE:report-copied")
                .putLong(KEY_TIME, System.currentTimeMillis())
                .apply()
        }
    }

    private fun stage(context: Context): String =
        prefs(context).getString(KEY_STAGE, "UNKNOWN").orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readLogcat(): String {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", "600", "-v", "time")
            )
            val raw = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(4, TimeUnit.SECONDS)
            val relevant = raw.lineSequence().filter { line ->
                line.contains("ARTVPN", ignoreCase = true) ||
                    line.contains("AndroidRuntime", ignoreCase = true) ||
                    line.contains("FATAL EXCEPTION", ignoreCase = true) ||
                    line.contains("Fatal signal", ignoreCase = true) ||
                    (line.contains("DEBUG", ignoreCase = true) && line.contains("crash", ignoreCase = true)) ||
                    line.contains("libbox", ignoreCase = true) ||
                    line.contains("sing-box", ignoreCase = true)
            }.toList()
            (if (relevant.isNotEmpty()) relevant.takeLast(180) else raw.lineSequence().toList().takeLast(100))
                .joinToString("\n")
                .ifBlank { "Logcat did not return readable entries." }
        } catch (error: Throwable) {
            "Unable to read logcat: " + error.javaClass.simpleName + ": " + error.message
        }
    }
}
