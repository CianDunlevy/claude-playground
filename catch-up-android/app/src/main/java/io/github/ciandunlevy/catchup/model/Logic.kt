package io.github.ciandunlevy.catchup.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One person. Field names match the web app's backup format so files move between the two. */
data class Person(
    val id: String,
    val name: String,
    val intervalDays: Int,
    val note: String = "",
    val lastCaughtUp: Long,
    val history: List<Long> = listOf(lastCaughtUp),
    val createdAt: Long = lastCaughtUp,
    val phones: List<String> = emptyList(),
    /** Other names this person shows up as in WhatsApp / Messenger / Instagram call notifications. */
    val aliases: List<String> = emptyList(),
)

enum class Level { OK, SOON, OVER }

data class Status(val elapsed: Int, val left: Int, val level: Level, val progress: Float)

object Logic {
    const val HISTORY_MAX = 30
    const val MAX_INTERVAL = 3650

    fun localDate(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** Whole calendar days between two instants in the phone's time zone. DST never shifts the count. */
    fun daysBetween(from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        ChronoUnit.DAYS.between(localDate(from, zone), localDate(to, zone)).toInt()

    fun status(p: Person, now: Long, zone: ZoneId = ZoneId.systemDefault()): Status {
        val elapsed = max(0, daysBetween(p.lastCaughtUp, now, zone))
        val left = p.intervalDays - elapsed
        val soonWindow = (p.intervalDays * 0.2).roundToInt().coerceIn(1, 7)
        val level = when {
            left < 0 -> Level.OVER
            left <= soonWindow -> Level.SOON
            else -> Level.OK
        }
        return Status(elapsed, left, level, min(1f, elapsed.toFloat() / p.intervalDays))
    }

    /** Most overdue first, then by name. */
    fun sorted(people: List<Person>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Person> =
        people.sortedWith(compareBy<Person>({ status(it, now, zone).left }, { it.name.lowercase() }))

    /**
     * Records a catch-up at [at]. History keeps one entry per calendar day, newest first.
     * lastCaughtUp only moves forward, so logging an older call adds history without making someone "more due".
     */
    fun logCatchUp(p: Person, at: Long, zone: ZoneId = ZoneId.systemDefault()): Person {
        val day = localDate(at, zone)
        val sameDay = p.history.firstOrNull { localDate(it, zone) == day }
        val history = if (sameDay != null && sameDay >= at) p.history
        else (listOf(at) + p.history.filter { localDate(it, zone) != day }).sortedDescending().take(HISTORY_MAX)
        return p.copy(lastCaughtUp = max(p.lastCaughtUp, at), history = history)
    }

    fun intervalLabel(days: Int): String = when (days) {
        1 -> "every day"
        7 -> "every week"
        14 -> "every 2 weeks"
        30 -> "every month"
        90 -> "every 3 months"
        else -> "every $days days"
    }

    fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

    // ---------- Phone numbers ----------

    /** Digits only, international "00" prefix dropped. */
    fun normalizeNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.startsWith("00")) digits.drop(2) else digits
    }

    /**
     * Compares the last 9 digits so "+353 87 123 4567", "00353871234567" and "087 123 4567" all match.
     * Short numbers (under 6 digits) must match exactly.
     */
    fun numbersMatch(a: String, b: String): Boolean {
        val na = normalizeNumber(a)
        val nb = normalizeNumber(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na.length < 6 || nb.length < 6) return na == nb
        return na.takeLast(9) == nb.takeLast(9)
    }

    fun personForNumber(people: List<Person>, number: String): Person? =
        people.firstOrNull { p -> p.phones.any { numbersMatch(it, number) } }

    // ---------- Names (WhatsApp / Messenger / Instagram calls) ----------

    /** Lowercase letters and digits only, single spaces: "Mam ❤️" -> "mam", "Mary-Kate O'Neill" -> "mary kate o neill". */
    fun normalizeName(raw: String): String =
        raw.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim().replace(Regex("\\s+"), " ")

    private fun labels(p: Person) = (listOf(p.name) + p.aliases).map { normalizeName(it) }.filter { it.isNotEmpty() }

    /**
     * Finds who a call notification is about. Exact name or alias match first. Otherwise a first-name style
     * match ("Dave" vs "Dave Smith"), but only when exactly one person fits, so two Daves never get mixed up.
     * A phone number in the notification (some apps include one) is tried first.
     */
    fun personForCall(people: List<Person>, name: String, number: String? = null): Person? {
        if (!number.isNullOrBlank()) personForNumber(people, number)?.let { return it }
        val n = normalizeName(name)
        if (n.isEmpty()) return null
        val exact = people.filter { n in labels(it) }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        val loose = people.filter { p -> labels(p).any { l -> n.startsWith("$l ") || l.startsWith("$n ") } }
        return loose.singleOrNull()
    }

    /**
     * Decides whether a messaging app's notification is a call in progress (not ringing, not a missed call).
     * [callType] is Android's CallStyle type: 1 incoming, 2 ongoing, 3 screening, 0 when the app doesn't use CallStyle.
     */
    fun isOngoingCall(category: String?, ongoingFlag: Boolean, callType: Int, showsTimer: Boolean, text: String): Boolean {
        if (callType == 2) return true
        if (callType == 1 || callType == 3) return false
        if (!ongoingFlag) return false
        if (category == "call" && showsTimer) return true
        val t = text.lowercase()
        val sounds = Regex("ongoing|in progress|on call|connected").containsMatchIn(t)
        return sounds && (category == "call" || "call" in t || "video" in t)
    }

    // ---------- Merge ----------

    data class MergeResult(val people: List<Person>, val added: Int, val updated: Int)

    /** Adds new people by id; for people in both, keeps the newer details and combines history. */
    fun merge(current: List<Person>, incoming: List<Person>): MergeResult {
        val out = current.toMutableList()
        var added = 0
        var updated = 0
        for (inc in incoming) {
            val i = out.indexOfFirst { it.id == inc.id }
            if (i < 0) { out += inc; added++; continue }
            val cur = out[i]
            val newer = if (inc.lastCaughtUp > cur.lastCaughtUp) inc else cur
            out[i] = cur.copy(
                name = newer.name,
                intervalDays = newer.intervalDays,
                note = newer.note,
                lastCaughtUp = max(cur.lastCaughtUp, inc.lastCaughtUp),
                history = (cur.history + inc.history).distinct().sortedDescending().take(HISTORY_MAX),
                phones = (cur.phones + inc.phones).distinctBy { normalizeNumber(it) },
                aliases = (cur.aliases + inc.aliases).distinctBy { normalizeName(it) },
            )
            updated++
        }
        return MergeResult(out, added, updated)
    }
}
