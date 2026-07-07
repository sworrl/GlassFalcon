// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One photo or video file the gallery can show. [id] is just the absolute path, stable and
 *  unique for local files, no need for a real ID scheme on top of it. */
data class MediaItem(
    val id: String,
    val file: File,
    val filename: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isVideo: Boolean,
)

/** Extra per-item detail that costs a decode/probe to obtain, so it's loaded lazily (per cell /
 *  on the viewer's info sheet) rather than eagerly for the whole folder on every refresh. Any
 *  field is null when unavailable (e.g. duration on a photo, dimensions on an undecodable file). */
data class MediaMeta(
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * Source of media for [dev.glassfalcon.ui.screens.GalleryScreen]. [LocalFolderMediaRepository]
 * (below) is the only implementation today, backed by whatever OffloadManager has already
 * downloaded to disk.
 *
 * FUTURE EXTENSION SEAM: a `DroneLiveMediaRepository` browsing the aircraft's SD card directly
 * over the live RC link, no file-copy round trip through offload first, would implement this
 * same interface. It does NOT exist yet and must not be stubbed in. Whether this aircraft (Mavic
 * 2 Pro / wm240) exposes any of that over the DUML link at all is unconfirmed: three candidate
 * transports were researched (DUML file-list/download opcodes, the legacy DJI FTP daemon, USB
 * mass storage) and none are confirmed working on wm240, see OffloadManager.probeFtpAccess's
 * doc comment. Do not guess at DUML opcodes for this and present them as working; build
 * `DroneLiveMediaRepository` only once a transport is actually confirmed on real hardware.
 */
interface MediaRepository {
    /** Current items, newest first. Observers just collect this, call [refresh] to re-scan. */
    val items: StateFlow<List<MediaItem>>

    /** Re-scans the backing store and republishes [items]. */
    fun refresh()

    /** Deletes [item] from the backing store and refreshes [items]. Returns true on success. */
    fun delete(item: MediaItem): Boolean

    /** Deletes several items in one pass, refreshing [items] only once at the end (not per file).
     *  Returns the number actually deleted. */
    fun delete(itemsToDelete: Collection<MediaItem>): Int

    /** A `Uri` suitable for full-screen playback (Image / ExoPlayer) of [item]. */
    fun uriFor(item: MediaItem): Uri

    /** Loads (and caches) a thumbnail bitmap for [item]. Suspends, call from a coroutine, not
     *  the main-thread composition pass. Returns null if the file can't be decoded. */
    suspend fun thumbnail(item: MediaItem): Bitmap?

    /** Loads (and caches) extra detail, video duration, pixel dimensions. Suspends. Returns an
     *  all-null [MediaMeta] rather than throwing if nothing can be read. */
    suspend fun meta(item: MediaItem): MediaMeta
}

/** Extensions OffloadManager's downloads can actually produce today. */
private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov")

/**
 * Lists whatever's already sitting in the local offload folder, same
 * `DCIM/GlassFalcon` directory OffloadManager's WiFi/ADB offload writes into (see
 * OffloadScreen's `defaultDir`). No drone connection needed: this is just a directory listing,
 * so it works today regardless of whether any live SD-card access ever gets confirmed.
 */
class LocalFolderMediaRepository(
    private val context: Context,
    private val dir: File,
) : MediaRepository {

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    override val items: StateFlow<List<MediaItem>> = _items

    // Thumbnails are decoded full-size images/video frames scaled down, cheap to keep a modest
    // number of them around, expensive to keep re-decoding on every recomposition/scroll. A
    // fixed slice of the JVM heap (1/8th) is the standard LruCache sizing pattern; no need for
    // anything heavier (Coil/Glide) for a folder that's realistically dozens–low hundreds of
    // files, not an infinite feed.
    private val thumbCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8L / 1024L).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    // Metadata is tiny (three nullable numbers) and immutable per file, so a plain unbounded map
    // keyed by path is fine, a folder is dozens–low hundreds of files, and entries are dropped
    // when the file is deleted.
    private val metaCache = ConcurrentHashMap<String, MediaMeta>()

    init { refresh() }

    override fun refresh() {
        val files = dir.listFiles { f -> f.isFile && isMedia(f) } ?: emptyArray()
        _items.value = files
            .sortedByDescending { it.lastModified() }
            .map {
                MediaItem(
                    id = it.absolutePath,
                    file = it,
                    filename = it.name,
                    sizeBytes = it.length(),
                    lastModified = it.lastModified(),
                    isVideo = it.extension.lowercase() in VIDEO_EXTENSIONS,
                )
            }
    }

    override fun delete(item: MediaItem): Boolean {
        thumbCache.remove(item.id)
        metaCache.remove(item.id)
        // Overwrite-then-unlink: captured media can carry GPS EXIF, so wipe rather than plain delete.
        SecureStore.secureDelete(item.file)
        val ok = !item.file.exists()
        if (ok) refresh()
        return ok
    }

    override fun delete(itemsToDelete: Collection<MediaItem>): Int {
        var deleted = 0
        for (item in itemsToDelete) {
            thumbCache.remove(item.id)
            metaCache.remove(item.id)
            SecureStore.secureDelete(item.file)
            if (!item.file.exists()) deleted++
        }
        if (deleted > 0) refresh()
        return deleted
    }

    override fun uriFor(item: MediaItem): Uri = Uri.fromFile(item.file)

    override suspend fun thumbnail(item: MediaItem): Bitmap? {
        thumbCache.get(item.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION") // File-based ThumbnailUtils.createImageThumbnail(File,...)
            // is API 29+; the (path, kind) overload used here covers this app's minSdk 26 too.
            val bmp = try {
                if (item.isVideo) {
                    ThumbnailUtils.createVideoThumbnail(item.file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
                } else {
                    ThumbnailUtils.createImageThumbnail(item.file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                }
            } catch (_: Exception) { null }
            if (bmp != null) thumbCache.put(item.id, bmp)
            bmp
        }
    }

    override suspend fun meta(item: MediaItem): MediaMeta {
        metaCache[item.id]?.let { return it }
        return withContext(Dispatchers.IO) {
            val m = if (item.isVideo) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(item.file.absolutePath)
                    fun i(key: Int) = r.extractMetadata(key)?.toLongOrNull()
                    MediaMeta(
                        durationMs = i(MediaMetadataRetriever.METADATA_KEY_DURATION),
                        width = i(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt(),
                        height = i(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt(),
                    )
                } catch (_: Exception) {
                    MediaMeta()
                } finally {
                    try { r.release() } catch (_: Exception) {}
                }
            } else {
                try {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(item.file.absolutePath, opts)
                    MediaMeta(
                        width = opts.outWidth.takeIf { it > 0 },
                        height = opts.outHeight.takeIf { it > 0 },
                    )
                } catch (_: Exception) {
                    MediaMeta()
                }
            }
            metaCache[item.id] = m
            m
        }
    }

    private fun isMedia(f: File): Boolean {
        val ext = f.extension.lowercase()
        return ext in PHOTO_EXTENSIONS || ext in VIDEO_EXTENSIONS
    }
}
