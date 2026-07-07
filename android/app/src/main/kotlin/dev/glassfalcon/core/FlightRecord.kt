// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import dev.glassfalcon.GlassFalconApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One completed flight, recorded automatically from real telemetry (takeoff → touchdown), 
 *  every number here comes from a live GPS/battery/altitude reading, never invented, so the
 *  stats it feeds are honest facts about a real flight rather than generic praise. */
data class FlightRecord(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val maxAltM: Float,
    val maxSpeedMs: Float,
    val distanceM: Float,
    val battStartPct: Int,
    val battEndPct: Int,
    val warningsHit: List<String>,
    val track: List<TrackPoint>,
) {
    val durationSec: Long get() = ((endedAtMs - startedAtMs) / 1000).coerceAtLeast(0)
    val battUsedPct: Int get() = (battStartPct - battEndPct).coerceAtLeast(0)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("startedAtMs", startedAtMs); put("endedAtMs", endedAtMs)
        put("maxAltM", maxAltM); put("maxSpeedMs", maxSpeedMs); put("distanceM", distanceM)
        put("battStartPct", battStartPct); put("battEndPct", battEndPct)
        put("warningsHit", JSONArray(warningsHit))
        put("track", JSONArray(track.map { p ->
            JSONObject().apply {
                put("lat", p.lat); put("lon", p.lon); put("speed", p.speed); put("alt", p.alt)
                put("tMs", p.tMs); put("battPct", p.battPct)
            }
        }))
    }

    companion object {
        fun fromJson(o: JSONObject): FlightRecord {
            val trackArr = o.optJSONArray("track") ?: JSONArray()
            val track = (0 until trackArr.length()).map { i ->
                val t = trackArr.getJSONObject(i)
                TrackPoint(
                    t.getDouble("lat"), t.getDouble("lon"), t.getDouble("speed").toFloat(), t.getDouble("alt").toFloat(),
                    t.optLong("tMs", 0L), t.optInt("battPct", -1),
                )
            }
            val warnArr = o.optJSONArray("warningsHit") ?: JSONArray()
            return FlightRecord(
                id = o.getString("id"), startedAtMs = o.getLong("startedAtMs"), endedAtMs = o.getLong("endedAtMs"),
                maxAltM = o.optDouble("maxAltM", 0.0).toFloat(), maxSpeedMs = o.optDouble("maxSpeedMs", 0.0).toFloat(),
                distanceM = o.optDouble("distanceM", 0.0).toFloat(),
                battStartPct = o.optInt("battStartPct", 0), battEndPct = o.optInt("battEndPct", 0),
                warningsHit = (0 until warnArr.length()).map { warnArr.getString(it) },
                track = track,
            )
        }
    }
}

/** Flat-file JSON store under the app's own external files dir, one file per flight, named by
 *  start time so listing is naturally chronological. No database needed for what's realistically
 *  a few dozen to a few hundred records. */
object FlightRecordStore {
    // Records hold lat/lon + operator home point — encrypted at rest (see SecureStore). Encrypted
    // files use the .gfr extension; legacy plaintext .json files from older builds are migrated to
    // .gfr on first load and then securely wiped.
    private fun dir(): File =
        File(GlassFalconApp.ctx.getExternalFilesDir(null), "flight_records").apply { mkdirs() }

    private fun encFile(id: String) = File(dir(), "$id.gfr")

    fun save(record: FlightRecord) {
        SecureStore.writeEncrypted(encFile(record.id), record.toJson().toString().toByteArray(Charsets.UTF_8))
    }

    fun loadAll(): List<FlightRecord> {
        val d = dir()
        // Migrate any leftover plaintext records first, then wipe the cleartext copy.
        d.listFiles { f -> f.extension == "json" }?.forEach { legacy ->
            runCatching {
                val rec = FlightRecord.fromJson(JSONObject(legacy.readText()))
                save(rec)
                SecureStore.secureDelete(legacy)
            }
        }
        return d.listFiles { f -> f.extension == "gfr" }
            ?.mapNotNull { f ->
                runCatching {
                    FlightRecord.fromJson(JSONObject(String(SecureStore.readEncrypted(f), Charsets.UTF_8)))
                }.getOrNull()
            }
            ?.sortedByDescending { it.startedAtMs }
            ?: emptyList()
    }

    /** The encrypted at-rest file. Not human-readable — use [exportPlaintext] for sharing. */
    fun file(record: FlightRecord): File = encFile(record.id)

    /** Securely erase a record (encrypted + any legacy plaintext). */
    fun delete(record: FlightRecord) {
        SecureStore.secureDelete(encFile(record.id))
        SecureStore.secureDelete(File(dir(), "${record.id}.json"))
    }

    /**
     * Decrypt a record to a temporary plaintext JSON file in cache for an explicit user export
     * (Share sheet). This is the one moment the data is intentionally in the clear — the user chose
     * to send it. Lives in cacheDir so the OS can reclaim it.
     */
    fun exportPlaintext(record: FlightRecord): File {
        val out = File(GlassFalconApp.ctx.cacheDir, "flight_${record.id}.json")
        out.writeText(String(SecureStore.readEncrypted(encFile(record.id)), Charsets.UTF_8))
        return out
    }
}

/** Concrete, checkable milestones only, no "Great flight!" filler. Each badge names the exact
 *  fact that earned it. Recomputed fresh from the actual record list every time, not stored, so
 *  there's nothing to get out of sync. */
object FlightBadges {
    data class Badge(val title: String, val detail: String)

    fun forRecord(record: FlightRecord, allRecords: List<FlightRecord>): List<Badge> {
        val badges = mutableListOf<Badge>()
        val sorted = allRecords.sortedBy { it.startedAtMs }
        if (sorted.firstOrNull()?.id == record.id) badges += Badge("First Flight", "Recorded flight #1")
        if (allRecords.maxByOrNull { it.maxAltM }?.id == record.id && record.maxAltM > 0)
            badges += Badge("Altitude Record", "${record.maxAltM.toInt()}m, highest of ${allRecords.size} recorded flights")
        if (allRecords.maxByOrNull { it.maxSpeedMs }?.id == record.id && record.maxSpeedMs > 0)
            badges += Badge("Speed Record", "${"%.1f".format(record.maxSpeedMs)} m/s, fastest of ${allRecords.size} recorded flights")
        if (allRecords.maxByOrNull { it.durationSec }?.id == record.id)
            badges += Badge("Endurance Record", "${record.durationSec / 60}m ${record.durationSec % 60}s, longest of ${allRecords.size} recorded flights")
        if (allRecords.maxByOrNull { it.distanceM }?.id == record.id && record.distanceM > 0)
            badges += Badge("Distance Record", "${(record.distanceM / 1000).let { "%.2f".format(it) }} km, farthest of ${allRecords.size} recorded flights")
        if (record.warningsHit.isEmpty()) badges += Badge("Clean Flight", "No warnings triggered")
        return badges
    }

    /** Career totals across every recorded flight, the "profile stats" view. */
    fun totals(records: List<FlightRecord>): Map<String, String> = mapOf(
        "Flights" to records.size.toString(),
        "Total airtime" to records.sumOf { it.durationSec }.let { "${it / 3600}h ${(it % 3600) / 60}m" },
        "Total distance" to "%.2f km".format(records.sumOf { it.distanceM.toDouble() } / 1000),
        "Highest altitude" to "${records.maxOfOrNull { it.maxAltM }?.toInt() ?: 0}m",
        "Top speed" to "%.1f m/s".format(records.maxOfOrNull { it.maxSpeedMs } ?: 0f),
    )
}
