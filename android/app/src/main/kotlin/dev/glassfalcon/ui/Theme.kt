// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.glassfalcon.R

// Bundled (not Downloadable Fonts, no runtime network fetch, works fully offline, matching
// this project's no-cloud-dependency posture), see README's "Fonts" section for licensing
// (both OFL-1.1). Space Grotesk is the app-wide default (labels, buttons, Settings screens);
// IbmPlexMono is applied explicitly to the flight HUD's own numeric readouts (speed/altitude/
// heading/battery/timer) where a monospaced, tabular-figure face keeps digit width constant as
// values change instead of the whole readout jittering side to side on every update.
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)
val IbmPlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_bold, FontWeight.Bold),
)

// Matching the Glass Falcon desktop colour palette
val Gold    = Color(0xFFC8902A)
val Navy    = Color(0xFF0A0C0F)
val DarkBg  = Color(0xFF050810)
val Panel   = Color(0xFF0D1015)
val Border  = Color(0xFF1C2A3A)
val TextPri = Color(0xFFC8D0DC)
val TextSec = Color(0xFF667788)
val Green   = Color(0xFF40C060)
val Red     = Color(0xFFE04040)
val Orange  = Color(0xFFFF8040)

private val ColorScheme = darkColorScheme(
    primary         = Gold,
    onPrimary       = Color.Black,
    secondary       = Green,
    onSecondary     = Color.Black,
    background      = Navy,
    onBackground    = TextPri,
    surface         = Panel,
    onSurface       = TextPri,
    surfaceVariant  = Border,
    onSurfaceVariant= TextSec,
    error           = Red,
)

private val AppTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = SpaceGrotesk),
        displayMedium = base.displayMedium.copy(fontFamily = SpaceGrotesk),
        displaySmall = base.displaySmall.copy(fontFamily = SpaceGrotesk),
        headlineLarge = base.headlineLarge.copy(fontFamily = SpaceGrotesk),
        headlineMedium = base.headlineMedium.copy(fontFamily = SpaceGrotesk),
        headlineSmall = base.headlineSmall.copy(fontFamily = SpaceGrotesk),
        titleLarge = base.titleLarge.copy(fontFamily = SpaceGrotesk),
        titleMedium = base.titleMedium.copy(fontFamily = SpaceGrotesk),
        titleSmall = base.titleSmall.copy(fontFamily = SpaceGrotesk),
        bodyLarge = base.bodyLarge.copy(fontFamily = SpaceGrotesk),
        bodyMedium = base.bodyMedium.copy(fontFamily = SpaceGrotesk),
        bodySmall = base.bodySmall.copy(fontFamily = SpaceGrotesk),
        labelLarge = base.labelLarge.copy(fontFamily = SpaceGrotesk),
        labelMedium = base.labelMedium.copy(fontFamily = SpaceGrotesk),
        labelSmall = base.labelSmall.copy(fontFamily = SpaceGrotesk),
    )
}

@Composable
fun GlassFalconTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, typography = AppTypography, content = content)
}
