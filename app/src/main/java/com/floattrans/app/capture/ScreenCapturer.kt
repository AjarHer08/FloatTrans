package com.floattrans.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.delay

/**
 * Owns a [MediaProjection] + [VirtualDisplay] mirroring the whole screen and
 * hands out fresh frames as bitmaps.
 */
class ScreenCapturer(private val context: Context) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var width = 0
    private var height = 0

    val isRunning: Boolean get() = imageReader != null

    /**
     * Starts mirroring. [onStopped] fires when the system (or the user, via the
     * status bar chip) revokes the capture session.
     */
    fun start(resultCode: Int, data: Intent, onStopped: () -> Unit): Boolean {
        releaseInternal()

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return false
        val mediaProjection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
            ?: return false

        // Android 14+ requires a registered callback before createVirtualDisplay().
        runCatching {
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    onStopped()
                }
            }, Handler(Looper.getMainLooper()))
        }

        val size = screenSize()
        width = size.first
        height = size.second
        if (width <= 0 || height <= 0) {
            runCatching { mediaProjection.stop() }
            return false
        }

        val densityDpi = context.resources.displayMetrics.densityDpi
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val display = runCatching {
            mediaProjection.createVirtualDisplay(
                "FloatTransCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
        }.getOrNull()

        if (display == null) {
            runCatching { reader.close() }
            runCatching { mediaProjection.stop() }
            return false
        }

        projection = mediaProjection
        virtualDisplay = display
        imageReader = reader
        return true
    }

    /** Throws away frames captured before the overlays were hidden. */
    fun drain() {
        val reader = imageReader ?: return
        var stale: Image? = reader.acquireLatestImage()
        while (stale != null) {
            runCatching { stale.close() }
            stale = reader.acquireLatestImage()
        }
    }

    /** Waits briefly for the next mirrored frame and converts it to a bitmap. */
    suspend fun captureNow(timeoutMs: Long = 900L): Bitmap? {
        val reader = imageReader ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        var image: Image? = null
        while (image == null && System.currentTimeMillis() < deadline) {
            image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image == null) delay(25)
        }
        val frame = image ?: return null
        return try {
            toBitmap(frame)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { frame.close() }
        }
    }

    /**
     * Cheap "did the page change?" fingerprint: samples a small luminance grid
     * straight out of the newest frame's pixel buffer, without copying a full
     * bitmap. Used by the automatic page/chapter watcher.
     */
    suspend fun sampleSignature(
        cols: Int = 24,
        rows: Int = 30,
        timeoutMs: Long = 400L
    ): IntArray? {
        val reader = imageReader ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        var image: Image? = null
        while (image == null && System.currentTimeMillis() < deadline) {
            image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image == null) delay(20)
        }
        val frame = image ?: return null
        return try {
            val plane = frame.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val limit = buffer.limit()
            val out = IntArray(cols * rows)

            // Skip the status bar and navigation bar so the clock, battery icon
            // or gesture hint never look like a new chapter.
            val top = (height * 0.06f).toInt()
            val bottom = (height * 0.94f).toInt()
            val usableHeight = (bottom - top).coerceAtLeast(1)

            for (row in 0 until rows) {
                val y = top + ((row * usableHeight) + usableHeight / 2) / rows
                val rowBase = y * rowStride
                for (col in 0 until cols) {
                    val x = ((col * width) + width / 2) / cols
                    val index = rowBase + x * pixelStride
                    if (index + 2 >= limit) continue
                    val r = buffer.get(index).toInt() and 0xFF
                    val g = buffer.get(index + 1).toInt() and 0xFF
                    val b = buffer.get(index + 2).toInt() and 0xFF
                    out[row * cols + col] = (r * 77 + g * 150 + b * 29) shr 8
                }
            }
            out
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { frame.close() }
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowPadding = rowStride - pixelStride * width
        val rawWidth = width + rowPadding / pixelStride

        val raw = Bitmap.createBitmap(rawWidth, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(plane.buffer)
        if (rawWidth == width) return raw

        val cropped = Bitmap.createBitmap(raw, 0, 0, width, height)
        raw.recycle()
        return cropped
    }

    private fun screenSize(): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return 0 to 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    fun stop() {
        releaseInternal()
    }

    private fun releaseInternal() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
    }
}
