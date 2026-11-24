package io.github.ciandunlevy.catchup.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Reads and writes the shared backup format (same as the web app, plus an optional "phones" list). */
object Backup {
    const val FORMAT = "catch-up-timer"
    const val VERSION = 1

    data class ParseResult(val people: List<Person>, val skipped: Int)

    fun toJson(people: List<Person>, pretty: Boolean = true): String {
        val arr = JSONArray()
        for (p in people) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("intervalDays", p.intervalDays)
                put("note", p.note)
                put("lastCaughtUp", p.lastCaughtUp)
                put("history", JSONArray(p.history))
                put("createdAt", p.createdAt)
                if (p.phones.isNotEmpty()) put("phones", JSONArray(p.phones))
            })
        }
        val root = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", Instant.now().toString())
            put("people", arr)
        }
        return if (pretty) root.toString(2) else root.toString()
    }

    /** Accepts a full backup object or a bare array of people. Throws IllegalArgumentException with a readable message. */
    fun parse(text: String): ParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("The backup is empty.")
        val list: JSONArray = try {
            if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray("people")
                ?: throw IllegalArgumentException("No \"people\" list found in that backup.")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("That isn't valid backup text. Check you copied all of it.")
        }
        val out = mutableListOf<Person>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            cleanPerson(o)?.let { out += it }
        }
        return ParseResult(out, list.length() - out.size)
    }

    private fun toMs(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> v.trim().takeIf { it.isNotEmpty() }?.let { s ->
            s.toLongOrNull()
                ?: runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        }
        else -> null
    }

    private fun cleanPerson(o: JSONObject): Person? {
        val name = o.optString("name", "").trim().take(60)
        val interval = o.opt("intervalDays").let { (it as? Number)?.toDouble() ?: (it as? String)?.toDoubleOrNull() }
            ?.let { Math.round(it).toInt() } ?: return null
        // "lastContact" is what the other web version called this field.
        val last = toMs(o.opt("lastCaughtUp")) ?: toMs(o.opt("lastContact")) ?: return null
        if (name.isEmpty() || interval !in 1..Logic.MAX_INTERVAL) return null
        val history = mutableListOf<Long>()
        o.optJSONArray("history")?.let { arr -> for (i in 0 until arr.length()) toMs(arr.opt(i))?.let { history += it } }
        if (last !in history) history += last
        val phones = mutableListOf<String>()
        o.optJSONArray("phones")?.let { arr -> for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { phones += it } }
        return Person(
            id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() },
            name = name,
            intervalDays = interval,
            note = o.optString("note", "").take(200),
            lastCaughtUp = last,
            history = history.distinct().sortedDescending().take(Logic.HISTORY_MAX),
            createdAt = toMs(o.opt("createdAt")) ?: last,
            phones = phones,
        )
    }
}
