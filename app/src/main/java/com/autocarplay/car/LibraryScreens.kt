package com.autocarplay.car

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import com.autocarplay.R
import com.autocarplay.core.LinkStore
import com.autocarplay.core.PhoneVideo
import com.autocarplay.core.PhoneVideos
import com.autocarplay.core.PlayRequest
import com.autocarplay.core.SourceKind
import com.autocarplay.core.Sources
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private val background = Executors.newSingleThreadExecutor()
private val mainThread = Handler(Looper.getMainLooper())

/** Videos stored on the phone, newest first, a page at a time. */
class PhoneVideosScreen(
    carContext: CarContext,
    private val display: CarDisplayController,
    private val offset: Int = 0,
) : Screen(carContext) {

    private var videos: List<PhoneVideo>? = null
    private var thumbnails: Map<Uri, Bitmap> = emptyMap()
    private var hasMore = false
    private var loading = false

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val ctx = carContext
        if (!PhoneVideos.hasAccess(ctx)) {
            return MessageTemplate.Builder(ctx.getString(R.string.videos_permission_needed))
                .setTitle(ctx.getString(R.string.menu_phone_videos))
                .setHeaderAction(Action.BACK)
                .addAction(
                    Action.Builder()
                        .setTitle(ctx.getString(R.string.allow_on_phone))
                        .setOnClickListener {
                            ctx.requestPermissions(listOf(PhoneVideos.permission)) { _, _ -> invalidate() }
                        }
                        .build(),
                )
                .build()
        }

        val loaded = videos
        val builder = ListTemplate.Builder()
            .setTitle(ctx.getString(R.string.menu_phone_videos))
            .setHeaderAction(Action.BACK)
        if (loaded == null) {
            load()
            return builder.setLoading(true).build()
        }

        val items = ItemList.Builder()
        items.setNoItemsMessage(ctx.getString(R.string.no_videos))
        loaded.forEach { video ->
            val row = Row.Builder()
                .setTitle(video.title)
                .setOnClickListener {
                    display.open(PlayRequest(SourceKind.VIDEO, video.uri.toString(), video.title))
                    screenManager.popToRoot()
                }
            PhoneVideos.describe(video).takeIf { it.isNotBlank() }?.let { row.addText(it) }
            val thumbnail = thumbnails[video.uri]
            row.setImage(if (thumbnail != null) bitmapIcon(thumbnail) else ctx.carIcon(R.drawable.ic_video))
            items.addItem(row.build())
        }
        if (hasMore) {
            items.addItem(
                Row.Builder()
                    .setTitle(ctx.getString(R.string.more_videos))
                    .setImage(ctx.carIcon(R.drawable.ic_more))
                    .setOnClickListener {
                        screenManager.push(PhoneVideosScreen(ctx, display, offset + loaded.size))
                    }
                    .build(),
            )
        }
        return builder.setSingleList(items.build()).build()
    }

    private fun load() {
        if (loading) return
        loading = true
        // Leave one row for "More videos"; keep pages small so thumbnails stay light.
        val pageSize = (carContext.listLimit() - 1).coerceIn(5, 11)
        val context = carContext.applicationContext
        background.execute {
            val (items, more) = try {
                PhoneVideos.query(context, offset, pageSize)
            } catch (e: Exception) {
                emptyList<PhoneVideo>() to false
            }
            val thumbs = HashMap<Uri, Bitmap>()
            items.forEach { video ->
                PhoneVideos.thumbnail(context, video.uri)?.let { thumbs[video.uri] = shrink(it) }
            }
            mainThread.post {
                videos = items
                hasMore = more
                thumbnails = thumbs
                loading = false
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) invalidate()
            }
        }
    }

    /** Small thumbnails keep the list well under Android's binder size limit. */
    private fun shrink(bitmap: Bitmap): Bitmap {
        val maxSide = 128f
        val scale = maxSide / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }
}

/** Links saved on the phone or shared to the app. */
class LinksScreen(
    carContext: CarContext,
    private val display: CarDisplayController,
) : Screen(carContext) {

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val ctx = carContext
        val items = ItemList.Builder()
        items.setNoItemsMessage(ctx.getString(R.string.no_links))
        LinkStore(ctx).links().take(ctx.listLimit()).forEach { link ->
            val kind = Sources.classify(link.url)
            items.addItem(
                Row.Builder()
                    .setTitle(link.title)
                    .addText(link.url)
                    .setImage(ctx.carIcon(if (kind == SourceKind.VIDEO) R.drawable.ic_video else R.drawable.ic_web))
                    .setOnClickListener {
                        Sources.requestFor(link.url, link.title)?.let { display.open(it) }
                        screenManager.popToRoot()
                    }
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle(ctx.getString(R.string.menu_links))
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}

/** Car keyboard: search YouTube, or type a website / search the web. */
class KeyboardScreen(
    carContext: CarContext,
    private val display: CarDisplayController,
    private val searchYouTube: Boolean,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val ctx = carContext
        val recent = ItemList.Builder()
        LinkStore(ctx).recentQueries().forEach { query ->
            recent.addItem(
                Row.Builder()
                    .setTitle(query)
                    .setOnClickListener { submit(query) }
                    .build(),
            )
        }
        val hint = if (searchYouTube) R.string.search_youtube_hint else R.string.website_hint
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) = submit(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(ctx.getString(hint))
            .setShowKeyboardByDefault(true)
            .setItemList(recent.build())
            .build()
    }

    private fun submit(text: String) {
        val request = Sources.requestForQuery(text, searchYouTube) ?: return
        LinkStore(carContext).addRecentQuery(text.trim())
        display.open(request)
        screenManager.popToRoot()
    }
}
