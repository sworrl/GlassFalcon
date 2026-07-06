// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 decoder for the DJI 4a57 multiplexed downlink.
 *
 * The 4a57 payload is NOT clean H.264, DJI interleaves raw DUML telemetry frames
 * (55 cc 49 57 …) between/inside NAL units, plus proprietary SEIs (type
 * 0x55/0xf0/0xba/0xff) whose payloads contain UNESCAPED 00 00 00 01 sequences.
 * extractNals() removes all of that (a streaming port of usb_capture.py ::
 * _extract_h264_stream).
 *
 * Critically, the Mavic 2 codes each frame as THREE slices and delimits frames
 * with AUDs:  SPS PPS IDR IDR IDR | AUD P P P | AUD P P P …
 * MediaCodec must receive one whole ACCESS UNIT (all of a frame's slices) per
 * input buffer with one timestamp, feeding slices individually makes the decoder
 * treat each as a separate partial frame (top band only, rest black, grey).
 */
class VideoDecoder {
    private val TAG = "VideoDecoder"
    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private val chunkQueue = ArrayBlockingQueue<ByteArray>(256)
    private var procThread: Thread? = null
    private var outThread: Thread? = null
    private var payloadCount = 0L

    // 1280x720 is only the pre-decode hint MediaCodec.configure() needs before it's seen an
    // SPS, real Mavic 2 downlink resolution can differ. UI aspect-ratio math (MainScreen's
    // TextureView letterboxing) needs the ACTUAL negotiated size, not this guess, or it
    // pillarboxes/stretches by however far the real stream's ratio is from 16:9 at 720p.
    private val _resolution = MutableStateFlow(1280 to 720)
    val resolution: StateFlow<Pair<Int, Int>> = _resolution

    fun start(surface: Surface) {
        if (running.getAndSet(true)) return
        try {
            // Dimensions are only a hint, the decoder reconfigures from the SPS.
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(fmt, surface, null, 0)
            c.start()
            codec = c
            procThread = Thread({ processorLoop(c) }, "gf-h264-proc").also { it.isDaemon = true; it.start() }
            outThread  = Thread({ outputLoop(c) },    "gf-h264-out" ).also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            running.set(false)
        }
    }

    fun stop() {
        running.set(false)
        procThread?.interrupt()
        outThread?.interrupt()
        procThread?.join(500)
        outThread?.join(500)
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        codec = null
        chunkQueue.clear()
    }

    /** Called from the AOA rx thread with a raw 4a57 payload (55cc header already stripped). */
    fun onVideoPayload(chunk: ByteArray) {
        if (!running.get() || chunk.isEmpty()) return
        payloadCount++
        if (payloadCount == 1L) Log.i(TAG, "first 4a57 video payload received (${chunk.size}B), link is live")
        else if (payloadCount % 300L == 0L) Log.i(TAG, "video payload #$payloadCount (${chunk.size}B)")
        chunkQueue.offer(chunk)
    }

    // ── Processor: accumulate payloads → extract clean NALs → group into access
    //    units → feed each whole access unit to the codec as one buffer ─────────

    private fun processorLoop(c: MediaCodec) {
        var buf = ByteArray(0)
        var started = false                 // begin once the first SPS is seen
        var pts = 0L
        val au = ByteArrayOutputStream(256 * 1024)
        var auHasVcl = false

        fun feedAu() {
            if (au.size() == 0) return
            val bytes = au.toByteArray()
            au.reset(); auHasVcl = false
            try {
                val idx = c.dequeueInputBuffer(15_000L)
                if (idx < 0) return          // decoder busy, drop one frame, stay live
                val ib = c.getInputBuffer(idx) ?: return
                if (bytes.size > ib.limit()) { c.queueInputBuffer(idx, 0, 0, pts, 0); return }
                ib.clear(); ib.put(bytes)
                c.queueInputBuffer(idx, 0, bytes.size, pts, 0)
                pts += 33_333L               // ~30 fps
            } catch (e: Exception) {
                Log.w(TAG, "feed: ${e.message}")
            }
        }

        while (running.get()) {
            val first = try {
                chunkQueue.poll(50, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) { break } ?: continue

            // Drain everything queued and append to the carry-over buffer.
            val batch = ArrayList<ByteArray>().apply { add(first) }
            chunkQueue.drainTo(batch)
            var total = buf.size
            for (b in batch) total += b.size
            val data = ByteArray(total)
            System.arraycopy(buf, 0, data, 0, buf.size)
            var off = buf.size
            for (b in batch) { System.arraycopy(b, 0, data, off, b.size); off += b.size }

            val (nals, consumed) = extractNals(data, data.size)
            buf = if (consumed >= data.size) EMPTY else data.copyOfRange(consumed, data.size)
            if (buf.size > 4 * 1024 * 1024) { buf = EMPTY; started = false; au.reset(); auHasVcl = false }

            for (nal in nals) {
                if (nal.size < 5) continue
                val t = nal[4].toInt() and 0x1f          // NAL type (byte after 4-byte start code)
                if (!started) {
                    if (t == 7) { started = true; Log.i(TAG, "SPS found, decode started") }
                    else continue
                }

                // An AUD (9), or an SPS (7) once the current AU already holds slices,
                // marks the start of a new access unit → flush the one we've assembled.
                if (auHasVcl && (t == 9 || t == 7)) feedAu()

                au.write(nal)
                if (t == 1 || t == 5) auHasVcl = true     // VCL slice present
            }
        }
    }

    private fun outputLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            try {
                val idx = c.dequeueOutputBuffer(info, 10_000L)
                if (idx >= 0) {
                    c.releaseOutputBuffer(idx, true)
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val fmt = c.outputFormat
                    val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                    val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    Log.i(TAG, "real decoder output format: ${w}x$h")
                    _resolution.value = w to h
                }
            } catch (e: Exception) {
                Log.w(TAG, "output: ${e.message}")
            }
        }
    }

    // ── Streaming H.264 extraction (port of _extract_h264_stream) ─────────────

    /**
     * Walk [d] (valid bytes 0 until [n]) and return clean complete NAL units (each
     * prefixed with a 4-byte start code) plus the number of bytes safely consumed.
     * Anything past [consumed] is an incomplete unit kept for the next batch.
     */
    private fun extractNals(d: ByteArray, n: Int): Pair<List<ByteArray>, Int> {
        val out = ArrayList<ByteArray>(16)
        var i = 0
        var consumed = 0

        outer@ while (i < n) {
            val j = findStart(d, i, n)
            if (j < 0) { consumed = if (n >= 3) n - 3 else 0; break@outer }  // keep partial start code
            val nalHdr = j + 4
            if (nalHdr >= n) { consumed = j; break@outer }                   // need the type byte
            val nalType = d[nalHdr].toInt() and 0x1f

            // DJI proprietary SEI, skip by DECLARED size (payload has false start codes).
            if (nalType == 6 && nalHdr + 1 < n) {
                val seiType = d[nalHdr + 1].toInt() and 0xff
                if (seiType in DJI_SEI_TYPES) {
                    var p = nalHdr + 2
                    var seiSize = 0
                    while (p < n && d[p] == 0xFF.toByte()) { seiSize += 255; p++ }
                    if (p >= n) { consumed = j; break@outer }                // size field incomplete
                    seiSize += d[p].toInt() and 0xff; p++
                    val seiEnd = p + seiSize
                    if (seiEnd > n) { consumed = j; break@outer }            // payload incomplete
                    i = seiEnd
                    continue@outer
                }
            }

            // Embedded video outer-frame header (55 cc 4a 57) as false NAL-21, skip by length.
            if (matchAt(d, nalHdr, VIDEO_MAGIC)) {
                if (nalHdr + 8 > n) { consumed = j; break@outer }
                val outerLen = u32le(d, nalHdr + 4)
                if (outerLen in 1 until 65536) {
                    val end = nalHdr + 8 + outerLen
                    if (end > n) { consumed = j; break@outer }
                    i = end
                    continue@outer
                }
            }

            // Standard NAL: scan for end = next real start code OR first validated DUML.
            var pos = nalHdr + 1
            var nalEnd = -1
            var skipTo = -1
            var incomplete = false
            scan@ while (true) {
                var sc = findStart(d, pos, n); if (sc < 0) sc = n
                var dm = findDuml(d, pos, n);  if (dm < 0) dm = n
                if (dm < sc) {
                    if (dm + 8 > n) { incomplete = true; break@scan }        // need DUML length
                    val outerLen = u32le(d, dm + 4)
                    val plausible = outerLen in 1 until 65536
                    if (plausible && dm + 8 + outerLen > n) { incomplete = true; break@scan }
                    if (plausible && isDumlInner(d, dm + 8, outerLen)) {
                        if (skipTo < 0) nalEnd = dm                          // truncate NAL at first DUML
                        skipTo = dm + 8 + outerLen
                        pos = skipTo
                        continue@scan
                    }
                    pos = dm + 1                                            // false DUML, step over
                } else {
                    if (sc >= n) { incomplete = true; break@scan }          // no real end yet
                    if (skipTo < 0) nalEnd = sc
                    break@scan
                }
            }
            if (incomplete) { consumed = j; break@outer }

            out.add(d.copyOfRange(j, nalEnd))
            i = if (skipTo >= 0) skipTo else nalEnd
            consumed = i
        }
        return Pair(out, consumed)
    }

    private fun findStart(d: ByteArray, from: Int, n: Int): Int {
        var i = from
        while (i <= n - 4) {
            if (d[i] == 0.toByte() && d[i + 1] == 0.toByte() &&
                d[i + 2] == 0.toByte() && d[i + 3] == 1.toByte()) return i
            i++
        }
        return -1
    }

    private fun findDuml(d: ByteArray, from: Int, n: Int): Int {
        var i = from
        while (i <= n - 4) {
            if (d[i] == 0x55.toByte() && d[i + 1] == 0xCC.toByte() &&
                d[i + 2] == 0x49.toByte() && d[i + 3] == 0x57.toByte()) return i
            i++
        }
        return -1
    }

    private fun matchAt(d: ByteArray, i: Int, pat: ByteArray): Boolean {
        if (i + pat.size > d.size) return false
        for (k in pat.indices) if (d[i + k] != pat[k]) return false
        return true
    }

    private fun u32le(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8) or
        ((d[o + 2].toInt() and 0xff) shl 16) or ((d[o + 3].toInt() and 0xff) shl 24)

    private fun isDumlInner(d: ByteArray, pos: Int, outerLen: Int): Boolean {
        if (pos + 2 >= d.size || d[pos] != 0x55.toByte()) return false
        val lv = (d[pos + 1].toInt() and 0xff) or ((d[pos + 2].toInt() and 0xff) shl 8)
        return (lv and 0x3FF) == outerLen
    }

    private companion object {
        val EMPTY = ByteArray(0)
        val VIDEO_MAGIC = byteArrayOf(0x55, 0xCC.toByte(), 0x4A, 0x57)
        val DJI_SEI_TYPES = setOf(0x55, 0xf0, 0xba, 0xff)
    }
}
