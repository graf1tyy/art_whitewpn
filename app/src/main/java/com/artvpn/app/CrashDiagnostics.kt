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
        val goCrash = readGoCrashReports(context)
        if (goCrash.isNotBlank()) {
            return "Обнаружен отчёт аварии sing-box (Go runtime).\n\n" +
                goCrash.lineSequence().take(8).joinToString("\n")
        }
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
        val goCrash = readGoCrashReports(context)
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
            appendLine("=== SING-BOX GO CRASH REPORT ===")
            append(goCrash.ifBlank { "No sing-box Go crash report found." })
            appendLine()
            appendLine("=== RELEVANT LOGCAT ===")
            append(logs)
        }.takeLast(65000)
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

    /**
     * sing-box redirects Go stderr to CrashReport-<source>.log and archives the
     * previous run into crash_reports on the next Libbox.setup(). Read both the
     * current file and archived go.log files; Android logcat alone often only
     * exposes the final SIGABRT and a stripped libbox.so address.
     */
    private fun readGoCrashReports(context: Context): String {
        val roots = listOf(
            File(context.filesDir, "sing-box/working"),
            File(context.filesDir, "sing-box"),
            File(context.cacheDir, "sing-box"),
        )
        val files = linkedSetOf<File>()
        for (root in roots) {
            if (!root.exists()) continue
            File(root, "CrashReport-ART VPN.log").takeIf { it.isFile && it.length() > 0 }?.let(files::add)
            File(root, "crash_reports").walkTopDown()
                .filter { it.isFile && it.name == "go.log" && it.length() > 0 }
                .sortedByDescending { it.lastModified() }
                .take(5)
                .forEach(files::add)
        }
        return files.take(6).joinToString("\n\n===== NEXT GO CRASH FILE =====\n\n") { file ->
            val body = runCatching { file.readText() }.getOrDefault("Unable to read " + file.name)
            "FILE: " + file.name + " (" + file.length() + " bytes)\n" + body.takeLast(24000)
        }.takeLast(52000)
    }

    private fun stage(context: Context): String =
        prefs(context).getString(KEY_STAGE, "UNKNOWN").orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readLogcat(): String {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", "1800", "-v", "threadtime")
            )
            val raw = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(5, TimeUnit.SECONDS)
            val lines = raw.lineSequence().toList()

            // Go's fatal/panic header can precede the libbox stack by dozens of
            // lines. Keep a contiguous window around SIGABRT instead of filtering
            // only lines containing "sing-box" and accidentally dropping the cause.
            val fatalIndex = lines.indexOfLast { line ->
                val lower = line.lowercase()
                lower.contains("fatal signal") ||
                    lower.contains("sigabrt") ||
                    lower.contains("fatal error:") ||
                    lower.contains("panic:") ||
                    lower.contains("runtime:") && lower.contains("fatal")
            }
            val selected = if (fatalIndex >= 0) {
                lines.subList(
                    (fatalIndex - 260).coerceAtLeast(0),
                    (fatalIndex + 100).coerceAtMost(lines.size)
                )
            } else {
                lines.filter { line ->
                    line.contains("ARTVPN", ignoreCase = true) ||
                        line.contains("AndroidRuntime", ignoreCase = true) ||
                        line.contains("FATAL EXCEPTION", ignoreCase = true) ||
                        line.contains("Go", ignoreCase = true) ||
                        line.contains("libbox", ignoreCase = true) ||
                        line.contains("sing-box", ignoreCase = true) ||
                        line.contains("DEBUG", ignoreCase = true)
                }.takeLast(360)
            }
            selected.joinToString("\n").ifBlank { "Logcat did not return readable entries." }
        } catch (error: Throwable) {
            "Unable to read logcat: " + error.javaClass.simpleName + ": " + error.message
        }
    }
}

