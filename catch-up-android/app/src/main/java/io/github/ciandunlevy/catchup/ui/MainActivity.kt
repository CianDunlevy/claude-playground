package io.github.ciandunlevy.catchup.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.ciandunlevy.catchup.data.Prefs
import io.github.ciandunlevy.catchup.notify.Notifier
import io.github.ciandunlevy.catchup.widget.CatchUpWidget
import io.github.ciandunlevy.catchup.work.Work
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_ADD = "io.github.ciandunlevy.catchup.ADD"
        /** Set by the long-press "Add person" shortcut. */
        val addRequests = MutableStateFlow(false)
        /** Bumped every time the app comes to the front, so day counts refresh. */
        val resumeTick = MutableStateFlow(0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { CatchUpTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == ACTION_ADD) addRequests.value = true
    }

    override fun onResume() {
        super.onResume()
        resumeTick.value++
        Work.scanSoon(this)
        CatchUpWidget.updateAll(this)
        Notifier.syncOverdue(this, alert = false)
    }
}

@Composable
fun AppRoot() {
    var screen by rememberSaveable { mutableStateOf("list") }
    BackHandler(enabled = screen != "list") { screen = "list" }

    // Ask once for notifications (Android 13+) so reminders and "caught up" confirmations can show.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotifications) {
            Prefs.askedNotifications = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    when (screen) {
        "settings" -> SettingsScreen(onBack = { screen = "list" })
        else -> ListScreen(onSettings = { screen = "settings" })
    }
}
