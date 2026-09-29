package com.autocarplay.car

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.autocarplay.R
import com.autocarplay.core.CarHub
import com.autocarplay.core.CarMode
import com.autocarplay.core.CarScreen
import com.autocarplay.core.LinkStore
import com.autocarplay.core.PhoneVideo
import com.autocarplay.core.PhoneVideos
import com.autocarplay.core.PlayRequest
import com.autocarplay.core.SourceKind
import com.autocarplay.core.Sources
import com.autocarplay.mirror.MirrorManager
import com.autocarplay.mirror.MirrorPermissionActivity
import com.autocarplay.mirror.MirrorPrompt
import com.autocarplay.mirror.TouchControlService
import java.util.concurrent.Executors

/**
 * What the car screen shows and plays: the video player, the web page, the phone mirror and
 * the home screen. The views themselves live in a [CarContent], which is attached while the
 * car screen is visible (it may come and go while playback continues).
 */
class CarController(private val context: Context) : CarScreen, CarContent.Callbacks {

    /** Lets the home-screen tiles open Android Auto template screens (lists). */
    interface Navigator {
        fun openPhoneVideos()
        fun openLinks()
    }

    /** Template screens when running on the Android Auto surface; null in the car activity. */
    var navigator: Navigator? = null

    /** Shows short notices; defaults to a message on the car screen. */
    var toaster: ((String) -> Unit)? = null

    override var mode = CarMode.HOME
        private set
    override var title: String? = null
        private set

    val isPlaying: Boolean get() = player?.isPlaying == true
    var isZoomed = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var content: CarContent? = null
    private var player: ExoPlayer? = null
    private var webUrl: String? = null
    private var touchHintShown = false

    private val hubListener: () -> Unit = { content?.updateMirror() }

    init {
        CarHub.addListener(hubListener)
        MirrorManager.onGeometryChanged = { content?.updateMirror() }
    }

    /** Shows the current state on newly created car views. */
    fun attach(newContent: CarContent) {
        content = newContent
        newContent.setVideoZoom(isZoomed)
        when (mode) {
            CarMode.HOME -> newContent.showHome()
            CarMode.VIDEO -> {
                val p = player
                if (p != null) newContent.showVideo(p, title.orEmpty()) else newContent.showHome()
            }
            CarMode.WEB -> newContent.showWeb(webUrl ?: Sources.YOUTUBE_HOME)
            CarMode.MIRROR -> newContent.showMirror()
        }
    }

    fun detach(oldContent: CarContent) {
        if (content === oldContent) content = null
    }

    // region CarScreen (requests from the phone or from car menus)

    override fun open(request: PlayRequest) {
        when (request.kind) {
            SourceKind.VIDEO -> playVideo(request)
            SourceKind.WEB -> openWeb(request.uri, request.title)
        }
    }

    override fun startMirror() {
        pausePlayer()
        mode = CarMode.MIRROR
        title = context.getString(R.string.mirror_title)
        content?.showMirror()
        if (!MirrorManager.isActive && !MirrorManager.consentPending) {
            // The capture prompt can only be answered on the phone.
            MirrorPermissionActivity.launch(context)
            MirrorPrompt.show(context)
            toast(R.string.mirror_check_phone)
        }
        CarHub.notifyChanged()
    }

    // endregion

    // region Controls

    fun goHome() {
        leaveMirror()
        pausePlayer()
        mode = CarMode.HOME
        title = null
        content?.showHome()
        CarHub.notifyChanged()
    }

    fun stopVideo() {
        player?.stop()
        player?.clearMediaItems()
        goHome()
    }

    fun stopMirror() {
        MirrorManager.stop()
        goHome()
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekBack() {
        player?.seekBack()
    }

    fun seekForward() {
        player?.seekForward()
    }

    fun toggleZoom() = setZoom(!isZoomed)

    /** Pinch: fit/fill for videos, zoom for web pages. */
    fun pinch(scaleFactor: Float) {
        when (mode) {
            CarMode.VIDEO -> {
                if (scaleFactor > 1.05f && !isZoomed) setZoom(true)
                if (scaleFactor < 0.95f && isZoomed) setZoom(false)
            }
            CarMode.WEB -> content?.webZoom(scaleFactor)
            else -> Unit
        }
    }

    fun webBack() {
        if (content?.webBack() != true) toast(R.string.web_no_back)
    }

    fun webScroll(down: Boolean) {
        content?.webScroll(down)
    }

    fun webReload() {
        content?.webReload()
    }

    fun mirrorBack() {
        if (requireTouchControl()) TouchControlService.instance?.back()
    }

    fun mirrorHome() {
        if (requireTouchControl()) TouchControlService.instance?.home()
    }

    fun mirrorScroll(down: Boolean) {
        if (!requireTouchControl()) return
        val (fromY, toY) = if (down) 0.7f to 0.3f else 0.3f to 0.7f
        onMirrorSwipe(0.5f, fromY, 0.5f, toY, 250)
    }

    /** System back on the car activity. Returns false when the app should close. */
    fun handleBack(): Boolean {
        val current = content
        if (current != null && current.isListShown) {
            current.hideList()
            return true
        }
        return when (mode) {
            CarMode.HOME -> false
            CarMode.WEB -> {
                if (current?.webBack() != true) goHome()
                true
            }
            else -> {
                goHome()
                true
            }
        }
    }

    /** Called when the car screen goes away for good. */
    fun release() {
        CarHub.removeListener(hubListener)
        CarHub.detach(this)
        MirrorManager.onGeometryChanged = null
        MirrorManager.stop()
        content = null
        player?.release()
        player = null
    }

    // endregion

    // region CarContent.Callbacks

    override fun onHomeTile(tile: CarContent.HomeTile) {
        when (tile) {
            CarContent.HomeTile.VIDEOS -> navigator?.openPhoneVideos() ?: showPhoneVideoList()
            CarContent.HomeTile.YOUTUBE -> openWeb(Sources.YOUTUBE_HOME, "YouTube")
            CarContent.HomeTile.LINKS -> navigator?.openLinks() ?: showLinkList()
            CarContent.HomeTile.MIRROR -> startMirror()
        }
    }

    override fun onControl(control: CarContent.Control) {
        when (control) {
            CarContent.Control.BACK -> if (mode == CarMode.MIRROR) mirrorBack() else webBack()
            CarContent.Control.CLOSE -> if (mode == CarMode.VIDEO) stopVideo() else goHome()
        }
    }

    override fun onWebPageChanged(url: String, title: String?) {
        webUrl = url
        if (mode == CarMode.WEB && !title.isNullOrBlank()) this.title = title
    }

    override fun onMirrorTap(x: Float, y: Float) {
        if (!requireTouchControl()) return
        TouchControlService.instance?.tap(x * MirrorManager.phoneWidth, y * MirrorManager.phoneHeight)
    }

    override fun onMirrorSwipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) {
        if (!requireTouchControl()) return
        val w = MirrorManager.phoneWidth
        val h = MirrorManager.phoneHeight
        TouchControlService.instance?.swipe(fromX * w, fromY * h, toX * w, toY * h, durationMs)
    }

