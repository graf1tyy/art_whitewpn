package com.artvpn.app.data
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
class RefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        NodeRepository(applicationContext).refresh()
        Result.success()
    }.getOrElse { Result.retry() }
}
