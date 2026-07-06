// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glassfalcon.core.FlightViewModel
import dev.glassfalcon.core.plugin.PluginContext
import dev.glassfalcon.core.plugin.PluginRegistry
import dev.glassfalcon.ui.*

private val DjiGreen = Color(0xFF00CC44)

/**
 * The Plugins tab, optional features that aren't shipped-on for every pilot. Each plugin gets a
 * card with an enable switch; while enabled it renders its own controls ([GlassFalconPlugin.Content]).
 */
@Composable
fun PluginsScreen(vm: FlightViewModel) {
    val appCtx = LocalContext.current.applicationContext
    val pctx = remember(vm) { PluginContext(vm, appCtx) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Plugins", color = TextPri, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            "Optional add-on features. Off unless you turn them on, enabling one only makes its " +
            "controls available; it won't start doing anything until you tell it to.",
            color = TextSec, fontSize = 10.sp, lineHeight = 13.sp,
        )

        PluginRegistry.all.forEach { plugin ->
            var enabled by remember { mutableStateOf(PluginRegistry.isEnabled(appCtx, plugin)) }
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(plugin.title, color = TextPri, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(plugin.description, color = TextSec, fontSize = 10.sp, lineHeight = 13.sp)
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { PluginRegistry.setEnabled(pctx, plugin, it); enabled = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = DjiGreen),
                        )
                    }
                    if (enabled) {
                        HorizontalDivider(color = Color(0x22FFFFFF))
                        plugin.Content(pctx)
                    }
                }
            }
        }
    }
}
