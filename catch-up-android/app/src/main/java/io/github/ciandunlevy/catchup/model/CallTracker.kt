package io.github.ciandunlevy.catchup.model

/**
 * Works out how long a WhatsApp / Messenger / Instagram call lasted from its notifications, using our own clock.
 * The apps' own timestamps aren't trusted, and apps often swap notifications mid-call (ringing → connected),
 * so a call is tracked by app + caller name across notifications, and only counts as ended once none of its
 * notifications have been showing for [graceMs].
 */
class CallTracker(private val graceMs: Long = 8_000) {

    data class Finished(val app: String, val name: String, val number: String?, val start: Long, val seconds: Long)

    private class Call(val app: String, val name: String, val number: String?, val firstSeen: Long) {
        var connectedAt: Long? = null
        var endedAt: Long? = null
        val keys = mutableSetOf<String>()
    }

    private val calls = mutableMapOf<String, Call>()
    private val keyToCall = mutableMapOf<String, String>()

    fun isTracking(key: String) = keyToCall.containsKey(key)

    /** A call notification is showing. [connected] is true once the app shows a running call timer. */
    @Synchronized
    fun posted(key: String, app: String, name: String, number: String?, connected: Boolean, now: Long) {
        val id = app + "|" + Logic.normalizeName(name)
        val call = calls.getOrPut(id) { Call(app, name, number, now) }
        call.keys += key
        call.endedAt = null
        if (connected && call.connectedAt == null) call.connectedAt = now
        keyToCall[key] = id
    }

    /** A call notification went away (or stopped being a call). */
    @Synchronized
    fun removed(key: String, now: Long) {
        val id = keyToCall.remove(key) ?: return
        val call = calls[id] ?: return
        call.keys -= key
        if (call.keys.isEmpty()) call.endedAt = now
    }

    /** Calls that have ended, measured from when they connected (or were first seen, if the app shows no timer). */
    @Synchronized
    fun finished(now: Long): List<Finished> {
        val done = calls.filterValues { c -> c.endedAt?.let { now - it >= graceMs } == true }
        done.keys.forEach { calls.remove(it) }
        return done.values.map { c ->
            val start = c.connectedAt ?: c.firstSeen
            Finished(c.app, c.name, c.number, start, (c.endedAt!! - start).coerceAtLeast(0) / 1000)
        }
    }
}
