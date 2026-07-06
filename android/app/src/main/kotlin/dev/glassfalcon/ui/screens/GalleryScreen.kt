// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.glassfalcon.core.FlightViewModel
import dev.glassfalcon.core.MediaItem
import dev.glassfalcon.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which media types the grid is currently showing. */
private enum class MediaFilter(val label: String) { ALL("All"), PHOTOS("Photos"), VIDEOS("Videos") }

/** Grid ordering. */
private enum class MediaSort(val label: String) {
    NEWEST("Newest"), OLDEST("Oldest"), LARGEST("Largest"), NAME("Name"),
}

/**
 * Full-featured local gallery over the `DCIM/GlassFalcon` folder OffloadManager downloads into
 * (see [dev.glassfalcon.core.LocalFolderMediaRepository]), no drone connection required, it's a
 * directory listing of files already on the phone.
 *
 * Beyond the basic grid it grew from, this now supports: a summary header (count + total size),
 * All/Photos/Videos filtering, sort (newest/oldest/largest/name), date section headers,
 * long-press multi-select with bulk share (via [FileProvider]) and bulk delete, and per-cell
 * video-duration badges. A Settings-tab screen (plain surfaces, no flight-HUD `glass()` styling).
 *
 * [onGoToOffload], if provided, jumps the Settings tab row over to Offload from the empty state.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(vm: FlightViewModel, onGoToOffload: (() -> Unit)? = null) {
    val allItems by vm.mediaRepo.items.collectAsState()
    LaunchedEffect(Unit) { vm.refreshGallery() }
    val context = LocalContext.current

    var filter by remember { mutableStateOf(MediaFilter.ALL) }
    var sort by remember { mutableStateOf(MediaSort.NEWEST) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    // Selected item ids. Non-empty == selection mode.
    val selected = remember { mutableStateListOf<String>() }
    var confirmDeleteSelected by remember { mutableStateOf(false) }

    // Filtered + sorted view the grid, the viewer, and select-all all agree on.
    val shown = remember(allItems, filter, sort) {
        allItems
            .filter {
                when (filter) {
                    MediaFilter.ALL -> true
                    MediaFilter.PHOTOS -> !it.isVideo
                    MediaFilter.VIDEOS -> it.isVideo
                }
            }
            .let { list ->
                when (sort) {
                    MediaSort.NEWEST -> list.sortedByDescending { it.lastModified }
                    MediaSort.OLDEST -> list.sortedBy { it.lastModified }
                    MediaSort.LARGEST -> list.sortedByDescending { it.sizeBytes }
                    MediaSort.NAME -> list.sortedBy { it.filename.lowercase() }
                }
            }
    }
    // Prune selections that no longer exist (e.g. after a delete or filter change hides them).
    LaunchedEffect(shown) {
        val visible = shown.mapTo(HashSet()) { it.id }
        selected.retainAll { it in visible }
    }

    val selectionMode = selected.isNotEmpty()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selectionMode) {
                SelectionBar(
                    count = selected.size,
                    onClose = { selected.clear() },
                    onSelectAll = {
                        selected.clear()
                        selected.addAll(shown.map { it.id })
                    },
                    onShare = {
                        val toShare = shown.filter { it.id in selected }
                        shareMedia(context, vm, toShare)
                    },
                    onDelete = { confirmDeleteSelected = true },
                )
            } else {
                GalleryHeader(itemCount = allItems.size, totalBytes = allItems.sumOf { it.sizeBytes }, context = context)
                FilterSortBar(
                    filter = filter, onFilter = { filter = it },
                    sort = sort, onSort = { sort = it },
                    photoCount = allItems.count { !it.isVideo },
                    videoCount = allItems.count { it.isVideo },
                )
            }

            if (shown.isEmpty()) {
                if (allItems.isEmpty()) EmptyGalleryState(onGoToOffload)
                else EmptyFilterState(filter)
            } else {
                // Group by calendar day for section headers, preserving the sorted order within
                // each group. For NAME/LARGEST sorts a date grouping is less meaningful, so those
                // fall back to a single flat "All" section.
                val grouped: List<Pair<String, List<MediaItem>>> =
                    if (sort == MediaSort.NEWEST || sort == MediaSort.OLDEST) {
                        shown.groupBy { dayLabel(it.lastModified) }.toList()
                    } else {
                        listOf(sort.label to shown)
                    }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    grouped.forEach { (label, groupItems) ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "hdr_$label") {
                            SectionHeader(label, groupItems.size)
                        }
                        items(groupItems, key = { it.id }) { item ->
                            GalleryCell(
                                vm = vm,
                                item = item,
                                selected = item.id in selected,
                                selectionMode = selectionMode,
                                onClick = {
                                    if (selectionMode) toggle(selected, item.id)
                                    else viewerIndex = shown.indexOf(item)
                                },
                                onLongClick = { toggle(selected, item.id) },
                            )
                        }
                    }
                }
            }
        }

        viewerIndex?.let { idx ->
            MediaViewerScreen(
                vm = vm,
                items = shown,
                initialIndex = idx.coerceIn(0, shown.lastIndex),
                onClose = { viewerIndex = null },
            )
        }
    }

    if (confirmDeleteSelected) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("Delete $n item${if (n == 1) "" else "s"}?") },
            text = {
                Text(
                    "This permanently deletes $n file${if (n == 1) "" else "s"} from this phone's " +
                    "DCIM/GlassFalcon folder. It does not touch the drone's own SD card.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteSelected = false
                    val toDelete = shown.filter { it.id in selected }
                    selected.clear()
                    vm.deleteMedia(toDelete)
                }) { Text("Delete", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteSelected = false }) { Text("Cancel") } },
        )
    }
}

private fun toggle(set: androidx.compose.runtime.snapshots.SnapshotStateList<String>, id: String) {
    if (id in set) set.remove(id) else set.add(id)
}

@Composable
private fun GalleryHeader(itemCount: Int, totalBytes: Long, context: Context) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Gallery", color = TextPri, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            val size = Formatter.formatShortFileSize(context, totalBytes)
            Text(
                "$itemCount item${if (itemCount == 1) "" else "s"} · $size",
                color = TextSec, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun FilterSortBar(
    filter: MediaFilter, onFilter: (MediaFilter) -> Unit,
    sort: MediaSort, onSort: (MediaSort) -> Unit,
    photoCount: Int, videoCount: Int,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            MediaFilter.values().forEach { f ->
                val n = when (f) {
                    MediaFilter.ALL -> photoCount + videoCount
                    MediaFilter.PHOTOS -> photoCount
                    MediaFilter.VIDEOS -> videoCount
                }
                FilterChip(
                    selected = filter == f,
                    onClick = { onFilter(f) },
                    label = { Text("${f.label} ($n)", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Gold.copy(alpha = 0.22f),
                        selectedLabelColor = Gold,
                    ),
                )
            }
        }
        var sortMenu by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { sortMenu = true }) {
                Text("Sort: ${sort.label}", color = TextSec, fontSize = 12.sp)
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                MediaSort.values().forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label, color = if (s == sort) Gold else TextPri) },
                        onClick = { onSort(s); sortMenu = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int, onClose: () -> Unit, onSelectAll: () -> Unit,
    onShare: () -> Unit, onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Gold.copy(alpha = 0.14f)).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel selection", tint = TextPri) }
        Text("$count selected", color = TextPri, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = onSelectAll) { Text("All", color = Gold, fontSize = 13.sp) }
        IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share selected", tint = TextPri) }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete selected", tint = Red) }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextPri, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text("($count)", color = TextSec, fontSize = 11.sp)
    }
}

@Composable
private fun EmptyGalleryState(onGoToOffload: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No media yet", color = TextPri, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Use Offload to sync photos and videos from the drone, anything pulled down into " +
            "DCIM/GlassFalcon shows up here automatically.",
            color = TextSec, fontSize = 12.sp, textAlign = TextAlign.Center,
        )
        if (onGoToOffload != null) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onGoToOffload,
                colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
            ) { Text("Go to Offload", color = Gold) }
        }
    }
}

@Composable
private fun EmptyFilterState(filter: MediaFilter) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No ${filter.label.lowercase()} here", color = TextSec, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCell(
    vm: FlightViewModel,
    item: MediaItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var durationMs by remember(item.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(item.id) { bitmap = vm.mediaRepo.thumbnail(item) }
    LaunchedEffect(item.id) { if (item.isVideo) durationMs = vm.mediaRepo.meta(item).durationMs }

    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = Panel),
    ) {
        Box(Modifier.fillMaxSize()) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = item.filename,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("…", color = TextSec, fontSize = 11.sp)
                }
            }
            if (item.isVideo) {
                Icon(
                    Icons.Filled.PlayArrow, contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                )
                durationMs?.let { d ->
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(3.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                    ) {
                        Text(formatDuration(d), color = Color.White, fontSize = 9.sp, fontFamily = IbmPlexMono)
                    }
                }
            }
            // Selection affordances, a dim veil + checkmark when picked, and (once in selection
            // mode) an empty ring on the unpicked ones so it's clear the whole grid is selectable.
            if (selected) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                    .border(2.dp, Gold))
                Box(
                    Modifier.align(Alignment.TopStart).padding(3.dp).size(18.dp)
                        .clip(RoundedCornerShape(50)).background(Gold),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, "Selected", tint = Color.Black, modifier = Modifier.size(13.dp)) }
            } else if (selectionMode) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(3.dp).size(18.dp)
                        .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f))
                        .border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(50)),
                )
            }
        }
    }
}

/** mm:ss (or h:mm:ss for long clips) from a millisecond duration. */
private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private val daySdf = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.US)
private fun dayLabel(ms: Long): String = daySdf.format(Date(ms))

/** Hands one or more media files to Android's native Share sheet (Quick Share / Nearby Share show
 *  up automatically). A [FileProvider] content URI is required to share to another app on modern
 *  Android; see `file_paths.xml`'s `gallery_media` external-path entry. */
private fun shareMedia(context: Context, vm: FlightViewModel, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val authority = "${context.packageName}.fileprovider"
    val uris = ArrayList(items.map { FileProvider.getUriForFile(context, authority, it.file) })
    val mime = when {
        items.all { !it.isVideo } -> "image/*"
        items.all { it.isVideo } -> "video/*"
        else -> "*/*"
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uris[0])
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(Intent.createChooser(intent, "Share ${items.size} item${if (items.size == 1) "" else "s"}"))
}
