package io.github.ciandunlevy.catchup.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.ciandunlevy.catchup.calls.CallScanner
import io.github.ciandunlevy.catchup.data.Prefs
import io.github.ciandunlevy.catchup.notify.Notifier
import io.github.ciandunlevy.catchup.widget.CatchUpWidget
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object Work {
    private const val SCAN_NOW = "scan-now"
    private const val SCAN_PERIODIC = "scan-periodic"
    private const val REMINDER = "daily-reminder"

    fun scheduleAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        // Backstop in case a phone call's end was missed, and keeps the widget's day counts fresh.
        // Calls are normally caught the moment they end, so this only needs to run a few times a day.
        // Android batches it with other apps' jobs and holds it during Doze.
        wm.enqueueUniquePeriodicWork(
            SCAN_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ScanWorker>(4, TimeUnit.HOURS).build(),
        )
        scheduleReminder(ctx)
    }

    fun scanSoon(ctx: Context, delaySeconds: Long = 0) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            SCAN_NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScanWorker>().setInitialDelay(delaySeconds, TimeUnit.SECONDS).build(),
        )
    }

    fun scheduleReminder(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!Prefs.remindersOn) { wm.cancelUniqueWork(REMINDER); return }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(Prefs.reminderHour, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMinutes()
        wm.enqueueUniquePeriodicWork(
            REMINDER, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .build(),
        )
    }
}

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        CallScanner.scan(applicationContext)
        CatchUpWidget.updateAll(applicationContext)
        Notifier.syncOverdue(applicationContext, alert = false)
        return Result.success()
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        CallScanner.scan(applicationContext)
        CatchUpWidget.updateAll(applicationContext)
        Notifier.syncOverdue(applicationContext, alert = true)
        return Result.success()
    }
}
