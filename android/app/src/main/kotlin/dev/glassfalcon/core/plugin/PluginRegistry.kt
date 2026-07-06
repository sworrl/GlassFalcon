// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core.plugin

import android.content.Context
import dev.glassfalcon.core.plugin.stream.EncryptedStreamPlugin

/**
 * The compiled-in set of optional plugins plus their per-device enable state (SharedPreferences,
 * same idiom as the rest of the app). New plugins are added to [all]; nothing here is dynamically
 * loaded, see [GlassFalconPlugin]'s note on why these are first-party rather than sandboxed.
 */
object PluginRegistry {
    val all: List<GlassFalconPlugin> = listOf(
        EncryptedStreamPlugin,
    )

    fun byId(id: String): GlassFalconPlugin? = all.firstOrNull { it.id == id }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("glassfalcon_plugins", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context, plugin: GlassFalconPlugin): Boolean =
        prefs(ctx).getBoolean("en_${plugin.id}", plugin.defaultEnabled)

    /** Flip a plugin's enable flag and fire its lifecycle hook. */
    fun setEnabled(pctx: PluginContext, plugin: GlassFalconPlugin, on: Boolean) {
        prefs(pctx.appContext).edit().putBoolean("en_${plugin.id}", on).apply()
        if (on) plugin.onEnable(pctx) else plugin.onDisable(pctx)
    }

    /** Re-attach passive hooks for plugins the pilot left enabled, call once after the VM exists
     *  (e.g. from MainActivity) so an enabled plugin's [onEnable] runs at launch, not only on flip. */
    fun restoreEnabled(pctx: PluginContext) {
        all.filter { isEnabled(pctx.appContext, it) }.forEach { runCatching { it.onEnable(pctx) } }
    }
}
