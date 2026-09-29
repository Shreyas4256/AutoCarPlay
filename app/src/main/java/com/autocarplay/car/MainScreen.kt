package com.autocarplay.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.autocarplay.R
import com.autocarplay.core.CarHub
import com.autocarplay.core.CarMode

/**
 * The full-screen view. A NavigationTemplate is the only template that shows the app's own
 * drawing surface, so the video/browser/mirror picture appears behind these buttons.
 */
class MainScreen(
    carContext: CarContext,
    private val controller: CarController,
) : Screen(carContext), CarController.Navigator {

    private val refresh: () -> Unit = { invalidate() }

    init {
        controller.navigator = this
        CarHub.addListener(refresh)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                CarHub.removeListener(refresh)
                if (controller.navigator === this@MainScreen) controller.navigator = null
            }
        })
    }

    override fun openPhoneVideos() {
        screenManager.push(PhoneVideosScreen(carContext, controller))
    }

    override fun openLinks() {
        screenManager.push(LinksScreen(carContext, controller))
    }

    override fun onGetTemplate(): Template {
        val ctx = carContext
        // Only one action in the top strip carries a title; map-strip actions are icon-only.
        val strip = ActionStrip.Builder()
            .addAction(ctx.carAction(R.drawable.ic_menu, ctx.getString(R.string.menu)) {
                screenManager.push(MenuScreen(ctx, controller))
            })
        // PAN must be present for Android Auto to forward drags and taps on the picture.
        val map = ActionStrip.Builder()
            .addAction(Action.Builder(Action.PAN).setIcon(ctx.carIcon(R.drawable.ic_pan)).build())

        when (controller.mode) {
            CarMode.HOME -> Unit
            CarMode.VIDEO -> {
                val playIcon = if (controller.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                strip.addAction(ctx.carAction(playIcon) { controller.togglePlayPause() })
                strip.addAction(ctx.carAction(R.drawable.ic_stop) { controller.stopVideo() })
                map.addAction(ctx.carAction(R.drawable.ic_rewind) { controller.seekBack() })
                map.addAction(ctx.carAction(R.drawable.ic_forward) { controller.seekForward() })
                map.addAction(ctx.carAction(R.drawable.ic_aspect) { controller.toggleZoom() })
            }
            CarMode.WEB -> {
                strip.addAction(ctx.carAction(R.drawable.ic_back) { controller.webBack() })
                strip.addAction(ctx.carAction(R.drawable.ic_keyboard) {
                    screenManager.push(KeyboardScreen(ctx, controller, searchYouTube = true))
                })
                strip.addAction(ctx.carAction(R.drawable.ic_close) { controller.goHome() })
                // Scroll buttons for car screens that do not report drags.
                map.addAction(ctx.carAction(R.drawable.ic_scroll_up) { controller.webScroll(false) })
                map.addAction(ctx.carAction(R.drawable.ic_scroll_down) { controller.webScroll(true) })
            }
            CarMode.MIRROR -> {
                strip.addAction(ctx.carAction(R.drawable.ic_back) { controller.mirrorBack() })
                strip.addAction(ctx.carAction(R.drawable.ic_home) { controller.mirrorHome() })
                strip.addAction(ctx.carAction(R.drawable.ic_stop) { controller.stopMirror() })
                map.addAction(ctx.carAction(R.drawable.ic_scroll_up) { controller.mirrorScroll(false) })
                map.addAction(ctx.carAction(R.drawable.ic_scroll_down) { controller.mirrorScroll(true) })
            }
        }

        return NavigationTemplate.Builder()
            .setActionStrip(strip.build())
            .setMapActionStrip(map.build())
            .build()
    }
}
