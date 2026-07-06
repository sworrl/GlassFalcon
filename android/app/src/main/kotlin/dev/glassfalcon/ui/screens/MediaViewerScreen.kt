// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.glassfalcon.core.FlightViewModel
import dev.glassfalcon.core.MediaItem
import dev.glassfalcon.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen photo/video viewer, swipeable via [HorizontalPager] between everything currently
 * in the gallery, opened from [GalleryScreen] on a thumbnail tap. Same Settings-overlay
 * screen, so no `glass()` styling; this is a plain black scrim over the pager, matching how a
 * full-screen media view reads in any gallery app.
 *
 * Deleting the current item removes it from [MediaRepository][dev.glassfalcon.core.MediaRepository]
 * and, because [items] is the live StateFlow list from the caller, the pager's page count
 * shrinks accordingly; the viewer closes itself if that empties the whole gallery.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewerScreen(
    vm: FlightViewModel,
    items: List<MediaItem>,
    initialIndex: Int,
    onClose: () -> Unit,
) {
    if (items.isEmpty()) { onClose(); return }

    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, items.lastIndex)) { items.size }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(items.size) { if (items.isEmpty()) onClose() }

    val current = items.getOrNull(pagerState.currentPage.coerceIn(0, items.lastIndex))

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val item = items.getOrNull(page)
            if (item != null) {
                if (item.isVideo) {
                    GalleryVideoPlayer(uri = vm.mediaRepo.uriFor(item), modifier = Modifier.fillMaxSize())
                } else {
                    GalleryImage(item, Modifier.fillMaxSize())
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
            Text(
                current?.filename ?: "", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = { confirmDelete = true }, enabled = current != null) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.White)
            }
        }
    }

    if (confirmDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${current.filename}?") },
            text = {
                Text(
                    "This permanently deletes the file from this phone's DCIM/GlassFalcon " +
                    "folder. It does not touch the drone's own SD card.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteMedia(current)
                }) { Text("Delete", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GalleryImage(item: MediaItem, modifier: Modifier = Modifier) {
    // Full-resolution one-shot decode, this is a single full-screen image, not a grid of them,
    // so there's no need to route it through MediaRepository's small thumbnail LruCache.
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id) {
        bitmap = withContext(Dispatchers.IO) {
            try { BitmapFactory.decodeFile(item.file.absolutePath) } catch (_: Exception) { null }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.filename,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(color = Gold)
        }
    }
}

/** Local file playback via the media3 ExoPlayer already used for the RTSP relay preview (see
 *  CameraScreen's `VideoPlayer`), reused here as its own small composable, rather than
 *  reusing that one directly, because this one needs on-screen playback controls
 *  (`useController = true`) where the live relay preview deliberately has none. */
@Composable
private fun GalleryVideoPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(ExoMediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(uri) { onDispose { player.release() } }
    AndroidView(
        factory = { c -> PlayerView(c).apply { this.player = player; useController = true } },
        modifier = modifier,
    )
}
