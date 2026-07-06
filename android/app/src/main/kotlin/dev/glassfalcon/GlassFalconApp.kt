// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon

import android.app.Application
import android.content.Context
import org.maplibre.android.MapLibre

class GlassFalconApp : Application() {
    companion object {
        lateinit var ctx: Context private set
    }

    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
        MapLibre.getInstance(this)
    }
}
