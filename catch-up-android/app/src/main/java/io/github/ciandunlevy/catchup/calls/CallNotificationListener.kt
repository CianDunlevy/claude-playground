package io.github.ciandunlevy.catchup.calls

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.BundleCompat
import io.github.ciandunlevy.catchup.data.Prefs
import io.github.ciandunlevy.catchup.data.Store
import io.github.ciandunlevy.catchup.model.Logic

/**
 * Watches WhatsApp, Messenger and Instagram for their "call in progress" notification. When that
 * notification goes away the call has ended: if it lasted long enough and the caller's name matches
 * someone, their catch-up is logged. Nothing else is read, kept or sent anywhere.
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
        private const val MAX_CALL_MS = 12L * 60 * 60 * 1000

        fun isEnabled(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

        fun component(ctx: Context) = ComponentName(ctx, CallNotificationListener::class.java)

        /** Calls currently in progress, by notification key. */
        private val active = mutableMapOf<String, ActiveCall>()
    }

    private data class ActiveCall(val app: String, val name: String, val number: String?, val start: Long)

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
        if (!Logic.isOngoingCall(n.category, ongoing, callType, showsTimer, text)) return

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

        val now = System.currentTimeMillis()
        synchronized(active) {
            if (active.containsKey(sbn.key)) return
            // While connected, these apps show a call timer whose start time is the notification's "when".
            val start = if (showsTimer && n.`when` in (now - MAX_CALL_MS)..now) n.`when` else now
            active[sbn.key] = ActiveCall(app, who, number, start)
        }
        Prefs.lastCallSeen = "$app call with $who in progress"
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (APPS[sbn.packageName] == null) return
        val call = synchronized(active) { active.remove(sbn.key) } ?: return
        val seconds = (System.currentTimeMillis() - call.start) / 1000
        val person = Logic.personForCall(Store.people.value, call.name, call.number)
        val long = seconds >= Prefs.minCallSeconds
        Prefs.lastCallSeen = "${call.app} call with ${call.name} · ${CallScanner.formatDuration(seconds)} · " + when {
            person == null -> "no match. Add \"${call.name}\" as another name on someone."
            !long -> "matched ${person.name}, but shorter than your minimum."
            else -> "logged for ${person.name}."
        }
        if (person != null && long) Store.upsert(Logic.logCatchUp(person, call.start))
    }
}
