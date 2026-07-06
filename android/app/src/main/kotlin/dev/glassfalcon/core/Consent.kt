// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.content.Context

/** GlassFalcon is a FOSS project with no monetization, server, or infrastructure of any kind, 
 *  there is no mechanism by which it could verify a pilot's certifications, waivers, or local
 *  authorizations, and it makes no attempt to. Any limit it shows (FC height/radius config,
 *  nearby FAA airspace ceilings) is informational, sourced from the aircraft's own reported
 *  config or public data, never a compliance guarantee, and nothing in the app is designed to
 *  restrict what a pilot who understands their own legal obligations chooses to do. This store
 *  persists a one-time explicit acknowledgment of that, shown once on first launch (see
 *  DisclaimerModal in GlassFalconRoot.kt), not re-shown on every subsequent start. */
object ConsentStore {
    private const val PREFS = "consent"
    private const val KEY_ACCEPTED = "disclaimer_accepted"

    fun hasAccepted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACCEPTED, false)

    fun setAccepted(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACCEPTED, true).apply()
    }
}