    // endregion

    /** Phone videos as an on-screen list (used by the car activity, which has no templates). */
    private fun showPhoneVideoList() {
        val target = content ?: return
        val listTitle = context.getString(R.string.menu_phone_videos)
        if (!PhoneVideos.hasAccess(context)) {
            target.showList(listTitle, emptyList(), context.getString(R.string.videos_permission_on_phone))
            return
        }
        target.showList(listTitle, null, "")
        val app = context.applicationContext
        background.execute {
            val videos = try {
                PhoneVideos.query(app, 0, MAX_LIST_VIDEOS).first
            } catch (e: Exception) {
                emptyList<PhoneVideo>()
            }
            val thumbnails = videos.map { PhoneVideos.thumbnail(app, it.uri) }
            main.post {
                if (content !== target || !target.isListShown) return@post
                val rows = videos.mapIndexed { i, video -> videoRow(video, thumbnails[i]) }
                target.showList(listTitle, rows, context.getString(R.string.no_videos))
            }
        }
    }

    private fun videoRow(video: PhoneVideo, thumbnail: Bitmap?) = CarContent.ListRow(
        title = video.title,
        subtitle = PhoneVideos.describe(video),
        icon = R.drawable.ic_video,
        thumbnail = thumbnail,
    ) { open(PlayRequest(SourceKind.VIDEO, video.uri.toString(), video.title)) }

    private fun showLinkList() {
        val target = content ?: return
        val rows = LinkStore(context).links().map { link ->
            val kind = Sources.classify(link.url)
            CarContent.ListRow(
                title = link.title,
                subtitle = link.url,
                icon = if (kind == SourceKind.VIDEO) R.drawable.ic_video else R.drawable.ic_web,
                thumbnail = null,
            ) { Sources.requestFor(link.url, link.title)?.let { open(it) } }
        }
        target.showList(context.getString(R.string.menu_links), rows, context.getString(R.string.no_links))
    }

    private fun playVideo(request: PlayRequest) {
        leaveMirror()
        val p = player ?: createPlayer().also { player = it }
        val item = MediaItem.Builder()
            .setUri(request.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).build())
            .build()
        p.setMediaItem(item)
        p.prepare()
        p.play()
        mode = CarMode.VIDEO
        title = request.title
        content?.showVideo(p, request.title)
        CarHub.notifyChanged()
    }

    private fun openWeb(url: String, pageTitle: String) {
        leaveMirror()
        pausePlayer()
        mode = CarMode.WEB
        title = pageTitle
        webUrl = url
        content?.showWeb(url)
        CarHub.notifyChanged()
    }

    private fun leaveMirror() {
        if (mode == CarMode.MIRROR) MirrorManager.stop()
    }

    private fun pausePlayer() {
        player?.pause()
    }

    private fun setZoom(zoom: Boolean) {
        isZoomed = zoom
        content?.setVideoZoom(zoom)
        CarHub.notifyChanged()
    }

    private fun requireTouchControl(): Boolean {
        if (TouchControlService.instance != null) return true
        if (!touchHintShown) {
            touchHintShown = true
            content?.showMessage(context.getString(R.string.touch_control_needed), 6000)
        } else {
            toast(R.string.touch_control_needed_short)
        }
        return false
    }

    private fun toast(message: Int) {
        val text = context.getString(message)
        val custom = toaster
        val current = content
        when {
            custom != null -> custom(text)
            current != null -> current.showMessage(text)
            else -> Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    private fun createPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val dataSource = DefaultDataSource.Factory(context, http)
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val newPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(audio, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        newPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                CarHub.notifyChanged()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Playback error", error)
                content?.showMessage(context.getString(R.string.video_error, error.errorCodeName), 8000)
                CarHub.notifyChanged()
            }
        })
        return newPlayer
    }

    private companion object {
        const val TAG = "CarController"
        const val MAX_LIST_VIDEOS = 100
        val background = Executors.newSingleThreadExecutor()
    }
}
