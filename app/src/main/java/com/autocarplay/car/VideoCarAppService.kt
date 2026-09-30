package com.autocarplay.car

import android.content.Intent
import android.content.pm.ApplicationInfo
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

    /**
     * Only Google's Android Auto / Automotive hosts may connect in release builds. The service
     * is exported, so accepting any host would let any app on the phone bind to it, drive the
     * car screen, capture what it shows (including the mirrored phone screen) and, with touch
     * control on, tap on the phone. Debug builds also accept the Desktop Head Unit and tools.
     */
    override fun createHostValidator(): HostValidator =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

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
