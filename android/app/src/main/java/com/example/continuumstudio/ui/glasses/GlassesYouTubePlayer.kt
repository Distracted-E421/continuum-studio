package com.example.continuumstudio.ui.glasses

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.continuumstudio.data.GlassesColors

/**
 * YouTube player for glasses display using WebView embeds.
 * 
 * Supports iframe embeds for videos that allow embedding.
 * Not all videos support embedding - creator/channel settings vary.
 * 
 * Usage:
 *   GlassesYouTubePlayer(videoId = "dQw4w9WgXcQ")
 *   GlassesYouTubePlayer(videoUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
 */
@Composable
fun GlassesYouTubePlayer(
    videoId: String? = null,
    videoUrl: String? = null,
    autoplay: Boolean = true,
    showControls: Boolean = true,
    modifier: Modifier = Modifier
) {
    val resolvedVideoId = remember(videoId, videoUrl) {
        videoId ?: extractVideoId(videoUrl)
    }
    
    if (resolvedVideoId == null) {
        YouTubeErrorCard(
            message = "Invalid YouTube URL or video ID",
            modifier = modifier
        )
        return
    }
    
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GlassesColors.Transparent)
    ) {
        // WebView for YouTube embed
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    setupYouTubeWebView(this)
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                        }
                        
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            super.onReceivedError(view, errorCode, description, failingUrl)
                            hasError = true
                        }
                    }
                    
                    webChromeClient = object : WebChromeClient() {
                        // Enable fullscreen video playback
                    }
                }
            },
            update = { webView ->
                val embedHtml = buildYouTubeEmbedHtml(
                    videoId = resolvedVideoId,
                    autoplay = autoplay,
                    showControls = showControls
                )
                webView.loadDataWithBaseURL(
                    "https://www.youtube.com",
                    embedHtml,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        )
        
        // Loading indicator
        if (isLoading) {
            LoadingOverlay(modifier = Modifier.align(Alignment.Center))
        }
        
        // Error state
        if (hasError) {
            YouTubeErrorCard(
                message = "Video unavailable for embedding",
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * Mini YouTube player for status bar / small displays
 */
@Composable
fun GlassesYouTubeMini(
    title: String,
    isPlaying: Boolean,
    progress: Float = 0f,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(GlassesColors.Transparent)
            .border(1.dp, GlassesColors.GreenPrimary, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // YouTube icon placeholder
        Text(
            text = "▶",
            color = if (isPlaying) GlassesColors.GreenPrimary else GlassesColors.GreenDim,
            fontSize = 14.sp
        )
        
        // Title
        Text(
            text = title.take(40) + if (title.length > 40) "..." else "",
            color = GlassesColors.GreenPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun LoadingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(GlassesColors.Transparent, RoundedCornerShape(8.dp))
            .border(1.dp, GlassesColors.GreenDim, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "Loading video...",
            color = GlassesColors.GreenPrimary,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun YouTubeErrorCard(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(GlassesColors.Transparent, RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFFF6B6B), RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "⚠",
                color = Color(0xFFFF6B6B),
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                color = GlassesColors.GreenPrimary,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "This video may not allow embedding",
                color = GlassesColors.GreenDim,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Extract video ID from various YouTube URL formats
 */
private fun extractVideoId(url: String?): String? {
    if (url == null) return null
    
    return when {
        // Standard watch URL: youtube.com/watch?v=VIDEO_ID
        url.contains("youtube.com/watch") -> {
            url.substringAfter("v=").substringBefore("&")
        }
        // Short URL: youtu.be/VIDEO_ID
        url.contains("youtu.be/") -> {
            url.substringAfter("youtu.be/").substringBefore("?")
        }
        // Embed URL: youtube.com/embed/VIDEO_ID
        url.contains("youtube.com/embed/") -> {
            url.substringAfter("embed/").substringBefore("?")
        }
        // Already a video ID (11 chars, alphanumeric with _ and -)
        url.matches(Regex("^[a-zA-Z0-9_-]{11}$")) -> url
        else -> null
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun setupYouTubeWebView(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        loadWithOverviewMode = true
        useWideViewPort = true
        allowContentAccess = true
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        
        // Optimize for video playback
        cacheMode = WebSettings.LOAD_DEFAULT
        setSupportZoom(false)
        builtInZoomControls = false
    }
    
    // Set black background for AR transparency
    webView.setBackgroundColor(android.graphics.Color.BLACK)
}

/**
 * Build HTML for YouTube iframe embed with AR-optimized styling
 */
private fun buildYouTubeEmbedHtml(
    videoId: String,
    autoplay: Boolean,
    showControls: Boolean
): String {
    val autoplayParam = if (autoplay) 1 else 0
    val controlsParam = if (showControls) 1 else 0
    
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                html, body { 
                    width: 100%; 
                    height: 100%; 
                    background-color: #000000;
                    overflow: hidden;
                }
                .container {
                    width: 100%;
                    height: 100%;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    background-color: #000000;
                }
                iframe {
                    width: 100%;
                    height: 100%;
                    border: none;
                }
            </style>
        </head>
        <body>
            <div class="container">
                <iframe 
                    src="https://www.youtube.com/embed/$videoId?autoplay=$autoplayParam&controls=$controlsParam&modestbranding=1&rel=0&showinfo=0&playsinline=1&enablejsapi=1"
                    allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
                    allowfullscreen>
                </iframe>
            </div>
        </body>
        </html>
    """.trimIndent()
}
