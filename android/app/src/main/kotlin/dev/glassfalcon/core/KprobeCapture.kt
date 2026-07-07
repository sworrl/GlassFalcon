// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.util.Log
import dev.glassfalcon.GlassFalconApp
import java.io.File

/**
 * Root-gated kernel-kprobe capture of the phone↔aircraft DUML link, for reverse-engineering DJI's
 * activation handshake (the crypto gate behind the 30 m "kid mode" cap). The historical capture
 * used only 32 bytes/frame, which truncated DJI's ~97-byte challenge/response; these profiles grab
 * a much bigger per-frame window so those frames come back whole.
 *
 * Drive it from the in-app Dev Tools screen or over ADB via [KprobeReceiver]. Requires root
 * (Magisk); every entry point degrades to a clear "NO ROOT" string on a stock device. Captures are
 * written to the app's external files dir under `kprobe/` so they can be `adb pull`ed off and run
 * through `captures/parse_wide.py`.
 */
object KprobeCapture {
    /** words × 8 = bytes captured per frame. `acc_write` = phone→aircraft (TX, proven path).
     *  `acc_read` = aircraft→phone (RX, the FC's challenge) is best-effort: the symbol and its
     *  buffer/length registers can differ per kernel, so treat rx-* as experimental and adjust the
     *  symbol/words live over ADB if it yields nothing. */
    data class Profile(val name: String, val symbol: String, val words: Int, val note: String)

    val PROFILES = listOf(
        Profile("tx-wide", "acc_write", 16, "TX 128 B/frame — covers the ~97 B activation frames"),
        Profile("tx-max",  "acc_write", 32, "TX 256 B/frame — max window, catches concatenated frames"),
        Profile("rx-wide", "acc_read",  16, "RX 128 B/frame — the FC's challenge (experimental symbol)"),
    )

    private const val PROBE = "gf_probe"
    private const val MAX_WORDS = 48   // safety clamp on kprobe fetch-arg count

    private fun tracefs(): String =
        if (File("/sys/kernel/tracing/kprobe_events").exists()) "/sys/kernel/tracing"
        else "/sys/kernel/debug/tracing"

    private val outDir: File
        get() = File(GlassFalconApp.ctx.getExternalFilesDir(null), "kprobe").apply { mkdirs() }

    fun rooted(): Boolean = RootShell.isRooted()

    /** Arm a kprobe on [symbol] capturing [words]×8 bytes/frame. Clears any prior trace first. */
    fun arm(symbol: String, words: Int): String {
        if (!rooted()) return "NO ROOT — cannot arm kprobe"
        val n = words.coerceIn(1, MAX_WORDS)
        val fetch = StringBuilder("count=%x2")
        for (i in 0 until n) fetch.append(" b$i=+${i * 8}(%x1):x64")
        val t = tracefs()
        // Disable any pre-existing kprobe first (a stale probe from a prior session makes
        // `> kprobe_events` fail with "Device or resource busy"), then clear and define ours.
        // Joined with ';' (not '&&') so a benign glob-miss when there are no existing probes
        // doesn't abort the chain; success is judged by the probe appearing in kprobe_events.
        val cmd = listOf(
            "cd $t",
            "echo 0 > tracing_on",
            "for f in events/kprobes/*/enable; do [ -e \"\$f\" ] && echo 0 > \"\$f\"; done",
            "echo > kprobe_events",
            "echo > trace",
            "echo 'p:$PROBE $symbol $fetch' > kprobe_events",
            "echo 1 > events/kprobes/$PROBE/enable",
            "echo 1 > tracing_on",
            "cat kprobe_events",
        ).joinToString(" ; ")
        val r = RootShell.run(cmd)
        return if (r.out.contains(PROBE)) "ARMED $symbol, ${n * 8} B/frame"
        else "ARM FAILED: ${r.out.trim()}"
    }

    /** Arm a named [PROFILES] entry. */
    fun arm(profileName: String): String {
        val p = PROFILES.firstOrNull { it.name == profileName }
            ?: return "unknown profile '$profileName' (have: ${PROFILES.joinToString { it.name }})"
        return arm(p.symbol, p.words)
    }

    /** Snapshot the trace buffer to a timestamped file in app storage; returns a status line. */
    fun dump(tag: String = "cap"): String {
        if (!rooted()) return "NO ROOT — cannot dump"
        val r = RootShell.run("cat ${tracefs()}/trace")
        if (!r.ok) return "DUMP FAILED (exit ${r.code}): ${r.out.trim()}"
        val f = File(outDir, "${tag}_${System.currentTimeMillis()}.txt")
        f.writeText(r.out)
        Log.i("GF_KPROBE", "dumped ${r.out.length} B -> ${f.absolutePath}")
        return "DUMPED ${r.out.length} B -> ${f.absolutePath}"
    }

    /** Disable + remove the probe (leaves tracing subsystem clean). */
    fun teardown(): String {
        if (!rooted()) return "NO ROOT"
        val t = tracefs()
        val r = RootShell.run(
            "cd $t ; echo 0 > tracing_on ; " +
                "for f in events/kprobes/*/enable; do [ -e \"\$f\" ] && echo 0 > \"\$f\"; done ; " +
                "echo > kprobe_events ; echo done")
        return if (r.out.contains("done")) "PROBE REMOVED" else "TEARDOWN FAILED: ${r.out.trim()}"
    }

    /** Current probe definition + list of captures on disk. */
    fun status(): String {
        if (!rooted()) return "root: NO — kprobe capture unavailable"
        val r = RootShell.run("cat ${tracefs()}/kprobe_events 2>/dev/null")
        val caps = captures().joinToString("\n") { "  ${it.name}  (${it.length()} B)" }
        return "root: YES\nprobe: ${r.out.trim().ifEmpty { "(none armed)" }}\ncaptures:\n${caps.ifEmpty { "  (none)" }}"
    }

    fun captures(): List<File> =
        outDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
}
