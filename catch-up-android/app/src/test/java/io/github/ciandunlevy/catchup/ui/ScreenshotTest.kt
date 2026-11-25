package io.github.ciandunlevy.catchup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import io.github.ciandunlevy.catchup.model.Logic
import io.github.ciandunlevy.catchup.model.Person
import org.junit.Rule
import org.junit.Test

/** Renders the main cards so layout can be checked without a phone. Images land in build/reports/paparazzi. */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 100.0)

    private val day = 24L * 60 * 60 * 1000
    private val now = System.currentTimeMillis()
    private val people = listOf(
        Person("a", "Mam", 7, "Ask how the garden is doing", now - 9 * day, phones = listOf("0871234567")),
        Person("b", "Aoife", 14, "", now - 12 * day),
        Person("c", "Dave", 30, "Ask about his new job", now - 6 * day),
    )

    private fun cards(dark: Boolean) {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT))
        paparazzi.snapshot {
            CatchUpTheme {
                val p = LocalPalette.current
                Column(Modifier.fillMaxSize().background(p.bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Catch-Up", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = p.fg)
                    Logic.sorted(people, now).forEach {
                        PersonCard(it, Logic.status(it, now), showHint = true, autoLog = true, onTap = {}, onEdit = {})
                    }
                }
            }
        }
    }

    @Test fun cardsLight() = cards(false)
    @Test fun cardsDark() = cards(true)

    @Test fun empty() = paparazzi.snapshot {
        CatchUpTheme { Column(Modifier.background(LocalPalette.current.bg)) { EmptyState(PaddingValues(0.dp), {}, {}) } }
    }
}
