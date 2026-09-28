package com.autocarplay.car

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
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
import com.autocarplay.core.PlayRequest
import com.autocarplay.core.SourceKind
import com.autocarplay.core.Sources
import com.autocarplay.mirror.MirrorManager
import com.autocarplay.mirror.MirrorPermissionActivity
import com.autocarplay.mirror.MirrorPrompt
import com.autocarplay.mirror.TouchControlService

/**
 * Receives the Android Auto drawing surface and shows the app's content on it.
 *
 * The surface is wrapped in a private [VirtualDisplay]; a [CarPresentation] placed on that
 * display renders the video player, browser or mirrored phone screen.
 */
class CarDisplayController(private val carContext: CarContext) :
    SurfaceCallback, CarScreen, CarPresentation.Callbacks {

    /** Lets the home-screen tiles open template screens (lists, keyboard). */
    interface Navigator {
        fun openPhoneVideos()
        fun openLinks()
    }

    var navigator: Navigator? = null

    override var mode = CarMode.HOME
        private set
    override var title: String? = null
        private set

    val isPlaying: Boolean get() = player?.isPlaying == true
    var isZoomed = false
        private set

    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 0
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarPresentation? = null
    private var player: ExoPlayer? = null
    private var webUrl: String? = null
    private var touchHintShown = false

    private val hubListener: () -> Unit = { presentation?.updateMirror() }

    init {
        CarHub.addListener(hubListener)
        MirrorManager.onGeometryChanged = { presentation?.updateMirror() }
    }

    // region SurfaceCallback

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val newSurface = surfaceContainer.surface ?: return
        val sizeChanged = surfaceContainer.width != surfaceWidth ||
            surfaceContainer.height != surfaceHeight ||
            surfaceContainer.dpi != surfaceDpi
        val oldSurface = surface
        surface = newSurface
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        surfaceDpi = surfaceContainer.dpi
        val display = virtualDisplay
        if (display == null || sizeChanged) {
            rebuildDisplay()
        } else {
            display.surface = newSurface
        }
        if (oldSurface != null && oldSurface !== newSurface) oldSurface.release()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        virtualDisplay?.surface = null
        surface?.release()
        surface = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) = Unit

    override fun onStableAreaChanged(stableArea: Rect) = Unit

    override fun onClick(x: Float, y: Float) {
        presentation?.injectTap(x, y)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        presentation?.injectScroll(distanceX, distanceY)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        when (mode) {
            // Pinch on a video switches between "fit" and "fill the screen".
            CarMode.VIDEO -> {
                if (scaleFactor > 1.05f && !isZoomed) setZoom(true)
                if (scaleFactor < 0.95f && isZoomed) setZoom(false)
            }
            CarMode.WEB -> presentation?.webZoom(scaleFactor)
            else -> Unit
        }
    }

    // endregion

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
        title = carContext.getString(R.string.mirror_title)
        presentation?.showMirror()
        if (!MirrorManager.isActive && !MirrorManager.consentPending) {
            // The capture prompt can only be answered on the phone.
            MirrorPermissionActivity.launch(carContext)
            MirrorPrompt.show(carContext)
            toast(R.string.mirror_check_phone)
        }
        CarHub.notifyChanged()
    }

    // endregion

    // region Controls used by the car templates

    fun goHome() {
        leaveMirror()
        pausePlayer()
        mode = CarMode.HOME
        title = null
        presentation?.showHome()
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

    fun webBack() {
        if (presentation?.webBack() != true) toast(R.string.web_no_back)
    }

    fun webScroll(down: Boolean) {
        presentation?.webScroll(down)
    }

    fun webReload() {
        presentation?.webReload()
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

    /** Called when the car session ends. */
    fun release() {
        CarHub.removeListener(hubListener)
        CarHub.detach(this)
        MirrorManager.onGeometryChanged = null
        MirrorManager.stop()
        tearDownDisplay()
        player?.release()
        player = null
        surface?.release()
        surface = null
    }

    // endregion

    // region CarPresentation.Callbacks

    override fun onHomeTile(tile: CarPresentation.HomeTile) {
        when (tile) {
            CarPresentation.HomeTile.VIDEOS -> navigator?.openPhoneVideos()
            CarPresentation.HomeTile.YOUTUBE -> openWeb(Sources.YOUTUBE_HOME, "YouTube")
            CarPresentation.HomeTile.LINKS -> navigator?.openLinks()
            CarPresentation.HomeTile.MIRROR -> startMirror()
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
        presentation?.showVideo(p, request.title)
        CarHub.notifyChanged()
    }

    private fun openWeb(url: String, pageTitle: String) {
        leaveMirror()
        pausePlayer()
        mode = CarMode.WEB
        title = pageTitle
        webUrl = url
        presentation?.showWeb(url)
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
        presentation?.setVideoZoom(zoom)
        CarHub.notifyChanged()
    }

    private fun requireTouchControl(): Boolean {
        if (TouchControlService.instance != null) return true
        if (!touchHintShown) {
            touchHintShown = true
            presentation?.showMessage(carContext.getString(R.string.touch_control_needed), 6000)
        } else {
            toast(R.string.touch_control_needed_short)
        }
        return false
    }

    private fun createPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val dataSource = DefaultDataSource.Factory(carContext, http)
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val newPlayer = ExoPlayer.Builder(carContext)
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
                presentation?.showMessage(
                    carContext.getString(R.string.video_error, error.errorCodeName), 8000,
                )
                CarHub.notifyChanged()
            }
        })
        return newPlayer
    }

    /** (Re)creates the virtual display for the current car surface and restores the content. */
    private fun rebuildDisplay() {
        tearDownDisplay()
        val target = surface ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val manager = carContext.getSystemService(DisplayManager::class.java) ?: return
        val display = try {
            manager.createVirtualDisplay(
                "AutoCarPlay", surfaceWidth, surfaceHeight, surfaceDpi.coerceAtLeast(120), target,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            null
        } ?: return
        virtualDisplay = display
        val newPresentation = CarPresentation(carContext, display.display, this)
        try {
            newPresentation.show()
        } catch (e: Exception) {
            Log.e(TAG, "Could not show content on the car screen", e)
            display.release()
            virtualDisplay = null
            return
        }
        presentation = newPresentation
        newPresentation.setVideoZoom(isZoomed)
        when (mode) {
            CarMode.HOME -> newPresentation.showHome()
            CarMode.VIDEO -> {
                val p = player
                if (p != null) newPresentation.showVideo(p, title.orEmpty()) else newPresentation.showHome()
            }
            CarMode.WEB -> newPresentation.showWeb(webUrl ?: Sources.YOUTUBE_HOME)
            CarMode.MIRROR -> newPresentation.showMirror()
        }
    }

    private fun tearDownDisplay() {
        presentation?.let {
            it.release()
            try {
                it.dismiss()
            } catch (e: Exception) {
                Log.w(TAG, "dismiss failed", e)
            }
        }
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun toast(message: Int) {
        CarToast.makeText(carContext, message, CarToast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "CarDisplay"
    }
}
