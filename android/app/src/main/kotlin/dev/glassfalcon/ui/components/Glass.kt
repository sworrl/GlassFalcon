// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/** Marks the content BEHIND the glass panels (camera feed, map) as the thing that should show
 *  through blurred. Put once on the background-most composable of a screen; pass the same
 *  [HazeState] down to that screen's [glass] calls. */
fun Modifier.glassSource(state: HazeState): Modifier = this.haze(state)

/**
 * Clear-glass panel styling, real window glass, not the frosted/sandblasted look this used to
 * have. The blur is now just enough to soften hard edges (a few dp, not the old 18dp "can't make
 * out shapes behind it" fog): a pilot needs to actually recognize objects in the camera feed
 * through this panel, not just see a vague blurred color. [haze] is optional so call sites with
 * nothing interesting behind them (flat screens) can skip it and keep the plain tint+gradient
 * look; when present it's a real backdrop blur via the Haze library (RenderEffect-based on API
 * 31+, gracefully un-blurred below that).
 *
 * `tint` lets a panel read as "this is the green/armed one," or, per the compass/altitude
 * tapes, literally the color the data itself is currently showing, instead of a fixed white;
 * pass whatever color-carrying value the panel already computes (headingColor(), colorFor(v),
 * ...) instead of leaving this at the white default.
 *
 * The "glasslike" texture is just a slightly raised static noise/grain (via Haze's own
 * noiseFactor), no motion, no sheen sweeping across it. An earlier version animated a moving
 * highlight band across every panel; it read as distracting screen-wide shimmer rather than
 * "this is glass," so it's gone. Real optical refraction (actually bending the video behind it)
 * would need an AGSL RuntimeShader (API 33+ only), a bigger lift than "hardly noticeable
 * texture" calls for.
 *
 * [border] defaults on for ordinary standalone panels. Set it false for panels that are meant
 * to be WELDED into a larger contiguous surface (the flight HUD's top bar + side tapes + bottom
 * compass), each panel drawing its own full border around itself is exactly why that weld
 * didn't read as one piece: two adjacent 1dp borders at a shared edge still look like two
 * panels touching, not one surface. For a welded group, disable each member's own border and
 * draw a single outline over the group's true outer perimeter + inner (video-facing) opening
 * instead, see MainScreen's HudFrameOutline.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(12.dp),
    tint: Color = Color.White,
    baseAlpha: Float = 0.18f,
    haze: HazeState? = null,
    border: Boolean = true,
): Modifier {
    return this
        .clip(shape)
        .let { m ->
            if (haze != null) {
                m.hazeChild(
                    state = haze,
                    shape = shape,
                    style = HazeStyle(
                        // Barely-there tint and blur, clear glass darkens what's behind it a
                        // touch and takes the edge off hard lines, it doesn't fog them out.
                        tint = Color.Black.copy(alpha = baseAlpha * 0.5f),
                        blurRadius = 3.dp,
                        noiseFactor = 0.05f,
                    ),
                )
            } else {
                // FULLY clear, no fill at all. This used to paint Black at baseAlpha*0.5 as a
                // stand-in for the missing backdrop blur, which put a dark wash behind every
                // panel over the video. Readability now comes from drop shadows on the panel's
                // own text/icons, not from darkening the scene behind it.
                m
            }
        }
        // Body tint, a soft top-lit gradient (brightest at the top where light lands, faint
        // return glow at the bottom), the base "material" colour.
        .background(
            Brush.verticalGradient(
                0.0f to tint.copy(alpha = 0.15f),
                0.5f to Color.Transparent,
                1.0f to tint.copy(alpha = 0.04f),
            )
        )
        // Specular light-catch, a thin bright band hugging the very top edge, the single detail
        // that most reads as "a real pane of glass" rather than a flat tinted rectangle.
        .background(
            Brush.verticalGradient(
                0.0f to Color.White.copy(alpha = 0.13f),
                0.09f to Color.Transparent,
            )
        )
        .let { m ->
            // Beveled 2-tone edge, bright highlight on the top-left where light hits, a subtle
            // dark on the bottom-right for a raised, embossed lip. A single flat border reads as a
            // sticker; this reads as a machined edge.
            if (!border) m else m.border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.34f),
                        tint.copy(alpha = 0.14f),
                        Color.Black.copy(alpha = 0.20f),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(220f, 220f),
                ),
                shape = shape,
            )
        }
}
