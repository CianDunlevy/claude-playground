package io.github.ciandunlevy.catchup.calls

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.BundleCompat
import io.github.ciandunlevy.catchup.data.Prefs
import io.github.ciandunlevy.catchup.data.Store
import io.github.ciandunlevy.catchup.model.CallTracker
import io.github.ciandunlevy.catchup.model.Logic
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Watches WhatsApp, Messenger and Instagram for their "call in progress" notification and times the call
 * itself (see [CallTracker]). When a call ends, if it lasted at least the minimum and the caller's name
 * matches someone, their catch-up is logged. Every other notification is ignored straight away.
 */
class CallNotificationListener : NotificationListenerService() {

    companion object {
        val APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp",
            "com.facebook.orca" to "Messenger",
            "com.facebook.mlite" to "Messenger",
            "com.instagram.android" to "Instagram",
        )
        private const val GRACE_MS = 8_000L

        fun isEnabled(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        fun component(ctx: Context) = ComponentName(ctx, CallNotificationListener::class.java)

        private val tracker = CallTracker(GRACE_MS)
        private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

        /** Adds a line to the short detection log shown in Settings (last 30 lines, call notifications only). */
        fun log(line: String) {
            Prefs.callLog = (listOf("${LocalTime.now().format(time)} $line") + Prefs.callLog).take(30)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        // Pick up a call that was already going when the service (re)started.
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = APPS[sbn.packageName] ?: return
        if (!Prefs.autoApps) return
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val callType = extras.getInt("android.callType", 0)
        val showsTimer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isCall = Logic.isOngoingCall(n.category, ongoing, callType, showsTimer, text)
        val tracked = tracker.isTracking(sbn.key)

        // Only call-related notifications are looked at further or logged; messages are ignored.
        if (!isCall) {
            if (tracked) {
                log("$app call notification changed to not-a-call (type=$callType timer=${yes(showsTimer)} ongoing=${yes(ongoing)} \"$text\")")
                tracker.removed(sbn.key, System.currentTimeMillis())
                scheduleCheck()
            } else if (n.category == Notification.CATEGORY_CALL || callType != 0) {
                log("$app call notification ignored (type=$callType timer=${yes(showsTimer)} ongoing=${yes(ongoing)} \"$text\")")
            }
            return
        }

        var name: String? = null
        var number: String? = null
        if (Build.VERSION.SDK_INT >= 28) {
            BundleCompat.getParcelable(extras, "android.callPerson", android.app.Person::class.java)?.let { person ->
                name = person.name?.toString()
                number = person.uri?.takeIf { it.startsWith("tel:") }?.removePrefix("tel:")
            }
        }
        val who = name?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: return

        // The call timer appears once the other person answers. (Android's "ongoing call" style is also used
        // while an outgoing call is still ringing, so it doesn't count as answered on its own.)
        val connected = showsTimer
        if (!tracked) log("$app call with $who (type=$callType timer=${yes(showsTimer)} \"$text\")")
        tracker.posted(sbn.key, app, who, number, connected, System.currentTimeMillis())
        Prefs.lastCallSeen = "$app call with $who in progress"
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (APPS[sbn.packageName] == null || !tracker.isTracking(sbn.key)) return
        tracker.removed(sbn.key, System.currentTimeMillis())
        scheduleCheck()
    }

    private fun scheduleCheck() {
        handler.postDelayed({ handleFinished() }, GRACE_MS + 500)
    }

    private fun handleFinished() {
        for (call in tracker.finished(System.currentTimeMillis())) {
            val person = Logic.personForCall(Store.people.value, call.name, call.number)
            val long = call.seconds >= Prefs.minCallSeconds
            val outcome = when {
                person == null -> "no match. Add \"${call.name}\" as another name on someone."
                !long -> "matched ${person.name}, but shorter than your ${Prefs.minCallSeconds / 60} min minimum."
                else -> "logged for ${person.name}."
            }
            Prefs.lastCallSeen = "${call.app} call with ${call.name} · ${formatSeconds(call.seconds)} · $outcome"
            log("${call.app} call with ${call.name} ended after ${formatSeconds(call.seconds)}: $outcome")
            if (person != null && long) Store.upsert(Logic.logCatchUp(person, call.start))
        }
    }

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    private fun formatSeconds(s: Long) = if (s < 60) "$s s" else CallScanner.formatDuration(s)
}
