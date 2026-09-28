package com.autocarplay.car

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.autocarplay.core.CarHub

/** Entry point Android Auto binds to when the user opens AutoCarPlay on the car screen. */
class VideoCarAppService : CarAppService() {

    // The app is sideloaded, so accept any Android Auto host (the Play Store build of Android
    // Auto, or a head-unit emulator such as the Desktop Head Unit).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = VideoSession()
}

class VideoSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val controller = CarDisplayController(carContext)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(controller)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                controller.release()
            }
        })
        val screen = MainScreen(carContext, controller)
        CarHub.attach(controller)
        return screen
    }
}
