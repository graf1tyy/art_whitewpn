package com.artvpn.app

import android.app.Application
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.artvpn.app.data.RefreshWorker
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.TimeUnit

class ArtVpnApplication : Application() {
    private val setupLock = Mutex()
    @Volatile private var setupCompleted = false

    /**
     * Mirrors the official sing-box Android lifecycle: setup finishes on an IO
     * coroutine before CommandServer is constructed. A failed setup is surfaced
     * to the service instead of being swallowed during Application.onCreate().
     */
    suspend fun ensureLibboxSetup() = setupLock.withLock {
        if (setupCompleted) return@withLock

        val root = filesDir.resolve("sing-box").apply {
            check(isDirectory || mkdirs()) { "Не удалось создать каталог sing-box" }
        }
        val work = root.resolve("working").apply {
            check(isDirectory || mkdirs()) { "Не удалось создать рабочий каталог sing-box" }
        }
        val temp = cacheDir.resolve("sing-box-tmp").apply {
            check(isDirectory || mkdirs()) { "Не удалось создать временный каталог sing-box" }
        }

        Libbox.setup(SetupOptions().apply {
            basePath = root.absolutePath
            workingPath = work.absolutePath
            tempPath = temp.absolutePath
            // Required by sing-box on Android 9+ (Go runtime issue #68760).
            fixAndroidStack = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            commandServerListenPort = 0
            appVersion = BuildConfig.VERSION_CODE.toString()
            appMarketingVersion = BuildConfig.VERSION_NAME
            crashReportSource = "ART VPN"
            logMaxLines = 3000
        })
        Libbox.setLocale(Locale.getDefault().toLanguageTag())
        setupCompleted = true
    }

    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)

        val refresh = PeriodicWorkRequestBuilder<RefreshWorker>(4, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "artvpn-subscriptions", ExistingPeriodicWorkPolicy.UPDATE, refresh
        )
    }
}
