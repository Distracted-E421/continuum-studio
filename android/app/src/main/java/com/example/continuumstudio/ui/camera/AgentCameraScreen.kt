package com.example.continuumstudio.ui.camera

import android.Manifest
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.continuumstudio.camera.AgentCameraClient.Destination
import com.example.continuumstudio.viewmodel.AgentCameraEvent
import com.example.continuumstudio.viewmodel.AgentCameraViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.util.concurrent.Executors

/**
 * Agent Camera Screen - Capture images and send to agents.
 * 
 * Features:
 * - Live camera preview
 * - Single-shot capture with "Send to Agent" button
 * - Preview before sending
 * - Connection status indicators
 * - Optional description for context
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AgentCameraScreen(
    viewModel: AgentCameraViewModel = viewModel(),
    onNavigateBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val events by viewModel.events.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var description by remember { mutableStateOf("") }
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(events) {
        when (val event = events) {
            is AgentCameraEvent.UploadSuccess -> {
                Log.i("AgentCamera", "Upload success: ${event.imageId}")
                viewModel.clearEvent()
            }
            is AgentCameraEvent.UploadError -> {
                Log.e("AgentCamera", "Upload error: ${event.message}")
                viewModel.clearEvent()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Camera") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    ConnectionIndicator(
                        synapsixConnected = state.synapsixConnected,
                        cognixConnected = state.cognixConnected
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!cameraPermission.status.isGranted) {
                CameraPermissionRequest(
                    onRequestPermission = { cameraPermission.launchPermissionRequest() }
                )
            } else if (state.showPreview && state.capturedImage != null) {
                ImagePreviewPanel(
                    bitmap = state.capturedImage!!,
                    description = description,
                    onDescriptionChange = { description = it },
                    onSend = { viewModel.sendToAgent(description.ifBlank { null }) },
                    onCancel = { viewModel.cancelPreview() },
                    isUploading = state.isUploading,
                    selectedDestination = state.selectedDestination,
                    onDestinationChange = { viewModel.setDestination(it) }
                )
            } else {
                CameraPreview(
                    modifier = Modifier.weight(1f),
                    onImageCaptureReady = { imageCapture = it }
                )
                
                CaptureControls(
                    onCapture = {
                        imageCapture?.let { capture ->
                            takePicture(capture, context) { bitmap ->
                                viewModel.onImageCaptured(bitmap)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ConnectionIndicator(
    synapsixConnected: Boolean,
    cognixConnected: Boolean
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 16.dp)
    ) {
        StatusDot(
            connected = synapsixConnected,
            label = "Syn"
        )
        StatusDot(
            connected = cognixConnected,
            label = "Cog"
        )
    }
}

@Composable
private fun StatusDot(connected: Boolean, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (connected) Color.Green else Color.Red)
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CameraPermissionRequest(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("Camera permission required")
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier = Modifier,
    onImageCaptureReady: (ImageCapture) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                onImageCaptureReady(imageCapture)

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                } catch (e: Exception) {
                    Log.e("AgentCamera", "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}

@Composable
private fun CaptureControls(onCapture: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        FilledIconButton(
            onClick = onCapture,
            modifier = Modifier.size(72.dp)
        ) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = "Capture",
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun ImagePreviewPanel(
    bitmap: Bitmap,
    description: String,
    onDescriptionChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    isUploading: Boolean,
    selectedDestination: Destination,
    onDestinationChange: (Destination) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Captured image",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text("Description (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedDestination == Destination.SYNAPSIX,
                onClick = { onDestinationChange(Destination.SYNAPSIX) },
                label = { Text("Synapsix") }
            )
            FilterChip(
                selected = selectedDestination == Destination.COGNIX,
                onClick = { onDestinationChange(Destination.COGNIX) },
                label = { Text("Cognix") }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                enabled = !isUploading
            ) {
                Text("Retake")
            }
            
            Button(
                onClick = onSend,
                modifier = Modifier.weight(1f),
                enabled = !isUploading
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send to Agent")
                }
            }
        }
    }
}

private fun takePicture(
    imageCapture: ImageCapture,
    context: android.content.Context,
    onCapture: (Bitmap) -> Unit
) {
    val executor = Executors.newSingleThreadExecutor()
    
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = image.toBitmap()
                image.close()
                onCapture(bitmap)
            }

            override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                Log.e("AgentCamera", "Capture failed", exception)
            }
        }
    )
}
