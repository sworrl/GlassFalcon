// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal root awareness + `su` command runner. Used by [KprobeCapture] to arm/read/tear down
 * kernel kprobes on rooted (Magisk) devices for DUML-handshake reverse engineering. On a
 * non-rooted device [isRooted] is false and callers fall back to a clear "no root" message —
 * nothing here ever crashes the app or prompts on a stock phone.
 */
object RootShell {
    data class Result(val ok: Boolean, val out: String, val code: Int)

    @Volatile private var cached: Boolean? = null

    /** True if a su binary is present AND grants uid 0. Result is cached; pass recheck to re-probe
     *  (e.g. after the user grants the Magisk prompt for the first time). */
    fun isRooted(recheck: Boolean = false): Boolean {
        if (!recheck) cached?.let { return it }
        val suPresent = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/data/adb/magisk", "/system/app/Superuser.apk",
        ).any { runCatching { File(it).exists() }.getOrDefault(false) }
        val granted = run("id").let { it.ok && it.out.contains("uid=0") }
        return (suPresent || granted).also { cached = it }
    }

    /**
     * Run one shell command as root via `su -c`. Never throws. stdout+stderr are merged and drained
     * before waiting so large trace dumps don't deadlock the pipe. Do NOT pass a streaming command
     * (e.g. `cat trace_pipe`) — it never returns EOF; use the finite `cat trace` snapshot instead.
     */
    fun run(cmd: String, timeoutMs: Long = 20_000): Result {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()   // drains until process closes stdout
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) { p.destroyForcibly(); Result(false, out + "\n[timeout]", -1) }
            else Result(p.exitValue() == 0, out, p.exitValue())
        } catch (e: Exception) {
            Log.w("GF_ROOT", "su failed: ${e.message}")
            Result(false, e.message ?: "su exception", -1)
        }
    }
}
