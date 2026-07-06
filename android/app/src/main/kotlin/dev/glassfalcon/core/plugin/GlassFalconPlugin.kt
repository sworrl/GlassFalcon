// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core.plugin

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewModelScope
import dev.glassfalcon.core.FlightViewModel
import kotlinx.coroutines.CoroutineScope

/**
 * What a plugin is handed to do its job, the seams into the running app it's allowed to touch:
 * the live [FlightViewModel] (drone state, the DUML link + its video tap, command hooks), an
 * Android [Context], and the view-model's [scope] for long-running work that dies with the app.
 * Deliberately the whole VM rather than a narrow façade: plugins here are first-party, compiled
 * into the app and gated by the pilot, not sandboxed third-party code.
 */
class PluginContext(val vm: FlightViewModel, val appContext: Context) {
    val scope: CoroutineScope get() = vm.viewModelScope
    fun log(msg: String) = vm.log(msg)
}

/**
 * An optional feature that not every pilot wants shipped-in-the-app but toggled on per device, 
 * the extension point for capabilities like the encrypted re-streamer. A plugin is a plain object
 * registered in [PluginRegistry]; the "Plugins" settings tab lists them, persists an enable flag
 * per [id], calls [onEnable]/[onDisable] as it flips, and renders [Content] while enabled.
 *
 * "Enabled" means *available* (its controls are shown, its passive hooks may attach), it does NOT
 * imply the plugin is actively doing work; a streaming plugin, say, still needs its own explicit
 * Start button inside [Content]. [onDisable] is where a plugin tears down anything it started.
 */
interface GlassFalconPlugin {
    /** Stable identifier used as the persistence key, never change it once shipped. */
    val id: String
    val title: String
    val description: String
    /** Whether the plugin is on for a fresh install. Optional features default off. */
    val defaultEnabled: Boolean get() = false

    /** Called when the pilot switches the plugin on (and once at startup if already enabled). */
    fun onEnable(ctx: PluginContext) {}
    /** Called when switched off, tear down any listeners/streams/sockets started in [Content]. */
    fun onDisable(ctx: PluginContext) {}

    /** The plugin's own settings/controls, shown in the Plugins tab while it's enabled. */
    @Composable fun Content(ctx: PluginContext) {}
}
