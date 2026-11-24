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
import io.github.ciandunlevy.catchup.data.Store
import io.github.ciandunlevy.catchup.model.Logic
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
        // Hourly backstop for call detection; also keeps the widget's day counts fresh.
        wm.enqueueUniquePeriodicWork(
            SCAN_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScanWorker>(1, TimeUnit.HOURS).build(),
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
        return Result.success()
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        CallScanner.scan(applicationContext)
        CatchUpWidget.updateAll(applicationContext)
        if (Prefs.remindersOn) {
            val now = System.currentTimeMillis()
            val due = Logic.sorted(Store.people.value, now).filter { Logic.status(it, now).left <= 0 }
            if (due.isNotEmpty()) Notifier.reminder(applicationContext, due, now)
        }
        return Result.success()
    }
}
