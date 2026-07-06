// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core.plugin.stream

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glassfalcon.core.plugin.GlassFalconPlugin
import dev.glassfalcon.core.plugin.PluginContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * First-party plugin: end-to-end-encrypted re-streaming of the live drone video to a blind relay
 * (falcontechnix.com by default), viewable from an ephemeral, self-burning link. All the transport
 * + crypto lives in [StreamPublisher]; this object is the pilot-facing config + start/stop control
 * and the glue that taps the DUML video feed while live.
 */
object EncryptedStreamPlugin : GlassFalconPlugin {
    override val id = "encrypted_stream"
    override val title = "Encrypted live stream"
    override val description =
        "Re-stream the drone's video to your website over an end-to-end-encrypted, server-blind " +
        "relay. Share an ephemeral link that burns itself when the stream ends."

    private const val PREFS = "glassfalcon_stream"
    private const val DEFAULT_BASE = "https://falcontechnix.com"

    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state

    private var publisher: StreamPublisher? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun serverBase(ctx: Context) = prefs(ctx).getString("base", DEFAULT_BASE) ?: DEFAULT_BASE
    fun secret(ctx: Context) = prefs(ctx).getString("secret", "") ?: ""
    fun saveConfig(ctx: Context, base: String, secret: String) {
        prefs(ctx).edit().putString("base", base.trim()).putString("secret", secret.trim()).apply()
    }

    override fun onDisable(ctx: PluginContext) = stopStreaming(ctx)

    private fun startStreaming(ctx: PluginContext) {
        val base = serverBase(ctx.appContext)
        val sec = secret(ctx.appContext)
        if (sec.isBlank()) { _state.value = StreamState.Error("Set a publisher secret first"); return }
        val pub = StreamPublisher(ctx.scope, { _state.value = it }, { ctx.log(it) }).also { publisher = it }
        ctx.vm.duml.addVideoListener(pub.videoTap)
        pub.start(base, sec)
    }

    private fun stopStreaming(ctx: PluginContext) {
        publisher?.let { p ->
            ctx.vm.duml.removeVideoListener(p.videoTap)
            p.stop()
        }
        publisher = null
    }

    @Composable
    override fun Content(ctx: PluginContext) {
        val st by state.collectAsState()
        var base by remember { mutableStateOf(serverBase(ctx.appContext)) }
        var secret by remember { mutableStateOf(secret(ctx.appContext)) }
        val live = st is StreamState.Live
        val busy = st is StreamState.Starting

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = base, onValueChange = { base = it; saveConfig(ctx.appContext, base, secret) },
                label = { Text("Relay server", fontSize = 10.sp) },
                singleLine = true, enabled = !live && !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = secret, onValueChange = { secret = it; saveConfig(ctx.appContext, base, secret) },
                label = { Text("Publisher secret", fontSize = 10.sp) },
                singleLine = true, enabled = !live && !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "The publisher secret authorises YOUR phone to open streams on the relay. The AES-256 " +
                "video key is generated on this phone, never sent to the server, and travels only in " +
                "the viewer link's #fragment, the relay only ever sees ciphertext.",
                color = Color(0xFF667788), fontSize = 9.sp, lineHeight = 12.sp,
            )

            when (st) {
                is StreamState.Idle, is StreamState.Error -> {
                    Button(
                        onClick = { startStreaming(ctx) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00CC44).copy(alpha = 0.25f)),
                    ) { Text("● Start encrypted stream", color = Color(0xFF00CC44), fontSize = 13.sp) }
                    (st as? StreamState.Error)?.let {
                        Text("⚠ ${it.message}", color = Color(0xFFFF3333), fontSize = 10.sp)
                    }
                }
                is StreamState.Starting -> {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFC8902A))
                        Spacer(Modifier.width(8.dp))
                        Text("Opening encrypted channel…", color = Color(0xFFC8902A), fontSize = 12.sp)
                    }
                }
                is StreamState.Live -> {
                    val url = (st as StreamState.Live).viewerUrl
                    Button(
                        onClick = { stopStreaming(ctx) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3333).copy(alpha = 0.25f)),
                    ) { Text("■ Stop & burn link", color = Color(0xFFFF3333), fontSize = 13.sp) }
                    Text("● LIVE, encrypted, server-blind", color = Color(0xFF00CC44), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Viewer link (contains the decryption key, share only with viewers):",
                        color = Color(0xFF667788), fontSize = 9.sp)
                    SelectionContainer {
                        Text(url, color = Color(0xFFC8D0DC), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { copyToClipboard(ctx.appContext, url) }) {
                            Text("Copy link", color = Color(0xFFC8902A), fontSize = 11.sp)
                        }
                        TextButton(onClick = { shareLink(ctx.appContext, url) }) {
                            Text("Share link", color = Color(0xFFC8902A), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    private fun copyToClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("GlassFalcon stream", text))
    }

    private fun shareLink(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(Intent.createChooser(send, "Share stream link").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
