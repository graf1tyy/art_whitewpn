package com.artvpn.app
import android.app.Application
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.artvpn.app.data.RefreshWorker
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.util.Locale
import java.util.concurrent.TimeUnit

class ArtVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
        runCatching {
            val root = filesDir.resolve("sing-box").apply { mkdirs() }
            val work = cacheDir.resolve("sing-box").apply { mkdirs() }
            val temp = cacheDir.resolve("sing-box-tmp").apply { mkdirs() }
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
        }.onFailure { Log.e("ARTVPN", "libbox setup failed", it) }
        val refresh = PeriodicWorkRequestBuilder<RefreshWorker>(4, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "artvpn-subscriptions", ExistingPeriodicWorkPolicy.UPDATE, refresh
        )
    }
}
