package no.synth.divelog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Ocean-led Material 3 scheme: deep-sea teal primary, the logo's purple as the
// secondary accent, a warm coral tertiary (for the temperature trace etc.).
private val LightColors = lightColorScheme(
    primary = Color(0xFF00687B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFAEECFF),
    onPrimaryContainer = Color(0xFF001F28),
    secondary = Color(0xFF5A5B9E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2DFFF),
    onSecondaryContainer = Color(0xFF15144B),
    tertiary = Color(0xFF9C4332),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD2),
    onTertiaryContainer = Color(0xFF3A0A03),
    background = Color(0xFFFAFDFD),
    onBackground = Color(0xFF191C1D),
    surface = Color(0xFFFAFDFD),
    onSurface = Color(0xFF191C1D),
    surfaceVariant = Color(0xFFDBE4E7),
    onSurfaceVariant = Color(0xFF3F484A),
    outline = Color(0xFF6F797B),
    outlineVariant = Color(0xFFBFC8CB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF54D7F3),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF004E5B),
    onPrimaryContainer = Color(0xFFAEECFF),
    secondary = Color(0xFFC3C0FF),
    onSecondary = Color(0xFF2B2A60),
    secondaryContainer = Color(0xFF42427A),
    onSecondaryContainer = Color(0xFFE2DFFF),
    tertiary = Color(0xFFFFB4A3),
    onTertiary = Color(0xFF5C1A0C),
    tertiaryContainer = Color(0xFF7D2E1E),
    onTertiaryContainer = Color(0xFFFFDAD2),
    background = Color(0xFF0E1415),
    onBackground = Color(0xFFDEE3E5),
    surface = Color(0xFF0E1415),
    onSurface = Color(0xFFDEE3E5),
    surfaceVariant = Color(0xFF3F484A),
    onSurfaceVariant = Color(0xFFBFC8CB),
    outline = Color(0xFF899295),
    outlineVariant = Color(0xFF3F484A),
)

/** Shared app theme used by all platforms. */
@Composable
fun SynthTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
