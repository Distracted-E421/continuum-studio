package com.datapunk.aa.video

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate

/**
 * Owns the head unit Surface and draws onto it.
 *
 * The point of this experiment is narrow: prove we can get a raw Surface from
 * Android Auto on a real head unit and push our own pixels into it. Everything
 * else (ExoPlayer, drone feed, Compose via Presentation) is a swap of what
 * paints, not a change to how the surface is acquired.
 *
 * Threading note: onSurfaceAvailable is delivered on the main thread, so the
 * drawing here is deliberately cheap. Real video rendering must move to
 * ExoPlayer/MediaCodec writing straight into the Surface, not Canvas on main.
 */
class VideoScreen(carContext: CarContext) : Screen(carContext) {

    companion object {
        private const val TAG = "AAVideoScreen"
    }

    private var surface: Surface? = null
    private var drawThread: Thread? = null
    @Volatile private var running = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 64f
    }

    private val surfaceCallback = object : SurfaceCallback {

        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            Log.i(TAG, "SURFACE AVAILABLE ${surfaceContainer.width}x${surfaceContainer.height} dpi=${surfaceContainer.dpi}")

            // Every Surface handed to us must be released; take ownership here
            // and release on destroy. Releasing it in onSurfaceAvailable without
            // holding a reference is a known cause of a black screen.
            surface?.release()
            surface = surfaceContainer.surface

            startDrawing()
            invalidate()
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            Log.i(TAG, "SURFACE DESTROYED")
            stopDrawing()
            surfaceContainer.surface?.release()
            surface = null
        }

        override fun onVisibleAreaChanged(visibleArea: android.graphics.Rect) {
            Log.i(TAG, "visible area -> $visibleArea")
        }

        override fun onStableAreaChanged(stableArea: android.graphics.Rect) {
            Log.i(TAG, "stable area -> $stableArea")
        }

        override fun onClick(x: Float, y: Float) {
            Log.i(TAG, "surface tap at $x,$y")
        }
    }

    init {
        // Register immediately so the host can hand us a surface as soon as the
        // template is pushed.
        carContext.getCarService(AppManager::class.java)
            .setSurfaceCallback(surfaceCallback)

        // Screen has no onDestroy; track lifecycle explicitly so the draw thread
        // and surface are released when the car session ends.
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                releaseAll()
            }
        })
    }

    override fun onGetTemplate(): Template {
        // NavigationTemplate is one of the few templates that carries a
        // Surface. The action strip gives us a minimal control surface.
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Stop")
                            .setOnClickListener {
                                stopDrawing()
                                surface?.release()
                                surface = null
                                invalidate()
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    /**
     * Simple animated pattern so a moving image proves the surface is live.
     *
     * Uses a dedicated thread because Canvas drawing into a Surface blocks on
     * buffer submission; doing it on main would stall the car UI.
     */
    private fun startDrawing() {
        if (running) return
        running = true
        drawThread = Thread {
            var frame = 0
            while (running) {
                val s = surface ?: break
                val canvas: Canvas? = try {
                    s.lockCanvas(null)
                } catch (e: Exception) {
                    Log.w(TAG, "lockCanvas failed: ${e.message}")
                    null
                }
                if (canvas != null) {
                    try {
                        canvas.drawColor(Color.BLACK)
                        val cx = canvas.width / 2f
                        val cy = canvas.height / 2f
                        val r = (minOf(canvas.width, canvas.height) * 0.15f) +
                            (frame % 60 - 30).let { if (it < 0) -it else it }.toFloat()
                        paint.color = Color.argb(255, 40, 160, 240)
                        canvas.drawCircle(cx, cy, r, paint)
                        paint.color = Color.WHITE
                        canvas.drawText("Surface OK", cx - 120f, cy + 160f, paint)
                    } finally {
                        try {
                            s.unlockCanvasAndPost(canvas)
                        } catch (e: Exception) {
                            Log.w(TAG, "unlock failed: ${e.message}")
                        }
                    }
                }
                frame++
                try {
                    Thread.sleep(33) // ~30fps, matching AA's own stream rate
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.also { it.start() }
    }

    private fun stopDrawing() {
        running = false
        drawThread?.interrupt()
        drawThread = null
    }

    private fun releaseAll() {
        stopDrawing()
        surface?.release()
        surface = null
    }
}
