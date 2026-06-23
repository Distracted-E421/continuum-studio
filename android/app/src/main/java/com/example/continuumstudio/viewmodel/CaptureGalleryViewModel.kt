package com.example.continuumstudio.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class CaptureGalleryViewModel : ViewModel() {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val json = Json { ignoreUnknownKeys = true }
    
    private val _state = MutableStateFlow(GalleryState())
    val state: StateFlow<GalleryState> = _state.asStateFlow()
    
    // Default to Cognix on Obsidian via Tailscale
    private var cognixUrl = "http://100.95.167.76:4100"
    
    fun setServerUrl(url: String) {
        cognixUrl = url
        refresh()
    }
    
    fun refresh() {
        loadCaptures()
    }
    
    fun loadCaptures(limit: Int = 50, offset: Int = 0) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            
            try {
                val captures = fetchCaptures(limit, offset)
                _state.value = _state.value.copy(
                    captures = captures,
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load captures", e)
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load captures"
                )
            }
        }
    }
    
    fun search(query: String) {
        if (query.isBlank()) {
            loadCaptures()
            return
        }
        
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            
            try {
                val captures = searchCaptures(query)
                _state.value = _state.value.copy(
                    captures = captures,
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to search captures", e)
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "Search failed"
                )
            }
        }
    }
    
    fun deleteCapture(captureId: String) {
        // TODO: Implement delete when backend supports it
        Log.w(TAG, "Delete not yet implemented for capture: $captureId")
    }
    
    private suspend fun fetchCaptures(limit: Int, offset: Int): List<CaptureItem> = 
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$cognixUrl/api/visual/list?limit=$limit&offset=$offset")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: throw Exception("Empty response")
                val listResponse = json.decodeFromString<CaptureListResponse>(body)
                listResponse.captures.map { it.toCaptureItem(cognixUrl) }
            } else {
                throw Exception("Failed to fetch captures: ${response.code}")
            }
        }
    
    private suspend fun searchCaptures(query: String, limit: Int = 20): List<CaptureItem> = 
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$cognixUrl/api/visual/search?q=$query&limit=$limit")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val body = response.body?.string() ?: throw Exception("Empty response")
                val listResponse = json.decodeFromString<CaptureListResponse>(body)
                listResponse.captures.map { it.toCaptureItem(cognixUrl) }
            } else {
                throw Exception("Search failed: ${response.code}")
            }
        }
    
    companion object {
        private const val TAG = "CaptureGalleryVM"
    }
}

data class GalleryState(
    val captures: List<CaptureItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

data class CaptureItem(
    val id: String,
    val timestamp: Long,
    val source: String,
    val format: String,
    val width: Int?,
    val height: Int?,
    val imageUrl: String,
    val description: String?
)

@Serializable
data class CaptureListResponse(
    val captures: List<CaptureData>,
    val count: Int
)

@Serializable
data class CaptureData(
    val id: String,
    val timestamp: Long,
    val source: String? = null,
    val format: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val image_url: String? = null,
    val context: CaptureContext? = null
) {
    fun toCaptureItem(baseUrl: String): CaptureItem {
        val fullImageUrl = if (image_url?.startsWith("/") == true) {
            "$baseUrl$image_url"
        } else {
            image_url ?: "$baseUrl/api/visual/image/$id"
        }
        
        return CaptureItem(
            id = id,
            timestamp = timestamp,
            source = source ?: "unknown",
            format = format ?: "jpeg",
            width = width,
            height = height,
            imageUrl = fullImageUrl,
            description = context?.description
        )
    }
}

@Serializable
data class CaptureContext(
    val description: String? = null,
    val triggeredBy: String? = null,
    val sessionId: String? = null,
    val agentId: String? = null,
    val location: String? = null,
    val timestamp: Long? = null
)
