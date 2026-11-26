package io.nekohasekai.sfa.artvpn

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ArtAutoProfileWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        ArtAutoProfileManager.installOrRefresh(applicationContext, force = false)
        Result.success()
    } catch (error: Exception) {
        Result.retry()
    }
}
