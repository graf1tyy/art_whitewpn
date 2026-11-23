package com.artvpn.app.data
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
class RefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        val repository = NodeRepository(applicationContext)
        val fresh = repository.refresh()
        val tested = NodeTester.test(fresh)
        repository.save(tested)
        Result.success()
    }.getOrElse { Result.retry() }
}
