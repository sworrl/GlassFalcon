// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

/**
 * Strips DJI proprietary SEIs and embedded DUML frames from a 4a57 payload,
 * yielding clean Annex-B H.264 NAL units suitable for MediaCodec.
 *
 * DJI SEI user_data_unregistered types observed in captures:
 *   0x55, 0xf0, 0xba, 0xff, skip the entire SEI NALU.
 * Embedded DUML frames start with 0x55 0x0d, skip to next start code.
 */
object VideoExtractor {

    private val START_CODE_3 = byteArrayOf(0x00, 0x00, 0x01)
    private val START_CODE_4 = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    // Known DJI proprietary SEI user_data_unregistered payload type bytes
    private val DJI_SEI_TYPES = setOf(0x55, 0xf0, 0xba, 0xff)

    /**
     * Extract clean H.264 NAL units from a 4a57 payload.
     * Returns a list of NAL unit byte arrays (each with a 4-byte start code).
     */
    fun extract(payload: ByteArray): List<ByteArray> {
        val nals = splitNalUnits(payload)
        val out = mutableListOf<ByteArray>()
        for (nal in nals) {
            val nalData = stripStartCode(nal)
            if (nalData.isEmpty()) continue
            val nalType = nalData[0].toInt() and 0x1f
            when {
                // DUML frame embedded as proprietary data, skip
                nalData.size >= 2 && nalData[0] == 0x55.toByte() && nalData[1] == 0x0d.toByte() -> continue
                // SEI NAL (type 6), check if DJI proprietary
                nalType == 6 -> if (!isDjiSei(nalData)) out.add(nal)
                // Everything else passes through
                else -> out.add(nal)
            }
        }
        return out
    }

    private fun isDjiSei(nalData: ByteArray): Boolean {
        // SEI: [nal_unit_type=6][payloadType][payloadSize][payload...]
        if (nalData.size < 3) return false
        val payloadType = nalData[1].toInt() and 0xff
        // user_data_unregistered = 5; DJI overloads this with custom type byte inside
        if (payloadType == 5 && nalData.size >= 4) {
            val djiType = nalData[3].toInt() and 0xff
            return djiType in DJI_SEI_TYPES
        }
        return false
    }

    private fun splitNalUnits(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i < data.size - 3) {
            if (data[i] == 0x00.toByte() && data[i + 1] == 0x00.toByte()) {
                when {
                    data[i + 2] == 0x01.toByte() -> { starts.add(i); i += 3; continue }
                    i + 3 < data.size && data[i + 2] == 0x00.toByte() && data[i + 3] == 0x01.toByte() -> {
                        starts.add(i); i += 4; continue
                    }
                }
            }
            i++
        }
        if (starts.isEmpty()) return emptyList()
        val result = mutableListOf<ByteArray>()
        for (k in starts.indices) {
            val from = starts[k]
            val to = if (k + 1 < starts.size) starts[k + 1] else data.size
            if (to > from) result.add(data.copyOfRange(from, to))
        }
        return result
    }

    private fun stripStartCode(nal: ByteArray): ByteArray {
        return when {
            nal.size >= 4 && nal[0] == 0x00.toByte() && nal[1] == 0x00.toByte() &&
                nal[2] == 0x00.toByte() && nal[3] == 0x01.toByte() -> nal.copyOfRange(4, nal.size)
            nal.size >= 3 && nal[0] == 0x00.toByte() && nal[1] == 0x00.toByte() &&
                nal[2] == 0x01.toByte() -> nal.copyOfRange(3, nal.size)
            else -> nal
        }
    }
}
