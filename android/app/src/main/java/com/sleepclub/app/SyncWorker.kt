package com.sleepclub.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Фоновая задача WorkManager.
 *  - periodic: страховка раз в ~45 минут (только при наличии сети): отправка, проверка
 *    здоровья, перезапуск сервиса, если его убили, и раз в сутки — проверка обновлений;
 *  - once: одна отправка сразу после выключения экрана (WorkManager сам держит
 *    процессор проснувшимся ровно на время работы и дождётся сети, если её нет).
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_REASON) ?: "periodic"
        val ok = SyncManager.sync(applicationContext, reason)
        if (reason == "periodic") {
            TrackerService.ensureRunning(applicationContext)
            Health.notifyIfBroken(applicationContext)
            runCatching { Updater.checkIfDue(applicationContext) }
        }
        return when {
            ok -> Result.success()
            runAttemptCount < 3 -> Result.retry()
            else -> Result.success() // дальше подхватит следующая периодическая задача
        }
    }

    companion object {
        private const val KEY_REASON = "reason"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(45, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("periodic-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun syncSoon(ctx: Context, reason: String) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_REASON to reason))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sync-once", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
