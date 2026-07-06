// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class OffloadStatus(
    val running: Boolean = false,
    val totalFiles: Int = 0,
    val transferredFiles: Int = 0,
    val currentFile: String = "",
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val log: List<String> = emptyList(),
    val localDir: String = "",
    val odmTaskId: String? = null,
    val odmStatus: String = "",
)

/**
 * Offloads media from the drone's internal storage via the wm240 drone WiFi HTTP server
 * (accessible when phone connects to drone's WiFi AP at 192.168.2.1).
 *
 * Also handles submission to a NodeODM server for photogrammetry processing.
 */
class OffloadManager(private val scope: CoroutineScope) {

    private val _status = MutableStateFlow(OffloadStatus())
    val status: StateFlow<OffloadStatus> = _status

    // ── Drone WiFi HTTP media API (192.168.2.1) ────────────────────────────────

    fun startWifiOffload(
        droneIp: String = "192.168.2.1",
        destDir: File,
        filter: String = ".JPG",
    ) {
        scope.launch(Dispatchers.IO) {
            _status.value = OffloadStatus(running = true, localDir = destDir.absolutePath)
            log("Connecting to drone at $droneIp …")
            destDir.mkdirs()

            try {
                // wm240 WiFi media list endpoint
                val listUrl = "http://$droneIp/cgi-bin/media/list.cgi"
                val listJson = get(listUrl)
                val files = parseMediaList(listJson, filter)

                log("Found ${files.size} files matching $filter")
                _status.value = _status.value.copy(totalFiles = files.size,
                    totalBytes = files.sumOf { it.second })

                var transferred = 0
                var bytes = 0L
                for ((path, size) in files) {
                    if (!isActive) break
                    val name = path.substringAfterLast('/')
                    _status.value = _status.value.copy(currentFile = name)
                    val dest = File(destDir, name)
                    if (!dest.exists() || dest.length() != size) {
                        download("http://$droneIp$path", dest)
                        bytes += size
                    } else {
                        log("Skip (exists): $name")
                        bytes += size
                    }
                    transferred++
                    _status.value = _status.value.copy(
                        transferredFiles = transferred,
                        bytesTransferred = bytes,
                    )
                }
                log("✓ Offload complete, $transferred files in ${destDir.absolutePath}")
            } catch (e: Exception) {
                log("Error: ${e.message}")
            } finally {
                _status.value = _status.value.copy(running = false, currentFile = "")
            }
        }
    }

    // ── SD card FTP probe (experimental, see doc comment) ────────────────────

