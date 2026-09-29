package com.autocarplay.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.autocarplay.core.CarHub

/**
 * Optional accessibility service that replays taps and swipes made on the car screen onto the
 * phone screen while mirroring, so apps like YouTube can be controlled from the car.
 * It does not read screen content.
 */
class TouchControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        CarHub.notifyChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        CarHub.notifyChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
        return perform(path, 50)
    }

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(fromX.coerceAtLeast(0f), fromY.coerceAtLeast(0f))
            lineTo(toX.coerceAtLeast(0f), toY.coerceAtLeast(0f))
        }
        return perform(path, durationMs.coerceIn(10, 2000))
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    private fun perform(path: Path, durationMs: Long): Boolean = try {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    } catch (e: IllegalArgumentException) {
        false
    }

    companion object {
        /** The running service, or null if the user has not enabled it in Accessibility settings. */
        var instance: TouchControlService? = null
            private set
    }
}
