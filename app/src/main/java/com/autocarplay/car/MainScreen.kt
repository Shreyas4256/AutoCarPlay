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
    private val display: CarDisplayController,
) : Screen(carContext), CarDisplayController.Navigator {

    private val refresh: () -> Unit = { invalidate() }

    init {
        display.navigator = this
        CarHub.addListener(refresh)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                CarHub.removeListener(refresh)
                if (display.navigator === this@MainScreen) display.navigator = null
            }
        })
    }

    override fun openPhoneVideos() {
        screenManager.push(PhoneVideosScreen(carContext, display))
    }

    override fun openLinks() {
        screenManager.push(LinksScreen(carContext, display))
    }

    override fun onGetTemplate(): Template {
        val ctx = carContext
        // Only one action in the top strip carries a title; map-strip actions are icon-only.
        val strip = ActionStrip.Builder()
            .addAction(ctx.carAction(R.drawable.ic_menu, ctx.getString(R.string.menu)) {
                screenManager.push(MenuScreen(ctx, display))
            })
        // PAN must be present for Android Auto to forward drags and taps on the picture.
        val map = ActionStrip.Builder()
            .addAction(Action.Builder(Action.PAN).setIcon(ctx.carIcon(R.drawable.ic_pan)).build())

        when (display.mode) {
            CarMode.HOME -> Unit
            CarMode.VIDEO -> {
                val playIcon = if (display.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                strip.addAction(ctx.carAction(playIcon) { display.togglePlayPause() })
                strip.addAction(ctx.carAction(R.drawable.ic_stop) { display.stopVideo() })
                map.addAction(ctx.carAction(R.drawable.ic_rewind) { display.seekBack() })
                map.addAction(ctx.carAction(R.drawable.ic_forward) { display.seekForward() })
                map.addAction(ctx.carAction(R.drawable.ic_aspect) { display.toggleZoom() })
            }
            CarMode.WEB -> {
                strip.addAction(ctx.carAction(R.drawable.ic_back) { display.webBack() })
                strip.addAction(ctx.carAction(R.drawable.ic_keyboard) {
                    screenManager.push(KeyboardScreen(ctx, display, searchYouTube = true))
                })
                strip.addAction(ctx.carAction(R.drawable.ic_close) { display.goHome() })
                // Scroll buttons for car screens that do not report drags.
                map.addAction(ctx.carAction(R.drawable.ic_scroll_up) { display.webScroll(false) })
                map.addAction(ctx.carAction(R.drawable.ic_scroll_down) { display.webScroll(true) })
            }
            CarMode.MIRROR -> {
                strip.addAction(ctx.carAction(R.drawable.ic_back) { display.mirrorBack() })
                strip.addAction(ctx.carAction(R.drawable.ic_home) { display.mirrorHome() })
                strip.addAction(ctx.carAction(R.drawable.ic_stop) { display.stopMirror() })
                map.addAction(ctx.carAction(R.drawable.ic_scroll_up) { display.mirrorScroll(false) })
                map.addAction(ctx.carAction(R.drawable.ic_scroll_down) { display.mirrorScroll(true) })
            }
        }

        return NavigationTemplate.Builder()
            .setActionStrip(strip.build())
            .setMapActionStrip(map.build())
            .build()
    }
}
