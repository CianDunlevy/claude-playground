package io.github.ciandunlevy.catchup.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ciandunlevy.catchup.calls.CallScanner
import io.github.ciandunlevy.catchup.data.Prefs
import io.github.ciandunlevy.catchup.data.Store
import io.github.ciandunlevy.catchup.model.Backup
import io.github.ciandunlevy.catchup.model.Logic
import io.github.ciandunlevy.catchup.notify.Notifier
import io.github.ciandunlevy.catchup.work.Work
import java.time.LocalDate

/** Imports backup text. Returns a message to show. */
fun importBackup(text: String?, replace: Boolean): String {
    if (text.isNullOrBlank()) return "Nothing to import. Copy the backup text first."
    return try {
        val r = Backup.parse(text)
        if (r.people.isEmpty()) return "That backup has no valid people in it."
        val skipped = if (r.skipped > 0) " (${r.skipped} invalid skipped)" else ""
        if (replace) {
            Store.update { r.people }
            "Replaced with ${Logic.plural(r.people.size, "person").replace("persons", "people")}$skipped"
        } else {
            val m = Logic.merge(Store.people.value, r.people)
            Store.update { m.people }
            "Imported: ${m.added} added, ${m.updated} updated$skipped"
        }
    } catch (e: IllegalArgumentException) {
        e.message ?: "That isn't valid backup text."
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val tick by MainActivity.resumeTick.collectAsState()

    var autoPhone by remember { mutableStateOf(Prefs.autoPhone) }
    var minSec by remember { mutableIntStateOf(Prefs.minCallSeconds) }
    var remindersOn by remember { mutableStateOf(Prefs.remindersOn) }
    var hour by remember { mutableIntStateOf(Prefs.reminderHour) }
    var permDenied by remember { mutableStateOf(false) }
    val hasCallPerm = remember(tick, autoPhone, permDenied) { CallScanner.hasPermission(ctx) }
    var backupMsg by remember { mutableStateOf<String?>(null) }
    var replaceMode by remember { mutableStateOf(false) }

    fun enablePhone() {
        Prefs.autoPhone = true
        Prefs.autoEnabledAt = System.currentTimeMillis()
        autoPhone = true
        permDenied = false
        Work.scanSoon(ctx)
    }
    val phonePerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.READ_CALL_LOG] == true) enablePhone() else permDenied = true
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        backupMsg = runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(Backup.toJson(Store.people.value).toByteArray()) }
            "Backup saved."
        }.getOrDefault("Couldn't save the file.")
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        backupMsg = if (text == null) "Couldn't read that file." else importBackup(text, replaceMode)
    }

    fun openAppSettings() = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
    }

    Scaffold(
        containerColor = p.bg,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.bg, scrolledContainerColor = p.bg),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Automatic logging") {
                SwitchRow(
                    title = "Detect phone calls",
                    subtitle = "Calls of ${minSec / 60}+ min with a saved number tick that person off. You get a notification with Undo.",
                    checked = autoPhone && hasCallPerm,
                ) { on ->
                    if (!on) { Prefs.autoPhone = false; autoPhone = false }
                    else if (hasCallPerm) enablePhone()
                    else phonePerms.launch(buildList {
                        add(Manifest.permission.READ_CALL_LOG)
                        add(Manifest.permission.READ_PHONE_STATE)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray())
                }
                if (permDenied || (autoPhone && !hasCallPerm)) {
                    Text("Catch-Up needs call log access for this. In Android settings: Permissions → Call logs → Allow.", color = p.over, fontSize = 14.sp)
                    TextButton(onClick = { openAppSettings() }) { Text("Open app settings") }
                }
                SectionLabel("Count calls longer than")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60, 180, 300, 600).forEach { s ->
                        ChoiceChip("${s / 60} min", minSec == s) { minSec = s; Prefs.minCallSeconds = s; Work.scanSoon(ctx) }
                    }
                }
                Text("Add phone numbers in each person's edit screen. WhatsApp, Messenger and Instagram calls are coming next.",
                    fontSize = 13.sp, color = p.muted)
            }

            Section("Daily reminder") {
                SwitchRow("Remind me who's due", "One notification a day if anyone is due or overdue.", remindersOn) { on ->
                    remindersOn = on
                    Prefs.remindersOn = on
                    Work.scheduleReminder(ctx)
                    if (on && !Notifier.canNotify(ctx) && Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (remindersOn) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(9, 12, 18, 20).forEach { h ->
                        ChoiceChip("%02d:00".format(h), hour == h) { hour = h; Prefs.reminderHour = h; Work.scheduleReminder(ctx) }
                    }
                }
            }

            Section("Backup") {
                Text("Same format as the web app, so you can move people between the two.", fontSize = 14.sp, color = p.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Save file") { exportFile.launch("catch-up-backup-${LocalDate.now()}.json") }
                    SmallButton("Share") {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Backup.toJson(Store.people.value))
                        runCatching { ctx.startActivity(Intent.createChooser(send, "Share backup")) }
                    }
                    SmallButton("Import file") { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                    SmallButton("Paste") {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                        backupMsg = importBackup(text, replaceMode)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = replaceMode, onCheckedChange = { replaceMode = it })
                    Text("Replace everyone instead of merging", fontSize = 14.sp)
                }
                backupMsg?.let { Text(it, fontSize = 14.sp, color = p.ok, fontWeight = FontWeight.SemiBold) }
            }

            Section("Privacy") {
                Text("Everything stays on this phone. Catch-Up has no internet permission, so it can't send your call history or contacts anywhere.",
                    fontSize = 14.sp, color = p.muted)
            }

            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
            Text("Catch-Up $version", fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = p.surface, contentColor = p.fg),
        border = BorderStroke(1.dp, p.line),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 13.sp, color = p.muted)
        }
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent))
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(46.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, color = LocalPalette.current.fg)
    }
}
