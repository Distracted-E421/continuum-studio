package com.example.continuumstudio.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.continuumstudio.viewmodel.CaptureGalleryViewModel
import com.example.continuumstudio.viewmodel.CaptureItem
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureGalleryScreen(
    viewModel: CaptureGalleryViewModel = viewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToCamera: () -> Unit,
    onCaptureSelected: ((CaptureItem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var selectedCapture by remember { mutableStateOf<CaptureItem?>(null) }
    
    LaunchedEffect(Unit) {
        viewModel.loadCaptures()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    if (showSearch) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { 
                                searchQuery = it
                                viewModel.search(it)
                            },
                            placeholder = { Text("Search captures...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("Capture Gallery")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, "Search")
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    IconButton(onClick = onNavigateToCamera) {
                        Icon(Icons.Default.CameraAlt, "Camera")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                state.error != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Error: ${state.error}",
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.refresh() }) {
                            Text("Retry")
                        }
                    }
                }
                state.captures.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No captures yet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onNavigateToCamera) {
                            Text("Take a Capture")
                        }
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.captures) { capture ->
                            CaptureGridItem(
                                capture = capture,
                                onClick = {
                                    selectedCapture = capture
                                    onCaptureSelected?.invoke(capture)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Detail dialog when capture is selected
    selectedCapture?.let { capture ->
        CaptureDetailDialog(
            capture = capture,
            onDismiss = { selectedCapture = null }
        )
    }
}

@Composable
private fun CaptureGridItem(
    capture: CaptureItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(capture.imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = capture.description ?: "Capture ${capture.id}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            
            // Source badge
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                shape = RoundedCornerShape(bottomEnd = 8.dp),
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Text(
                    text = capture.source,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(4.dp)
                )
            }
            
            // Timestamp at bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
            ) {
                Text(
                    text = formatTimestamp(capture.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
    }
}

@Composable
private fun CaptureDetailDialog(
    capture: CaptureItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Capture Details") },
        text = {
            Column {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(capture.imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = capture.description,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(
                            if (capture.width != null && capture.height != null && capture.height > 0)
                                capture.width.toFloat() / capture.height.toFloat()
                            else 1f
                        )
                        .clip(RoundedCornerShape(8.dp))
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                DetailRow("ID", capture.id.take(8) + "...")
                DetailRow("Source", capture.source)
                DetailRow("Time", formatTimestamp(capture.timestamp, detailed = true))
                capture.width?.let { w ->
                    capture.height?.let { h ->
                        DetailRow("Size", "${w}x$h")
                    }
                }
                capture.description?.let {
                    DetailRow("Description", it)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatTimestamp(timestamp: Long, detailed: Boolean = false): String {
    val date = Date(timestamp)
    val format = if (detailed) {
        SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault())
    } else {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    }
    return format.format(date)
}
