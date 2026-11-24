package io.github.ciandunlevy.catchup.ui

import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ciandunlevy.catchup.data.Prefs
import io.github.ciandunlevy.catchup.data.Store
import io.github.ciandunlevy.catchup.model.Level
import io.github.ciandunlevy.catchup.model.Logic
import io.github.ciandunlevy.catchup.model.Person
import io.github.ciandunlevy.catchup.model.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(onSettings: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val people by Store.people.collectAsState()
    val tick by MainActivity.resumeTick.collectAsState()
    val addRequest by MainActivity.addRequests.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tick) {
        while (true) { now = System.currentTimeMillis(); delay(60_000) }
    }

    var editing by remember { mutableStateOf<String?>(null) } // "" = new person, otherwise an id
    LaunchedEffect(addRequest) {
        if (addRequest) { editing = ""; MainActivity.addRequests.value = false }
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var hintDone by remember { mutableStateOf(Prefs.hintDone) }
    val autoPhone = remember(tick) { Prefs.autoPhone }

    val sorted = remember(people, now) { Logic.sorted(people, now) }
    val statuses = remember(sorted, now) { sorted.associate { it.id to Logic.status(it, now) } }

    fun showUndo(message: String, undo: () -> Unit) {
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            val r = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) undo()
        }
    }

    fun caughtUp(person: Person) {
        val after = Logic.logCatchUp(person, System.currentTimeMillis())
        Store.upsert(after)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (!hintDone) { hintDone = true; Prefs.hintDone = true }
        showUndo("Caught up with ${person.name}") { if (Store.find(person.id) != null) Store.upsert(person) }
    }

    Scaffold(
        containerColor = p.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.bg, scrolledContainerColor = p.bg),
                title = {
                    Column {
                        Text("Catch-Up", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                        Summary(sorted.size, statuses.values)
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = p.muted) }
                },
            )
        },
        floatingActionButton = {
            if (sorted.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = { editing = "" },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add person", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) },
                containerColor = p.accent, contentColor = p.onAccent,
                shape = RoundedCornerShape(50),
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (sorted.isEmpty()) {
            EmptyState(pad, onAdd = { editing = "" }, onPaste = {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                val msg = importBackup(text, replace = false)
                scope.launch { snackbar.showSnackbar(msg) }
            })
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 104.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sorted, key = { it.id }) { person ->
                    PersonCard(
                        person = person,
                        s = statuses.getValue(person.id),
                        showHint = !hintDone,
                        autoPhone = autoPhone,
                        onTap = { caughtUp(person) },
                        onEdit = { editing = person.id },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    editing?.let { id ->
        EditSheet(
            person = if (id.isEmpty()) null else Store.find(id),
            onDismiss = { editing = null },
            onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
            onDeleted = { removed -> showUndo("Deleted ${removed.name}") { Store.upsert(removed) } },
        )
    }
}

@Composable
private fun Summary(count: Int, statuses: Collection<Status>) {
    val p = LocalPalette.current
    val over = statuses.count { it.level == Level.OVER }
    val soon = statuses.count { it.level == Level.SOON }
    val people = if (count == 1) "1 person" else "$count people"
    Text(
        buildAnnotatedString {
            when {
                count == 0 -> append("Nobody added yet")
                over == 0 && soon == 0 -> append("All caught up · $people")
                else -> {
                    if (over > 0) withStyle(SpanStyle(color = p.over, fontWeight = FontWeight.Bold)) { append("$over overdue") }
                    if (over > 0 && soon > 0) append(" · ")
                    if (soon > 0) withStyle(SpanStyle(color = p.soon, fontWeight = FontWeight.Bold)) { append("$soon due soon") }
                    append(" · $people")
                }
            }
        },
        fontSize = 14.sp, color = p.muted,
    )
}

@Composable
fun EmptyState(pad: PaddingValues, onAdd: () -> Unit, onPaste: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxSize().padding(pad).padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Who do you want to keep in touch with?", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = p.fg)
        Spacer(Modifier.height(10.dp))
        Text("Add someone, pick how often you want to catch up, and tap their card each time you do.",
            color = p.muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent)) {
            Text("Add person", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp)) {
            Text("Paste backup from the web app", fontWeight = FontWeight.Bold, color = p.fg)
        }
        Spacer(Modifier.height(8.dp))
        Text("In the web app: tap the download icon, then Copy text. Come back and tap Paste.",
            fontSize = 13.sp, color = p.muted, textAlign = TextAlign.Center)
    }
}

private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

@Composable
fun PersonCard(person: Person, s: Status, showHint: Boolean, autoPhone: Boolean, onTap: () -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val (color, chipBg) = when (s.level) {
        Level.OK -> p.ok to p.okBg
        Level.SOON -> p.soon to p.soonBg
        Level.OVER -> p.over to p.overBg
    }
    val chip = when (s.level) { Level.OVER -> "Overdue"; Level.SOON -> if (s.left == 0) "Today" else "Soon"; Level.OK -> "Fine" }
    val big = when {
        s.left < 0 -> "${Logic.plural(-s.left, "day")} overdue"
        s.left == 0 -> "Due today"
        else -> "Due in ${Logic.plural(s.left, "day")}"
    }
    val ago = when (s.elapsed) {
        0 -> "today"
        1 -> "yesterday"
        else -> "${s.elapsed} days ago (${Logic.localDate(person.lastCaughtUp).format(shortDate)})"
    }
    Card(
        onClick = onTap,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = p.surface, contentColor = p.fg),
        border = BorderStroke(1.dp, if (s.level == Level.OVER) color.copy(alpha = 0.55f) else p.line),
    ) {
        Box {
            Column(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(person.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                    Text(chip.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp,
                        modifier = Modifier.background(chipBg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Text(big, color = color, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 28.sp)
                LinearProgressIndicator(
                    progress = { max(0.04f, s.progress) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = color, trackColor = p.surface2, strokeCap = StrokeCap.Round,
                    gapSize = 0.dp, drawStopIndicator = {},
                )
                Text("Last caught up $ago · ${Logic.intervalLabel(person.intervalDays)}", color = p.muted, fontSize = 14.sp)
                if (person.note.isNotBlank()) {
                    Text(person.note, fontSize = 14.sp, modifier = Modifier.fillMaxWidth()
                        .background(p.surface2, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
                }
                if (person.phones.isNotEmpty() && autoPhone) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Phone, contentDescription = null, tint = p.muted, modifier = Modifier.size(14.dp))
                        Text("Calls tick this off automatically", fontSize = 12.sp, color = p.muted)
                    }
                }
                if (showHint && s.elapsed > 0) Text("Tap the card when you catch up", fontSize = 12.sp, color = p.muted)
            }
            IconButton(onClick = onEdit, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit ${person.name}", tint = p.muted)
            }
        }
    }
}
