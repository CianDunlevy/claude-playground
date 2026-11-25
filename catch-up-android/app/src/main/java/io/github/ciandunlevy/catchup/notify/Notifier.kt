package io.github.ciandunlevy.catchup.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.ciandunlevy.catchup.R
import io.github.ciandunlevy.catchup.data.Prefs
import io.github.ciandunlevy.catchup.data.Store
import io.github.ciandunlevy.catchup.model.Logic
import io.github.ciandunlevy.catchup.model.Person
import io.github.ciandunlevy.catchup.ui.MainActivity

/**
 * The app's only notification: "who's overdue". It exists only while someone is overdue, so the
 * app icon shows a dot exactly then. It buzzes once a day at most (at the chosen time) and only
 * when someone new has become overdue; otherwise it updates silently and clears itself when you catch up.
 */
object Notifier {
    private const val CH_OVERDUE = "overdue"
    private const val OVERDUE_ID = 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // Channels from earlier versions ("caught up" confirmations and the old reminder).
        nm.deleteNotificationChannel("logged")
        nm.deleteNotificationChannel("reminders")
        nm.createNotificationChannel(
            NotificationChannel(CH_OVERDUE, "Overdue", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shown only while someone is overdue"
                setShowBadge(true)
            },
        )
    }

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Brings the overdue notification in line with the current list.
     * [alert] is true only for the daily check; it's the one time a new notification may buzz.
     */
    @Synchronized
    fun syncOverdue(ctx: Context, alert: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val overdue = Logic.sorted(Store.people.value, now).filter { Logic.status(it, now).left < 0 }
        val ids = overdue.map { it.id }.toSet()
        if (overdue.isEmpty() || !Prefs.remindersOn || !canNotify(ctx)) {
            nm.cancel(OVERDUE_ID)
            Prefs.notifiedOverdue = Prefs.notifiedOverdue.intersect(ids)
            return
        }
        val showing = runCatching { nm.activeNotifications.any { it.id == OVERDUE_ID } }.getOrDefault(false)
        val someoneNew = (ids - Prefs.notifiedOverdue).isNotEmpty()
        when {
            alert && someoneNew -> { post(ctx, nm, overdue, now, silent = false); Prefs.notifiedOverdue = ids }
            showing -> { post(ctx, nm, overdue, now, silent = true); Prefs.notifiedOverdue = Prefs.notifiedOverdue.intersect(ids) }
            // Not showing (dismissed, or not yet alert time): leave it. People who stopped being overdue can alert again later.
            else -> Prefs.notifiedOverdue = Prefs.notifiedOverdue.intersect(ids)
        }
    }

    private fun post(ctx: Context, nm: NotificationManager, overdue: List<Person>, now: Long, silent: Boolean) {
        fun days(p: Person) = Logic.plural(-Logic.status(p, now).left, "day")
        val title = if (overdue.size == 1) "${overdue[0].name} is ${days(overdue[0])} overdue"
        else "${overdue.size} people overdue"
        val style = NotificationCompat.InboxStyle().also { s -> overdue.take(6).forEach { s.addLine("${it.name} · ${days(it)} overdue") } }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_OVERDUE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF3540A0.toInt())
            .setContentTitle(title)
            .setContentText(overdue.take(4).joinToString(", ") { it.name })
            .setStyle(if (overdue.size > 1) style else null)
            .setNumber(overdue.size)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setShowWhen(false)
            .build()
        runCatching { nm.notify(OVERDUE_ID, n) }
    }
}

/** Handles taps on names in the widget: logs a catch-up without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TAP = "io.github.ciandunlevy.catchup.TAP"
        const val EXTRA_PERSON = "person"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TAP) return
        val p = intent.getStringExtra(EXTRA_PERSON)?.let { Store.find(it) } ?: return
        Store.upsert(Logic.logCatchUp(p, System.currentTimeMillis()))
    }
}
