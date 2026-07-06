// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * App-wide unit and clock configuration. Metric and 24-hour are the defaults; the imperial and
 * 12-hour branches are fully implemented and each switch on a single flag, so a build can present
 * either system without touching call sites.
 */
object Units {
    @JvmField var metric = true
    @JvmField var hour24 = true

    fun temp(c: Float): String =
        if (metric) "${c.roundToInt()}°C" else "${(c * 9f / 5f + 32f).roundToInt()}°F"

    fun windSpeed(mps: Float): String =
        if (metric) "${mps.roundToInt()} m/s" else "${(mps * 2.23694f).roundToInt()} mph"

    private val clock24 = SimpleDateFormat("HH:mm", Locale.US)
    private val clock12 = SimpleDateFormat("h:mm a", Locale.US)
    private val stamp24 = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val stamp12 = SimpleDateFormat("h:mm:ss a", Locale.US)
    private val date24 = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.US)
    private val date12 = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US)

    fun clock(ms: Long): String = (if (hour24) clock24 else clock12).format(Date(ms))
    fun stamp(ms: Long): String = (if (hour24) stamp24 else stamp12).format(Date(ms))
    fun dateTime(ms: Long): String = (if (hour24) date24 else date12).format(Date(ms))
}
