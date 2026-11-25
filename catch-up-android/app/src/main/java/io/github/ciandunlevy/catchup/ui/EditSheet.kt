package io.github.ciandunlevy.catchup.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ciandunlevy.catchup.data.Prefs
import io.github.ciandunlevy.catchup.data.Store
import io.github.ciandunlevy.catchup.model.Logic
import io.github.ciandunlevy.catchup.model.Person
import io.github.ciandunlevy.catchup.work.Work
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Opens the system contact picker filtered to phone numbers. Needs no contacts permission. */
class PickPhone : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit) =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

private fun readPicked(ctx: Context, uri: Uri): Pair<String, String?>? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { it to c.getString(1) } else null }
}.getOrNull()

private val longDate = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

@Composable
fun SectionLabel(text: String) = Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = LocalPalette.current.fg)

@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 10.dp)) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = p.accent, selectedLabelColor = p.onAccent,
            containerColor = p.bg, labelColor = p.fg,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditSheet(person: Person?, onDismiss: () -> Unit, onMessage: (String) -> Unit, onDeleted: (Person) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(person?.name ?: "") }
    var interval by remember { mutableStateOf((person?.intervalDays ?: 7).toString()) }
    var lastDate by remember { mutableStateOf(person?.let { Logic.localDate(it.lastCaughtUp) } ?: LocalDate.now()) }
    var note by remember { mutableStateOf(person?.note ?: "") }
    val phones = remember { mutableStateListOf<String>().apply { person?.phones?.let { addAll(it) } } }
    var newPhone by remember { mutableStateOf("") }
    val aliases = remember { mutableStateListOf<String>().apply { person?.aliases?.let { addAll(it) } } }
    var newAlias by remember { mutableStateOf("") }
    // Edits are worked out from the person as they were when the sheet opened, so changing your mind is harmless.
    val original = remember { person }
    var error by remember { mutableStateOf<String?>(null) }
    var showDate by remember { mutableStateOf(false) }
    var deleteArmed by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    LaunchedEffect(deleteArmed) { if (deleteArmed) { delay(4000); deleteArmed = false } }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (person == null) { delay(300); runCatching { focus.requestFocus() } } }

    fun addPhone(raw: String) {
        val num = raw.trim()
        if (Logic.normalizeNumber(num).length >= 3 && phones.none { Logic.numbersMatch(it, num) }) phones += num
    }
    val pickPhone = rememberLauncherForActivityResult(PickPhone()) { uri ->
        uri?.let { readPicked(ctx, it) }?.let { (num, contactName) ->
            addPhone(num)
            if (name.isBlank() && !contactName.isNullOrBlank()) name = contactName.take(60)
        }
    }

    fun addAlias(raw: String) {
        val a = raw.trim().take(60)
        if (Logic.normalizeName(a).isNotEmpty() && aliases.none { Logic.normalizeName(it) == Logic.normalizeName(a) }) aliases += a
    }

    /** The person as currently edited, or null (with [error] set) if something's missing. */
    fun build(): Person? {
        val nm = name.trim()
        val iv = interval.toIntOrNull()
        if (nm.isEmpty()) { error = "Enter a name."; return null }
        if (iv == null || iv !in 1..Logic.MAX_INTERVAL) { error = "Pick how often, between 1 and ${Logic.MAX_INTERVAL} days."; return null }
        error = null
        val now = System.currentTimeMillis()
        val dateMs = if (lastDate == LocalDate.now()) now
        else lastDate.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val cleanNote = note.trim().take(200)
        val base = original ?: return Person(UUID.randomUUID().toString(), nm, iv, cleanNote, dateMs, listOf(dateMs), now, phones.toList(), aliases.toList())
        var updated = (Store.find(base.id) ?: base).copy(name = nm, intervalDays = iv, note = cleanNote, phones = phones.toList(), aliases = aliases.toList())
        val origDate = Logic.localDate(base.lastCaughtUp)
        updated = when {
            lastDate == origDate -> updated.copy(lastCaughtUp = base.lastCaughtUp, history = base.history)
            // A later date is a catch-up you forgot to log.
            lastDate.isAfter(origDate) -> updated.copy(
                lastCaughtUp = dateMs,
                history = (listOf(dateMs) + base.history.filter { Logic.localDate(it) != lastDate }).sortedDescending().take(Logic.HISTORY_MAX),
            )
            // An earlier date (e.g. "Make due now") just moves the timer back; it isn't a catch-up.
            else -> updated.copy(lastCaughtUp = dateMs, history = base.history.filter { !Logic.localDate(it).isAfter(lastDate) })
        }
        return updated
    }

    /** Edits to an existing person save themselves. */
    fun persist() {
        if (original == null || deleted) return
        val p = build() ?: return
        if (p == Store.find(p.id)) return
        val phonesChanged = p.phones != Store.find(p.id)?.phones
        Store.upsert(p)
        if (Prefs.autoPhone && phonesChanged) Work.scanSoon(ctx)
    }
    if (original != null) {
        LaunchedEffect(name, interval, lastDate, note, phones.toList(), aliases.toList()) { delay(400); persist() }
    }

    fun close() {
        persist()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun addNew() {
        if (newPhone.isNotBlank()) { addPhone(newPhone); newPhone = "" }
        if (newAlias.isNotBlank()) { addAlias(newAlias); newAlias = "" }
        val p = build() ?: return
        Store.upsert(p)
        if (Prefs.autoPhone && p.phones.isNotEmpty()) Work.scanSoon(ctx)
        onMessage("Added ${p.name}")
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = { persist(); onDismiss() }, sheetState = sheetState, containerColor = p.surface) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (original == null) "Add person" else "Edit ${original.name}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (original != null) Text("Changes save as you go", fontSize = 13.sp, color = p.muted)
                }
                IconButton(onClick = { close() }) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = p.muted) }
            }

            OutlinedTextField(
                value = name, onValueChange = { name = it.take(60); error = null },
                label = { Text("Name") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (original == null) addNew() else close() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )

            SectionLabel("Catch up every")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to "Week", 14 to "2 weeks", 30 to "Month", 90 to "3 months").forEach { (d, l) ->
                    ChoiceChip(l, interval == d.toString()) { interval = d.toString() }
                }
            }
            OutlinedTextField(
                value = interval, onValueChange = { interval = it.filter(Char::isDigit).take(4) },
                label = { Text("Or a custom number of days") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("Last caught up")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val today = LocalDate.now()
                ChoiceChip("Today", lastDate == today) { lastDate = today }
                ChoiceChip("Yesterday", lastDate == today.minusDays(1)) { lastDate = today.minusDays(1) }
                ChoiceChip("A week ago", lastDate == today.minusDays(7)) { lastDate = today.minusDays(7) }
                ChoiceChip("Make due now", false) { lastDate = today.minusDays((interval.toLongOrNull() ?: 7L).coerceAtLeast(1)) }
            }
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
                Text(lastDate.format(longDate), color = p.fg)
            }

            SectionLabel("Phone numbers")
            Text(
                if (Prefs.autoPhone) "Phone calls of ${Prefs.minCallSeconds / 60}+ min with these numbers tick them off automatically."
                else "Add a number, then turn on call detection in Settings to tick them off automatically.",
                fontSize = 13.sp, color = p.muted,
            )
            phones.toList().forEach { num ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(num, modifier = Modifier.weight(1f), fontSize = 16.sp)
                    IconButton(onClick = { phones.remove(num) }) { Icon(Icons.Filled.Close, contentDescription = "Remove $num", tint = p.muted) }
                }
            }
            OutlinedTextField(
                value = newPhone, onValueChange = { newPhone = it.take(30) },
                label = { Text("Add a number") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addPhone(newPhone); newPhone = "" }),
                trailingIcon = {
                    if (newPhone.isNotBlank()) IconButton(onClick = { addPhone(newPhone); newPhone = "" }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add number")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { runCatching { pickPhone.launch(Unit) } }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = p.fg)
                Spacer(Modifier.width(8.dp))
                Text("Pick from contacts", color = p.fg)
            }

            SectionLabel("Other names")
            Text(
                "WhatsApp, Messenger, Instagram and Google Chat calls are matched by name. " +
                    "\"${name.trim().ifEmpty { "Their name" }}\" is used already; add any other name they show up as, like Mary Dunlevy.",
                fontSize = 13.sp, color = p.muted,
            )
            aliases.toList().forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a, modifier = Modifier.weight(1f), fontSize = 16.sp)
                    IconButton(onClick = { aliases.remove(a) }) { Icon(Icons.Filled.Close, contentDescription = "Remove $a", tint = p.muted) }
                }
            }
            OutlinedTextField(
                value = newAlias, onValueChange = { newAlias = it.take(60) },
                label = { Text("Add another name") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addAlias(newAlias); newAlias = "" }),
                trailingIcon = {
                    if (newAlias.isNotBlank()) IconButton(onClick = { addAlias(newAlias); newAlias = "" }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add name")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = note, onValueChange = { note = it.take(200) },
                label = { Text("Note (optional)") }, placeholder = { Text("Ask about the new job") },
                minLines = 2, modifier = Modifier.fillMaxWidth(),
            )

            error?.let { Text(it, color = p.over, fontSize = 14.sp) }

            if (original == null) Button(
                onClick = { addNew() }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
            ) { Text("Add person", fontWeight = FontWeight.Bold, fontSize = 17.sp) }

            if (person != null) {
                if (person.history.isNotEmpty()) {
                    SectionLabel("Recent catch-ups")
                    Column {
                        person.history.forEachIndexed { i, t ->
                            val gap = person.history.getOrNull(i + 1)?.let { "${Logic.daysBetween(it, t)}d gap" } ?: ""
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(Logic.localDate(t).format(longDate), color = p.muted, modifier = Modifier.weight(1f), fontSize = 14.sp)
                                Text(gap, color = p.muted, fontSize = 14.sp)
                            }
                            HorizontalDivider(color = p.line)
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (!deleteArmed) deleteArmed = true
                        else {
                            deleted = true
                            Store.delete(person.id)
                            onDeleted(person)
                            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                    colors = if (deleteArmed) ButtonDefaults.outlinedButtonColors(containerColor = p.over, contentColor = p.surface)
                    else ButtonDefaults.outlinedButtonColors(contentColor = p.over),
                ) { Text(if (deleteArmed) "Tap again to delete" else "Delete person", fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (showDate) {
        val todayUtc = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = lastDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { lastDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}
