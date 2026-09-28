package com.autocarplay.core

import android.os.Handler
import android.os.Looper

/** What the car screen is currently showing. */
enum class CarMode { HOME, VIDEO, WEB, MIRROR }

/** Implemented by the car-side screen controller while Android Auto shows this app. */
interface CarScreen {
    val mode: CarMode
    val title: String?
    fun open(request: PlayRequest)
    fun startMirror()
}

/**
 * Connects the phone UI with the car screen. Both run in the same process, so the phone can
 * simply hand requests to the car screen. Requests made before the car screen is open are
 * kept and replayed as soon as it opens. Main thread only.
 */
object CarHub {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var pending: ((CarScreen) -> Unit)? = null

    var screen: CarScreen? = null
        private set

    val hasPendingRequest: Boolean get() = pending != null

    fun attach(carScreen: CarScreen) {
        screen = carScreen
        pending?.let { action ->
            pending = null
            action(carScreen)
        }
        notifyChanged()
    }

    fun detach(carScreen: CarScreen) {
        if (screen === carScreen) screen = null
        notifyChanged()
    }

    /** Returns true if the car shows it now, false if it will be shown once the car screen opens. */
    fun open(request: PlayRequest): Boolean = runOnCar { it.open(request) }

    fun startMirror(): Boolean = runOnCar { it.startMirror() }

    private fun runOnCar(action: (CarScreen) -> Unit): Boolean {
        val current = screen
        if (current != null) {
            action(current)
            return true
        }
        pending = action
        notifyChanged()
        return false
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun notifyChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.toList().forEach { it() }
        } else {
            main.post { notifyChanged() }
        }
    }
}
