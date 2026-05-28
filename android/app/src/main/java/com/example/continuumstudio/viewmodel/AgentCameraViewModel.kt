package com.example.continuumstudio.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.camera.AgentCameraClient
import com.example.continuumstudio.camera.AgentCameraClient.CaptureContext
import com.example.continuumstudio.camera.AgentCameraClient.Destination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for Agent Camera functionality.
 * 
 * Manages camera state, image capture, and upload to agents.
 */
class AgentCameraViewModel : ViewModel() {
    private val client = AgentCameraClient()

    private val _state = MutableStateFlow(AgentCameraState())
    val state: StateFlow<AgentCameraState> = _state.asStateFlow()

    private val _events = MutableStateFlow<AgentCameraEvent?>(null)
    val events: StateFlow<AgentCameraEvent?> = _events.asStateFlow()

    init {
        checkConnections()
    }

    fun checkConnections() {
        viewModelScope.launch {
            val synapsixConnected = client.checkConnection(Destination.SYNAPSIX)
            val cognixConnected = client.checkConnection(Destination.COGNIX)
            
            _state.value = _state.value.copy(
                synapsixConnected = synapsixConnected,
                cognixConnected = cognixConnected
            )
        }
    }

    fun onImageCaptured(bitmap: Bitmap) {
        _state.value = _state.value.copy(
            capturedImage = bitmap,
            showPreview = true
        )
    }

    fun sendToAgent(description: String? = null) {
        val bitmap = _state.value.capturedImage ?: return
        
        viewModelScope.launch {
            _state.value = _state.value.copy(isUploading = true)
            
            val context = CaptureContext(
                triggeredBy = "user",
                description = description,
                sessionId = _state.value.currentSessionId
            )
            
            val destination = _state.value.selectedDestination
            val result = client.sendImage(bitmap, context, destination)
            
            result.fold(
                onSuccess = { response ->
                    _state.value = _state.value.copy(
                        isUploading = false,
                        showPreview = false,
                        capturedImage = null,
                        lastUploadedId = response.imageId
                    )
                    _events.value = AgentCameraEvent.UploadSuccess(response.imageId)
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(isUploading = false)
                    _events.value = AgentCameraEvent.UploadError(error.message ?: "Upload failed")
                }
            )
        }
    }

    fun cancelPreview() {
        _state.value = _state.value.copy(
            showPreview = false,
            capturedImage = null
        )
    }

    fun setDestination(destination: Destination) {
        _state.value = _state.value.copy(selectedDestination = destination)
    }

    fun setSessionId(sessionId: String?) {
        _state.value = _state.value.copy(currentSessionId = sessionId)
    }

    fun clearEvent() {
        _events.value = null
    }
}

data class AgentCameraState(
    val capturedImage: Bitmap? = null,
    val showPreview: Boolean = false,
    val isUploading: Boolean = false,
    val synapsixConnected: Boolean = false,
    val cognixConnected: Boolean = false,
    val selectedDestination: Destination = Destination.SYNAPSIX,
    val currentSessionId: String? = null,
    val lastUploadedId: String? = null
)

sealed class AgentCameraEvent {
    data class UploadSuccess(val imageId: String?) : AgentCameraEvent()
    data class UploadError(val message: String) : AgentCameraEvent()
    data class AgentTrigger(val sessionId: String, val prompt: String?) : AgentCameraEvent()
}
