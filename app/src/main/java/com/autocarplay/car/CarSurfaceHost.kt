package com.autocarplay.car

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

/**
 * Receives the Android Auto map surface (Car App Library, navigation category) and shows the
 * car content on it: the surface is wrapped in a private [VirtualDisplay] and a
 * [CarPresentation] on that display draws normal Android views.
 */
class CarSurfaceHost(
    private val carContext: CarContext,
    private val controller: CarController,
) : SurfaceCallback {

    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 0
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarPresentation? = null

    private val content: CarContent? get() = presentation?.content

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val newSurface = surfaceContainer.surface ?: return
        val sizeChanged = surfaceContainer.width != surfaceWidth ||
            surfaceContainer.height != surfaceHeight ||
            surfaceContainer.dpi != surfaceDpi
        val oldSurface = surface
        surface = newSurface
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        surfaceDpi = surfaceContainer.dpi
        val display = virtualDisplay
        if (display == null || sizeChanged) {
            rebuildDisplay()
        } else {
            display.surface = newSurface
        }
        if (oldSurface != null && oldSurface !== newSurface) oldSurface.release()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        virtualDisplay?.surface = null
        surface?.release()
        surface = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) = Unit

    override fun onStableAreaChanged(stableArea: Rect) = Unit

    override fun onClick(x: Float, y: Float) {
        content?.injectTap(x, y)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        content?.injectScroll(distanceX, distanceY)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        controller.pinch(scaleFactor)
    }

    /** Called when the car session ends. */
    fun release() {
        tearDownDisplay()
        surface?.release()
        surface = null
    }

    /** (Re)creates the virtual display for the current car surface and restores the content. */
    private fun rebuildDisplay() {
        tearDownDisplay()
        val target = surface ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val manager = carContext.getSystemService(DisplayManager::class.java) ?: return
        val display = try {
            manager.createVirtualDisplay(
                "AutoCarPlay", surfaceWidth, surfaceHeight, surfaceDpi.coerceAtLeast(120), target,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            null
        } ?: return
        virtualDisplay = display
        val newPresentation = CarPresentation(carContext, display.display, controller)
        try {
            newPresentation.show()
        } catch (e: Exception) {
            Log.e(TAG, "Could not show content on the car screen", e)
            display.release()
            virtualDisplay = null
            return
        }
        presentation = newPresentation
        newPresentation.content?.let { controller.attach(it) }
    }

    private fun tearDownDisplay() {
        presentation?.let {
            it.content?.let { old ->
                controller.detach(old)
                old.release()
            }
            try {
                it.dismiss()
            } catch (e: Exception) {
                Log.w(TAG, "dismiss failed", e)
            }
        }
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private companion object {
        const val TAG = "CarSurfaceHost"
    }
}
