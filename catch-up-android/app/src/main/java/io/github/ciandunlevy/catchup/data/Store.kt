package io.github.ciandunlevy.catchup.data

import android.content.Context
import android.content.SharedPreferences
import io.github.ciandunlevy.catchup.model.Backup
import io.github.ciandunlevy.catchup.model.Person
import io.github.ciandunlevy.catchup.widget.CatchUpWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** The list of people, saved as a backup-format JSON file in the app's private storage. */
object Store {
    private lateinit var app: Context
    private lateinit var file: File
    private val _people = MutableStateFlow<List<Person>>(emptyList())
    val people: StateFlow<List<Person>> = _people

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "people.json")
        _people.value = runCatching { if (file.exists()) Backup.parse(file.readText()).people else emptyList() }
            .getOrDefault(emptyList())
    }

    fun find(id: String): Person? = _people.value.firstOrNull { it.id == id }

    @Synchronized
    fun update(transform: (List<Person>) -> List<Person>) {
        val next = transform(_people.value)
        if (next == _people.value) return
        _people.value = next
        val tmp = File(app.filesDir, "people.json.tmp")
        tmp.writeText(Backup.toJson(next, pretty = false))
        tmp.renameTo(file)
        CatchUpWidget.updateAll(app)
    }

    fun upsert(p: Person) = update { list ->
        if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p
    }

    fun delete(id: String) = update { list -> list.filterNot { it.id == id } }
}

/** Small settings, plus bookkeeping for call detection. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var autoPhone: Boolean
        get() = sp.getBoolean("autoPhone", false)
        set(v) = sp.edit().putBoolean("autoPhone", v).apply()

    /** Calls before this moment are logged quietly (no notification) when detection is first turned on. */
    var autoEnabledAt: Long
        get() = sp.getLong("autoEnabledAt", 0L)
        set(v) = sp.edit().putLong("autoEnabledAt", v).apply()

    var minCallSeconds: Int
        get() = sp.getInt("minCallSeconds", 180)
        set(v) = sp.edit().putInt("minCallSeconds", v).apply()

    var remindersOn: Boolean
        get() = sp.getBoolean("remindersOn", true)
        set(v) = sp.edit().putBoolean("remindersOn", v).apply()

    var reminderHour: Int
        get() = sp.getInt("reminderHour", 10)
        set(v) = sp.edit().putInt("reminderHour", v).apply()

    var askedNotifications: Boolean
        get() = sp.getBoolean("askedNotifications", false)
        set(v) = sp.edit().putBoolean("askedNotifications", v).apply()

    var hintDone: Boolean
        get() = sp.getBoolean("hintDone", false)
        set(v) = sp.edit().putBoolean("hintDone", v).apply()

    /** Call-log entries you undid, so the scanner never re-applies them. */
    var ignoredCallIds: Set<String>
        get() = sp.getStringSet("ignoredCallIds", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("ignoredCallIds", v.sortedByDescending { it.toLongOrNull() ?: 0 }.take(300).toSet()).apply()
}
