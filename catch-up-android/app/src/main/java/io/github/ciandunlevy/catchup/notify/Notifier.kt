package io.github.ciandunlevy.catchup.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.ciandunlevy.catchup.R
import io.github.ciandunlevy.catchup.data.Prefs
import io.github.ciandunlevy.catchup.data.Store
import io.github.ciandunlevy.catchup.model.Backup
import io.github.ciandunlevy.catchup.model.Logic
import io.github.ciandunlevy.catchup.model.Person
import io.github.ciandunlevy.catchup.ui.MainActivity
import java.time.format.DateTimeFormatter
import java.util.Locale

object Notifier {
    private const val CH_LOGGED = "logged"
    private const val CH_REMIND = "reminders"
    private const val REMINDER_ID = 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LOGGED, "Automatic catch-ups", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Shown when a call ticks someone off, with an Undo button" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Daily reminder", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Who you're due to catch up with" },
        )
    }

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun notificationId(personId: String) = 1000 + (personId.hashCode() and 0xFFFFF)

    /** "Logged catch-up with Mam" with an Undo button that restores [before]. */
    fun logged(ctx: Context, before: Person, after: Person, how: String, callId: String? = null) {
        if (!canNotify(ctx)) return
        val next = Logic.localDate(after.lastCaughtUp).plusDays(after.intervalDays.toLong())
            .format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        val undo = Intent(ctx, ActionReceiver::class.java).apply {
            action = ActionReceiver.ACTION_UNDO
            data = Uri.parse("catchup://undo/${before.id}")
            putExtra(ActionReceiver.EXTRA_SNAPSHOT, Backup.toJson(listOf(before), pretty = false))
            putExtra(ActionReceiver.EXTRA_CALL_ID, callId)
        }
        val undoPi = PendingIntent.getBroadcast(ctx, notificationId(before.id), undo,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH_LOGGED)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF3540A0.toInt())
            .setContentTitle("Caught up with ${after.name}")
            .setContentText("$how · next due $next")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setTimeoutAfter(6 * 60 * 60 * 1000L)
            .addAction(0, "Undo", undoPi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(notificationId(after.id), n) }
    }

    fun reminder(ctx: Context, due: List<Person>, now: Long) {
        if (!canNotify(ctx)) return
        fun line(p: Person): String {
            val left = Logic.status(p, now).left
            return if (left == 0) "${p.name} · due today" else "${p.name} · ${Logic.plural(-left, "day")} overdue"
        }
        val title = if (due.size == 1) "Time to catch up with ${due[0].name}"
        else "${due.size} people to catch up with"
        val style = NotificationCompat.InboxStyle().also { s -> due.take(6).forEach { s.addLine(line(it)) } }
        val n = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF3540A0.toInt())
            .setContentTitle(title)
            .setContentText(due.take(3).joinToString(", ") { it.name })
            .setStyle(style)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(REMINDER_ID, n) }
    }
}

/** Handles Undo from a notification and taps on names in the widget. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_UNDO = "io.github.ciandunlevy.catchup.UNDO"
        const val ACTION_TAP = "io.github.ciandunlevy.catchup.TAP"
        const val EXTRA_SNAPSHOT = "snapshot"
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_PERSON = "person"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_UNDO -> {
                val before = intent.getStringExtra(EXTRA_SNAPSHOT)
                    ?.let { runCatching { Backup.parse(it).people.firstOrNull() }.getOrNull() } ?: return
                intent.getStringExtra(EXTRA_CALL_ID)?.let { Prefs.ignoredCallIds = Prefs.ignoredCallIds + it }
                if (Store.find(before.id) != null) Store.upsert(before)
                NotificationManagerCompat.from(context).cancel(Notifier.notificationId(before.id))
            }
            ACTION_TAP -> {
                val p = intent.getStringExtra(EXTRA_PERSON)?.let { Store.find(it) } ?: return
                val after = Logic.logCatchUp(p, System.currentTimeMillis())
                Store.upsert(after)
                Notifier.logged(context, p, after, "Logged from widget")
            }
        }
    }
}
