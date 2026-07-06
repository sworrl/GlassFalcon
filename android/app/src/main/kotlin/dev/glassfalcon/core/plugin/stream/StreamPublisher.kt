// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core.plugin.stream

import android.util.Base64
import dev.glassfalcon.core.VideoExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What the UI needs to render, where the publisher is in its lifecycle. */
sealed class StreamState {
    object Idle : StreamState()
    object Starting : StreamState()
    /** Live: [viewerUrl] carries the content key in its #fragment and is safe to share. */
    data class Live(val viewerUrl: String, val streamId: String) : StreamState()
    data class Error(val message: String) : StreamState()
}

/**
 * Re-streams the drone's live H.264 to a blind relay, end-to-end encrypted so the server only ever
 * sees ciphertext (see STREAMING_SERVER_SPEC.md). Pipeline:
 *
 *   drone 4a57 payloads → [VideoExtractor] clean NALs → assemble access units → AES-256-GCM per AU
 *   → binary WebSocket (wss) to the relay's /ingest → relay fans the same ciphertext to viewers.
 *
 * The AES-256 content key is generated here, never sent to the server, and rides only in the viewer
 * link's `#fragment` (which browsers never transmit). Tokens gate publish + view and self-expire;
 * [stop] also calls the relay's burn endpoint so the link dies with the stream.
 *
 * SRT would give better resilience on a lossy cellular uplink, but a browser can't receive SRT and
 * a server-blind design can't transcode, so a symmetric wss relay of already-encrypted frames is
 * the design that actually satisfies "encrypted + server-blind + thin relay + plays in a webpage".
 */
class StreamPublisher(
    private val scope: CoroutineScope,
    private val onState: (StreamState) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var serverBase: String = ""
    @Volatile private var secret: String = ""
    @Volatile private var streamId: String = ""

    // AES-256-GCM content key + a 4-byte per-stream nonce salt; the 12-byte GCM nonce is
    // salt(4) ++ counter(8). The counter is shared across INIT and MEDIA frames so no (key,nonce)
    // pair is ever reused, which GCM requires for security.
    private var key: ByteArray = ByteArray(0)
    private var salt: ByteArray = ByteArray(4)
    private val counter = AtomicLong(0)
    private val rng = SecureRandom()

    private val assembler = AccessUnitAssembler()
    private var frameCount = 0L
    private var lastInitFrame = -1000L

    val videoTap: (ByteArray) -> Unit = tap@{ payload ->
        if (!running) return@tap
        val nals = runCatching { VideoExtractor.extract(payload) }.getOrNull() ?: return@tap
        for (nal in nals) assembler.feed(nal) { au, keyframe -> onAccessUnit(au, keyframe) }
    }

    /** Kick off a stream. Network work runs on IO; [onState] reports progress/result. */
    fun start(serverBaseUrl: String, publisherSecret: String) {
        if (running) return
        serverBase = serverBaseUrl.trimEnd('/')
        secret = publisherSecret.trim()
        onState(StreamState.Starting)
        scope.launch(Dispatchers.IO) {
            try {
                key = ByteArray(32).also { rng.nextBytes(it) }
                salt = ByteArray(4).also { rng.nextBytes(it) }
                counter.set(0); frameCount = 0; lastInitFrame = -1000
                assembler.reset()

                val (sid, ingestToken, viewerToken) = requestStream()
                streamId = sid
                val keyB64 = Base64.encodeToString(key, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
                val viewerUrl = "$serverBase/watch/$sid?t=$viewerToken#k=$keyB64"

                openIngest(sid, ingestToken, viewerUrl)
            } catch (e: Exception) {
                running = false
                onLog("Stream start failed: ${e.message}")
                onState(StreamState.Error(e.message ?: "start failed"))
            }
        }
    }

    /** Stop streaming and burn the link. Idempotent. */
    fun stop() {
        if (!running && ws == null) { onState(StreamState.Idle); return }
        running = false
        runCatching { ws?.close(1000, "done") }
        ws = null
        val sid = streamId
        if (sid.isNotEmpty()) scope.launch(Dispatchers.IO) { runCatching { burnStream(sid) } }
        onLog("Stream stopped")
        onState(StreamState.Idle)
    }

    // ── Relay token API ────────────────────────────────────────────────────────────────────────

    private data class StreamCreds(val streamId: String, val ingest: String, val viewer: String)

    private fun requestStream(): StreamCreds {
        val body = JSONObject().apply { put("ttlSeconds", 0) } // 0 = bound to the stream, burn on stop
            .toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$serverBase/api/stream/start")
            .header("Authorization", "Bearer $secret")
            .post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("relay ${resp.code}: ${text.take(120)}")
            val j = JSONObject(text)
            return StreamCreds(
                streamId = j.getString("streamId"),
                ingest = j.getString("ingestToken"),
                viewer = j.getString("viewerToken"),
            )
        }
    }

    private fun burnStream(sid: String) {
        val body = JSONObject().apply { put("streamId", sid) }
            .toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$serverBase/api/stream/burn")
            .header("Authorization", "Bearer $secret")
            .post(body).build()
        http.newCall(req).execute().use { /* fire-and-forget */ }
    }

    // ── Ingest WebSocket ─────────────────────────────────────────────────────────────────────────

    private fun openIngest(sid: String, ingestToken: String, viewerUrl: String) {
        val wsUrl = serverBase.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/ingest/$sid?t=$ingestToken"
        val req = Request.Builder().url(wsUrl).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                running = true
                onLog("Ingest connected; streaming encrypted video")
                onState(StreamState.Live(viewerUrl, sid))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!running) return
                running = false
                onLog("Ingest link failed: ${t.message}")
                onState(StreamState.Error(t.message ?: "ingest failed"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (running) { running = false; onState(StreamState.Idle) }
            }
        })
    }

    // ── Per-access-unit encryption + framing ───────────────────────────────────────────────────

    private fun onAccessUnit(au: ByteArray, keyframe: Boolean) {
        val sock = ws ?: return
        if (!running) return
        // Periodically (and on the first keyframe) resend the decoder INIT so viewers who join
        // mid-stream can configure their decoder without us knowing they arrived.
        if (keyframe && frameCount - lastInitFrame > 60) {
            assembler.codecString()?.let { codec ->
                sendFrame(sock, TYPE_INIT, JSONObject().put("codec", codec).toString().toByteArray())
                lastInitFrame = frameCount
            }
        }
        val ts = frameCount * 33_333L // ~30 fps in microseconds; monotonic is all WebCodecs needs
        val plain = ByteArrayOutputStream(au.size + 9).apply {
            write(byteArrayOf(
                (ts ushr 56).toByte(), (ts ushr 48).toByte(), (ts ushr 40).toByte(), (ts ushr 32).toByte(),
                (ts ushr 24).toByte(), (ts ushr 16).toByte(), (ts ushr 8).toByte(), ts.toByte(),
            ))
            write(if (keyframe) 1 else 0)
            write(au)
        }.toByteArray()
        sendFrame(sock, TYPE_MEDIA, plain)
        frameCount++
    }

    /** Encrypt [plain] and push `[type][nonce(12)][ciphertext+tag]` as one binary WS message. */
    private fun sendFrame(sock: WebSocket, type: Int, plain: ByteArray) {
        val n = counter.getAndIncrement()
        val nonce = ByteArray(12)
        System.arraycopy(salt, 0, nonce, 0, 4)
        for (i in 0 until 8) nonce[4 + i] = (n ushr ((7 - i) * 8)).toByte()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }
        val ct = cipher.doFinal(plain)
        val out = ByteArray(1 + 12 + ct.size)
        out[0] = type.toByte()
        System.arraycopy(nonce, 0, out, 1, 12)
        System.arraycopy(ct, 0, out, 13, ct.size)
        runCatching { sock.send(out.toByteString()) }
    }

    companion object {
        const val TYPE_INIT = 0x01
        const val TYPE_MEDIA = 0x02
    }
}

