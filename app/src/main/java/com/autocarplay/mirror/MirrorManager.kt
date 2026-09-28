package com.autocarplay.mirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import com.autocarplay.core.CarHub
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owns the phone screen capture. The captured phone screen is rendered into a Surface that
 * belongs to the car screen (a TextureView on the car display), so the phone's screen appears
 * in the car. Main thread only.
 *
 * Android 14+ allows only one virtual display per MediaProjection, so the virtual display is
 * kept for the whole session and only its target surface and size are swapped.
 */
object MirrorManager {
    private const val TAG = "MirrorManager"

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var output: Surface? = null
    private var maxWidth = 1280
    private var maxHeight = 720
    private var densityDpi = 320

    /** The phone screen size in its current rotation, in pixels. */
    var phoneWidth = 0
        private set
    var phoneHeight = 0
        private set

    private var consentRequestedAt = 0L

    /** True shortly after the "Start now" screen-capture prompt was opened on the phone. */
    var consentPending: Boolean
        get() = consentRequestedAt != 0L && SystemClock.elapsedRealtime() - consentRequestedAt < 30_000
        set(value) {
            consentRequestedAt = if (value) SystemClock.elapsedRealtime() else 0L
        }

    /** Called when the phone rotates so the car can adjust the picture's aspect ratio. */
    var onGeometryChanged: (() -> Unit)? = null

    val isActive: Boolean get() = projection != null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { release() }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateGeometry()
        }
    }

    fun start(context: Context, mediaProjection: MediaProjection) {
        if (projection != null) stop()
        val app = context.applicationContext
        appContext = app
        consentPending = false
        MirrorPrompt.cancel(app)
        projection = mediaProjection
        // Must be registered before createVirtualDisplay() on Android 14+.
        mediaProjection.registerCallback(projectionCallback, main)
        app.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, main)
        measurePhone()
        val (width, height) = renderSize()
        virtualDisplay = try {
            mediaProjection.createVirtualDisplay(
                "AutoCarPlay-mirror", width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, output, null, null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not start screen capture", e)
            null
        }
        if (virtualDisplay == null) {
            stop()
            return
        }
        onGeometryChanged?.invoke()
        CarHub.notifyChanged()
    }

    fun stop() {
        val current = projection ?: return
        release()
        try {
            current.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
    }

    /** Sets where the phone screen is drawn and the largest size worth rendering. */
    fun setOutput(surface: Surface, boundsWidth: Int, boundsHeight: Int) {
        output = surface
        if (boundsWidth > 0 && boundsHeight > 0) {
            maxWidth = boundsWidth
            maxHeight = boundsHeight
        }
        val display = virtualDisplay ?: return
        updateGeometry()
        display.surface = surface
    }

    fun clearOutput(surface: Surface) {
        if (output !== surface) return
        output = null
        virtualDisplay?.surface = null
    }

    private fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        val current = projection
        projection = null
        current?.unregisterCallback(projectionCallback)
        val context = appContext
        context?.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        if (current != null && context != null) MirrorService.stop(context)
        CarHub.notifyChanged()
    }

    private fun updateGeometry() {
        val oldWidth = phoneWidth
        val oldHeight = phoneHeight
        measurePhone()
        val display = virtualDisplay ?: return
        val (width, height) = renderSize()
        display.resize(width, height, densityDpi)
        if (oldWidth != phoneWidth || oldHeight != phoneHeight) onGeometryChanged?.invoke()
    }

    private fun measurePhone() {
        val context = appContext ?: return
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        phoneWidth = metrics.widthPixels
        phoneHeight = metrics.heightPixels
        densityDpi = metrics.densityDpi
    }

    /** Phone aspect ratio, scaled down to what the car screen can actually show. */
    private fun renderSize(): Pair<Int, Int> {
        if (phoneWidth <= 0 || phoneHeight <= 0) return maxWidth to maxHeight
        val scale = min(1f, min(maxWidth.toFloat() / phoneWidth, maxHeight.toFloat() / phoneHeight))
        val width = ((phoneWidth * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        val height = ((phoneHeight * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        return width to height
    }
}
