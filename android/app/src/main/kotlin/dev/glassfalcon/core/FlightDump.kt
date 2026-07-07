// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File

/**
 * On-device flight dump: every DUML frame the app sees, written to a plain-text log with a
 * millisecond timestamp, direction, cmd_set/cmd_id, and the full raw frame hex. This is the
 * no-root equivalent of the kernel kprobe capture, it runs on any phone (e.g. the flight-test
 * Pixel 10) and produces exactly the kind of trace the parsers already read.
 *
 * Control is granular: arm/disarm by hand, or let it auto-start on takeoff and auto-stop on
 * touchdown (the same `inAir` boundary the flight recorder uses). Files land in the app's own
 * external files dir under dumps/ so they can be pulled or shared without extra permissions.
 */
class FlightDumpRecorder(private val ctx: Context) {
    companion object {
        private const val PREFS = "glassfalcon_debug"
        private const val KEY_DEBUG = "debug_mode"
        private const val KEY_AUTODUMP = "dump_on_flight"
    }

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    private val _lastPath = MutableStateFlow<String?>(null)
    val lastPath: StateFlow<String?> = _lastPath

    var debugMode: Boolean
        get() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DEBUG, false)
        set(v) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DEBUG, v).apply() }
    var autoDumpOnFlight: Boolean
        get() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTODUMP, false)
        set(v) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTODUMP, v).apply() }

    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var frameCount = 0L

    @Synchronized
    fun start(reason: String) {
        if (_active.value) return
        val dir = File(ctx.getExternalFilesDir(null), "dumps").apply { mkdirs() }
        // Dumps contain lat/lon-bearing telemetry frames and can reach hundreds of MB, so they're
        // encrypted while streaming: FileOutputStream → AES-GCM CipherOutputStream → BufferedWriter.
        // The GCM tag is written when the writer is closed in stop(). Extension .gfd = encrypted.
        val f = File(dir, "gfdump_${System.currentTimeMillis()}.gfd")
        writer = SecureStore.encryptingStream(java.io.FileOutputStream(f)).bufferedWriter().also {
            it.appendLine("# GlassFalcon flight dump")
            it.appendLine("# reason: $reason")
            it.appendLine("# started_ms: ${System.currentTimeMillis()}")
            it.appendLine("# format: <ms> <dir(>=out/<=in)> cs=<hex> id=<hex> <raw-frame-hex>")
        }
        file = f
        frameCount = 0
        _active.value = true
    }

    @Synchronized
    fun stop() {
        if (!_active.value) return
        writer?.apply {
            appendLine("# stopped_ms: ${System.currentTimeMillis()}")
            appendLine("# frames: $frameCount")
            flush(); close()
        }
        writer = null
        _lastPath.value = file?.absolutePath
        _active.value = false
    }

    /** Record one frame. `outbound` marks app→aircraft; the default is an inbound telemetry frame. */
    @Synchronized
    fun record(cmdSet: Int, cmdId: Int, raw: ByteArray, outbound: Boolean) {
        val w = writer ?: return
        val dir = if (outbound) ">" else "<"
        w.append(System.currentTimeMillis().toString()).append(' ').append(dir)
            .append(" cs=").append("%02x".format(cmdSet))
            .append(" id=").append("%02x".format(cmdId)).append(' ')
            .append(raw.joinToString("") { "%02x".format(it) })
        w.append('\n')
        frameCount++
    }

    fun dumpFiles(): List<File> =
        File(ctx.getExternalFilesDir(null), "dumps").listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Decrypt an encrypted (.gfd) dump to a plaintext temp in cache for an explicit export/read;
     *  a legacy plaintext (.txt) dump is returned as-is. */
    fun exportPlaintext(f: File): File {
        if (f.extension != "gfd") return f
        val out = File(ctx.cacheDir, f.nameWithoutExtension + ".txt")
        SecureStore.decryptingStream(f.inputStream()).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    /** Securely erase a dump file (see SecureStore.secureDelete). */
    fun secureDeleteDump(f: File) = SecureStore.secureDelete(f)
}