/**
 * Groups clean Annex-B NALs into whole access units (one decoded frame each) so viewers get correct
 * frame boundaries and timestamps. Caches SPS/PPS and prepends them to every keyframe AU so a viewer
 * joining mid-stream can decode from the next keyframe without a separate parameter-set exchange.
 */
private class AccessUnitAssembler {
    private var cur = ByteArrayOutputStream()
    private var curHasVcl = false
    private var curHasIdr = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun reset() { cur = ByteArrayOutputStream(); curHasVcl = false; curHasIdr = false; sps = null; pps = null }

    fun feed(nal: ByteArray, emit: (ByteArray, Boolean) -> Unit) {
        val type = nalType(nal)
        val isVcl = type in 1..5
        val startsNewAu = when {
            type == 9 -> true                                   // access unit delimiter
            isVcl && curHasVcl -> true                          // a second coded slice ⇒ next frame
            (type == 6 || type == 7 || type == 8) && curHasVcl -> true // SEI/SPS/PPS after a slice
            else -> false
        }
        if (startsNewAu && cur.size() > 0) flush(emit)

        when (type) { 7 -> sps = nal; 8 -> pps = nal }
        cur.write(nal)
        if (isVcl) { curHasVcl = true; if (type == 5) curHasIdr = true }
    }

    private fun flush(emit: (ByteArray, Boolean) -> Unit) {
        val body = cur.toByteArray()
        val out = if (curHasIdr) {
            // Self-contained keyframe: parameter sets ahead of the IDR (harmless if duplicated).
            ByteArrayOutputStream().apply {
                sps?.let { write(it) }; pps?.let { write(it) }; write(body)
            }.toByteArray()
        } else body
        emit(out, curHasIdr)
        cur = ByteArrayOutputStream(); curHasVcl = false; curHasIdr = false
    }

    /** avc1.PPCCLL codec string from the cached SPS, for WebCodecs configure(). */
    fun codecString(): String? {
        val s = sps ?: return null
        val p = stripStartCode(s)
        if (p.size < 4) return null
        return "avc1.%02x%02x%02x".format(p[1].toInt() and 0xff, p[2].toInt() and 0xff, p[3].toInt() and 0xff)
    }

    private fun nalType(nal: ByteArray): Int {
        val p = stripStartCode(nal)
        return if (p.isEmpty()) -1 else p[0].toInt() and 0x1f
    }

    private fun stripStartCode(nal: ByteArray): ByteArray = when {
        nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte() ->
            nal.copyOfRange(4, nal.size)
        nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte() ->
            nal.copyOfRange(3, nal.size)
        else -> nal
    }
}
