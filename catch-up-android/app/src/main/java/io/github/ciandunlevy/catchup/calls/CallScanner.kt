package io.github.ciandunlevy.catchup.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.TelephonyManager
import io.github.ciandunlevy.catchup.data.Prefs
import io.github.ciandunlevy.catchup.data.Store
import io.github.ciandunlevy.catchup.model.Logic
import io.github.ciandunlevy.catchup.work.Work

/**
 * Reads the last 30 days of the phone's call log and logs a catch-up for any call with a known
 * number that lasted at least the minimum length. Safe to run repeatedly: logging is per day and
 * lastCaughtUp only moves forward, so the same call never counts twice.
 */
object CallScanner {
    private const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    @Synchronized
    fun scan(ctx: Context): Int {
        if (!Prefs.autoPhone || !hasPermission(ctx)) return 0
        if (Store.people.value.none { it.phones.isNotEmpty() }) return 0
        val now = System.currentTimeMillis()
        val minSeconds = Prefs.minCallSeconds
        var logged = 0
        val cursor = runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                "${CallLog.Calls.DATE} > ?",
                arrayOf((now - WINDOW_MS).toString()),
                "${CallLog.Calls.DATE} ASC",
            )
        }.getOrNull() ?: return 0
        cursor.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(1) ?: continue
                val date = c.getLong(2)
                val seconds = c.getLong(3)
                val type = c.getInt(4)
                if (type != CallLog.Calls.INCOMING_TYPE && type != CallLog.Calls.OUTGOING_TYPE) continue
                if (seconds < minSeconds) continue
                val before = Logic.personForNumber(Store.people.value, number) ?: continue
                val after = Logic.logCatchUp(before, date)
                if (after == before) continue
                Store.upsert(after)
                logged++
            }
        }
        return logged
    }

    fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }
}

/** When a call ends, check the call log a few seconds later (Android writes the entry just after). */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) == TelephonyManager.EXTRA_STATE_IDLE && Prefs.autoPhone) {
            Work.scanSoon(context, delaySeconds = 4)
        }
    }
}