    /**
     * Older DJI aircraft (Mavic Pro/Spark/Phantom 4/Inspire 2) expose a modified busybox ftpd
     * on their private USB/WiFi IP (192.168.42.2 over USB RNDIS, 192.168.2.1 over WiFi),
     * `nouser`/`nopass`, files AES-scrambled with an in-firmware key, confirmed working by
     * MAVProxyUser/DJI_ftpd_aes_unscramble on those aircraft. Whether wm240 (Mavic 2 Pro) still
     * exposes this is explicitly flagged as UNCONFIRMED by that same research (a mavicpilots.com
     * thread says so directly), this project has no working test of it either. Rather than
     * build a whole gallery around an unverified assumption, this just tries a raw connect and
     * reads whatever banner comes back, so a live session immediately says yes or no instead of
     * guessing. No AES descrambling here, that's only worth writing once this probe actually
     * gets a real FTP banner back on this aircraft.
     */
    fun probeFtpAccess(host: String) {
        scope.launch(Dispatchers.IO) {
            log("Probing SD card FTP at $host:21 (experimental, unconfirmed on Mavic 2)…")
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, 21), 4000)
                    socket.soTimeout = 4000
                    val banner = socket.getInputStream().bufferedReader().readLine()
                    if (banner != null) {
                        log("✓ FTP responded: $banner")
                        log("(Listing/download not implemented yet, this only confirms the port answers.)")
                    } else {
                        log("Connected but got no banner, probably not an FTP server here")
                    }
                }
            } catch (e: Exception) {
                log("No FTP response from $host:21 (${e.message}), this path likely isn't available on this aircraft")
            }
        }
    }

    // ── ADB offload (when assistant unlock gave ADB access) ──────────────────

    fun startAdbOffload(destDir: File, filter: String = "*.JPG") {
        scope.launch(Dispatchers.IO) {
            _status.value = OffloadStatus(running = true, localDir = destDir.absolutePath)
            destDir.mkdirs()
            log("ADB pull from /sdcard/DCIM/DJI/ …")
            try {
                val proc = ProcessBuilder("adb", "pull", "/sdcard/DCIM/DJI/", destDir.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                log(out.takeLast(500))
                log("ADB pull complete")
            } catch (e: Exception) {
                log("ADB not available: ${e.message}")
            } finally {
                _status.value = _status.value.copy(running = false)
            }
        }
    }

    // ── NodeODM photogrammetry submission ─────────────────────────────────────

    fun submitToODM(
        imageDir: File,
        odmUrl: String,    // e.g. http://192.168.1.100:3000
        taskName: String = "GlassFalcon_${System.currentTimeMillis()}",
        options: Map<String, String> = mapOf(
            "dsm"       to "true",
            "dtm"       to "true",
            "mesh"      to "true",
            "pc-quality" to "medium",
            "feature-quality" to "medium",
        ),
    ) {
        scope.launch(Dispatchers.IO) {
            log("Submitting to NodeODM at $odmUrl …")
            try {
                // 1. Init task
                val initBody = JSONObject().apply {
                    put("name", taskName)
                    put("options", JSONArray().apply {
                        options.forEach { (k, v) ->
                            put(JSONObject().apply { put("name",k); put("value",v) })
                        }
                    })
                }
                val initResp = post("$odmUrl/task/new/init", initBody.toString())
                val taskId = JSONObject(initResp).getString("uuid")
                log("ODM task created: $taskId")
                _status.value = _status.value.copy(odmTaskId = taskId, odmStatus = "Uploading…")

                // 2. Upload images via multipart
                val images = imageDir.listFiles { f -> f.extension.uppercase() in setOf("JPG","JPEG","TIF","TIFF") }
                    ?: emptyArray()
                log("Uploading ${images.size} images…")
                uploadImages(odmUrl, taskId, images)

                // 3. Commit and start
                post("$odmUrl/task/new/commit/$taskId", "{}")
                log("ODM processing started for task $taskId")
                _status.value = _status.value.copy(odmStatus = "Processing…")

                // 4. Poll status
                pollOdmStatus(odmUrl, taskId)

            } catch (e: Exception) {
                log("ODM error: ${e.message}")
                _status.value = _status.value.copy(odmStatus = "Error: ${e.message}")
            }
        }
    }

    private suspend fun pollOdmStatus(odmUrl: String, taskId: String) {
        while (isActive) {
            delay(10_000)
            try {
                val resp = get("$odmUrl/task/$taskId/info")
                val obj = JSONObject(resp)
                val status = obj.optJSONObject("status")?.optString("code") ?: "unknown"
                val pct = obj.optInt("progress", 0)
                val msg = "ODM: $status ($pct%)"
                _status.value = _status.value.copy(odmStatus = msg)
                log(msg)
                if (status in listOf("COMPLETED","FAILED","CANCELED")) break
            } catch (_: Exception) { break }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMediaList(json: String, filter: String): List<Pair<String, Long>> {
        return try {
            val arr = JSONObject(json).optJSONArray("MediaFileList") ?: JSONArray(json)
            (0 until arr.length()).mapNotNull {
                val item = arr.getJSONObject(it)
                val path = item.optString("FilePath", "")
                val size = item.optLong("Size", 0L)
                if (path.uppercase().endsWith(filter.uppercase())) path to size else null
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 30_000
        return conn.inputStream.bufferedReader().readText()
    }

    private fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.write(body.toByteArray())
        return conn.inputStream.bufferedReader().readText()
    }

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.inputStream.use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }
    }

    private fun uploadImages(
        odmUrl: String, taskId: String, images: Array<File>
    ) {
        // NodeODM multipart upload
        val boundary = "GF${System.currentTimeMillis()}"
        for ((i, img) in images.withIndex()) {
            val conn = URL("$odmUrl/task/new/upload/$taskId").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.doOutput = true
            conn.outputStream.bufferedWriter().use { w ->
                w.write("--$boundary\r\n")
                w.write("Content-Disposition: form-data; name=\"images\"; filename=\"${img.name}\"\r\n")
                w.write("Content-Type: image/jpeg\r\n\r\n")
                w.flush()
                conn.outputStream.write(img.readBytes())
                conn.outputStream.bufferedWriter().also { it.write("\r\n--$boundary--\r\n"); it.flush() }
            }
            conn.responseCode   // trigger send
            if (i % 10 == 0) log("Uploaded ${i+1}/${images.size}")
        }
    }

    private val isActive get() = scope.isActive

    private fun log(msg: String) {
        val cur = (_status.value.log + msg).takeLast(200)
        _status.value = _status.value.copy(log = cur)
    }
}
