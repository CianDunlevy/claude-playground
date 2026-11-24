package io.github.ciandunlevy.catchup.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Same palette as the web app. */
data class Palette(
    val bg: Color, val surface: Color, val surface2: Color, val fg: Color, val muted: Color, val line: Color,
    val accent: Color, val onAccent: Color,
    val ok: Color, val okBg: Color, val soon: Color, val soonBg: Color, val over: Color, val overBg: Color,
)

private val Light = Palette(
    bg = Color(0xFFF4F4F8), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFEBEBF2), fg = Color(0xFF1A1B24),
    muted = Color(0xFF5D6072), line = Color(0xFFD9DAE4), accent = Color(0xFF3540A0), onAccent = Color.White,
    ok = Color(0xFF23803A), okBg = Color(0xFFE2F3E6), soon = Color(0xFFA86A00), soonBg = Color(0xFFFCEFD2),
    over = Color(0xFFC42B2B), overBg = Color(0xFFFBE3E3),
)

private val Dark = Palette(
    bg = Color(0xFF111219), surface = Color(0xFF1A1C26), surface2 = Color(0xFF252836), fg = Color(0xFFE9EAF2),
    muted = Color(0xFF9A9DB2), line = Color(0xFF2E3142), accent = Color(0xFFA7B1FF), onAccent = Color(0xFF12142A),
    ok = Color(0xFF5FCF7E), okBg = Color(0xFF16301F), soon = Color(0xFFF2B84B), soonBg = Color(0xFF382A10),
    over = Color(0xFFFF7070), overBg = Color(0xFF3C1718),
)

val LocalPalette = staticCompositionLocalOf { Light }

@Composable
fun CatchUpTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) Dark else Light
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.accent, onPrimary = p.onAccent,
        background = p.bg, onBackground = p.fg,
        surface = p.surface, onSurface = p.fg,
        surfaceVariant = p.surface2, onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.surface, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface2,
        secondaryContainer = p.surface2, onSecondaryContainer = p.fg,
        primaryContainer = p.accent, onPrimaryContainer = p.onAccent,
        outline = p.line, outlineVariant = p.line,
        error = p.over,
        inverseSurface = p.fg, inverseOnSurface = p.bg, inversePrimary = if (dark) Light.accent else Dark.accent,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
