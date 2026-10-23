package com.geostamp.camera.ui.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geostamp.camera.model.PhotoItem
import com.geostamp.camera.ui.components.rememberPhotoPainter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Photos taken on one calendar day, newest first (the repository already sorts by date taken). */
private data class DaySection(val day: LocalDate, val label: String, val photos: List<PhotoItem>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onPhotoClick: (PhotoItem) -> Unit,
    modifier: Modifier = Modifier,
    onOpenCamera: () -> Unit = onBack
) {
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val hasLoaded by viewModel.hasLoaded.collectAsStateWithLifecycle()

    // Re-query on every resume so deletions/edits made elsewhere (other gallery apps, the editor) show up.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPhotos()
        onPauseOrDispose { }
    }

    val sections = remember(photos) { groupByDay(photos) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Gallery", style = MaterialTheme.typography.titleLarge)
                        if (photos.isNotEmpty()) {
                            Text(
                                "${photos.size} photo${if (photos.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        when {
            photos.isNotEmpty() -> PhotoGrid(
                sections = sections,
                contentPadding = paddingValues,
                onPhotoClick = onPhotoClick
            )
            // Avoid flashing the empty state before the first query finishes.
            !hasLoaded -> Box(Modifier.fillMaxSize().padding(paddingValues))
            else -> EmptyGallery(
                onOpenCamera = onOpenCamera,
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@Composable
private fun PhotoGrid(
    sections: List<DaySection>,
    contentPadding: PaddingValues,
    onPhotoClick: (PhotoItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
            start = 2.dp,
            end = 2.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        sections.forEach { section ->
            item(
                key = "header-${section.day}",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "header"
            ) {
                Text(
                    text = section.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 8.dp)
                )
            }
            items(section.photos, key = { it.id }, contentType = { "photo" }) { photo ->
                PhotoGridItem(photo = photo, onClick = { onPhotoClick(photo) })
            }
        }
    }
}

@Composable
fun PhotoGridItem(
    photo: PhotoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = rememberPhotoPainter(photo),
            contentDescription = photo.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        if (photo.isEditable) {
            // Small, low-contrast hint that location/date can still be changed.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Editable",
                    tint = Color.White,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyGallery(onOpenCamera: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "No photos yet",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Photos you take with GeoStamp Camera appear here, stamped with their location, date and time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = onOpenCamera) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Take a photo")
            }
        }
    }
}

private fun groupByDay(photos: List<PhotoItem>): List<DaySection> {
    if (photos.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val yesterday = today.minusDays(1)
    val headerFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())
    // LinkedHashMap keeps the repository's newest-first order for both days and photos.
    return photos
        .groupBy { Instant.ofEpochMilli(it.dateTaken).atZone(zone).toLocalDate() }
        .map { (day, dayPhotos) ->
            val label = when (day) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> day.format(headerFormat)
            }
            DaySection(day, label, dayPhotos)
        }
}
