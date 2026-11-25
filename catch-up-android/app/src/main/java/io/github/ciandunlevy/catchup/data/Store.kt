package io.github.ciandunlevy.catchup.data

import android.content.Context
import android.content.SharedPreferences
import io.github.ciandunlevy.catchup.model.Backup
import io.github.ciandunlevy.catchup.model.Person
import io.github.ciandunlevy.catchup.notify.Notifier
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
        Notifier.syncOverdue(app, alert = false)
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


    /** Detect WhatsApp / Messenger / Instagram calls from their notifications. */
    var autoApps: Boolean
        get() = sp.getBoolean("autoApps", false)
        set(v) = sp.edit().putBoolean("autoApps", v).apply()

    /** Plain-language description of the last messaging-app call seen, shown in Settings to help matching. */
    var lastCallSeen: String
        get() = sp.getString("lastCallSeen", "") ?: ""
        set(v) = sp.edit().putString("lastCallSeen", v).apply()

    /** Recent call-notification events (newest first), shown in Settings to help tune detection. */
    var callLog: List<String>
        get() = sp.getString("callLog", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = sp.edit().putString("callLog", v.joinToString("\n")).apply()

    /** People already included in an overdue alert, so the same overdue people don't buzz you twice. */
    var notifiedOverdue: Set<String>
        get() = sp.getStringSet("notifiedOverdue", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("notifiedOverdue", v).apply()

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

}
