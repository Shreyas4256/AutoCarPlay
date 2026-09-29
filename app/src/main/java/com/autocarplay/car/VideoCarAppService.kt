package com.autocarplay.car

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.autocarplay.core.CarHub

/**
 * Car App Library entry point (navigation category). Android Auto only lists it when the app
 * was installed from a trusted source such as Google Play; sideloaded copies use
 * [CarScreenActivity] instead.
 */
class VideoCarAppService : CarAppService() {

    // Accept any Android Auto host (the Play Store build of Android Auto, or a head-unit
    // emulator such as the Desktop Head Unit).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = VideoSession()
}

class VideoSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val controller = CarController(carContext)
        controller.toaster = { CarToast.makeText(carContext, it, CarToast.LENGTH_LONG).show() }
        val host = CarSurfaceHost(carContext, controller)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(host)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                host.release()
                controller.release()
            }
        })
        val screen = MainScreen(carContext, controller)
        CarHub.attach(controller)
        return screen
    }
}
