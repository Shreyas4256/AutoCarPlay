package com.autocarplay.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.autocarplay.R
import com.autocarplay.core.PlayRequest
import com.autocarplay.core.SourceKind
import com.autocarplay.core.Sources

/** Main menu: every way to put something on the car screen. Six rows fit on every car. */
class MenuScreen(
    carContext: CarContext,
    private val display: CarDisplayController,
) : Screen(carContext) {

    @Suppress("DEPRECATION") // setTitle/setHeaderAction/setActionStrip work on every Android Auto version.
    override fun onGetTemplate(): Template {
        val ctx = carContext
        val items = ItemList.Builder()
            .addItem(row(R.drawable.ic_video, R.string.menu_phone_videos, R.string.menu_phone_videos_text) {
                screenManager.push(PhoneVideosScreen(ctx, display))
            })
            .addItem(row(R.drawable.ic_link, R.string.menu_links, R.string.menu_links_text) {
                screenManager.push(LinksScreen(ctx, display))
            })
            .addItem(row(R.drawable.ic_youtube, R.string.menu_youtube, R.string.menu_youtube_text) {
                display.open(PlayRequest(SourceKind.WEB, Sources.YOUTUBE_HOME, "YouTube"))
                screenManager.popToRoot()
            })
            .addItem(row(R.drawable.ic_search, R.string.menu_search_youtube, R.string.menu_search_youtube_text) {
                screenManager.push(KeyboardScreen(ctx, display, searchYouTube = true))
            })
            .addItem(row(R.drawable.ic_web, R.string.menu_website, R.string.menu_website_text) {
                screenManager.push(KeyboardScreen(ctx, display, searchYouTube = false))
            })
            .addItem(row(R.drawable.ic_mirror, R.string.menu_mirror, R.string.menu_mirror_text) {
                display.startMirror()
                screenManager.popToRoot()
            })
            .build()

        return ListTemplate.Builder()
            .setTitle(ctx.getString(R.string.app_name))
            .setHeaderAction(Action.BACK)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(ctx.getString(R.string.help))
                            .setOnClickListener { screenManager.push(HelpScreen(ctx)) }
                            .build(),
                    )
                    .build(),
            )
            .setSingleList(items)
            .build()
    }

    private fun row(icon: Int, title: Int, text: Int, onClick: () -> Unit): Row =
        Row.Builder()
            .setTitle(carContext.getString(title))
            .addText(carContext.getString(text))
            .setImage(carContext.carIcon(icon))
            .setOnClickListener { onClick() }
            .build()
}

/** Short how-to shown on the car screen. */
class HelpScreen(carContext: CarContext) : Screen(carContext) {
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(carContext.getString(R.string.car_help))
            .setTitle(carContext.getString(R.string.help))
            .setHeaderAction(Action.BACK)
            .build()
}
