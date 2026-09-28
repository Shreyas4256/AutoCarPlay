package com.autocarplay.car

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.autocarplay.R
import com.autocarplay.core.CarMode
import com.autocarplay.mirror.MirrorManager

/**
 * Everything drawn on the car screen. It is a Presentation shown on a private virtual display
 * whose output is the Android Auto surface, so it can hold normal Android views: a video
 * player, a web browser and the mirrored phone screen.
 *
 * Android Auto only reports taps and drags as coordinates, so they are replayed here as
 * touch events on these views.
 */
class CarPresentation(
    context: Context,
    display: Display,
    private val callbacks: Callbacks,
) : Presentation(context, display, R.style.Theme_AutoCarPlay_Car) {

    interface Callbacks {
        fun onHomeTile(tile: HomeTile)
        fun onWebPageChanged(url: String, title: String?)

        /** A tap on the mirrored phone screen, in 0..1 coordinates of the phone screen. */
        fun onMirrorTap(x: Float, y: Float)

        /** A drag on the mirrored phone screen, in 0..1 coordinates of the phone screen. */
        fun onMirrorSwipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long)
    }

    enum class HomeTile { VIDEOS, YOUTUBE, LINKS, MIRROR }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var home: View
    private lateinit var playerView: PlayerView
    private lateinit var videoTitle: TextView
    private lateinit var webContainer: FrameLayout
    private lateinit var mirrorContainer: FrameLayout
    private lateinit var mirrorFrame: AspectRatioFrameLayout
    private lateinit var mirrorTexture: TextureView
    private lateinit var mirrorHint: TextView
    private lateinit var message: TextView

    private var webView: WebView? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var mirrorSurface: Surface? = null
    private var mode = CarMode.HOME
    private var created = false

    // State of a drag being replayed from Android Auto scroll events.
    private var dragDownTime = 0L
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private val finishDrag = Runnable { endDrag() }
    private val hideMessage = Runnable { message.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        setContentView(R.layout.car_presentation)
        home = findViewById(R.id.home)
        playerView = findViewById(R.id.player_view)
        videoTitle = findViewById(R.id.video_title)
        webContainer = findViewById(R.id.web_container)
        mirrorContainer = findViewById(R.id.mirror_container)
        mirrorFrame = findViewById(R.id.mirror_frame)
        mirrorTexture = findViewById(R.id.mirror_texture)
        mirrorHint = findViewById(R.id.mirror_hint)
        message = findViewById(R.id.message)

        findViewById<View>(R.id.tile_videos).setOnClickListener { callbacks.onHomeTile(HomeTile.VIDEOS) }
        findViewById<View>(R.id.tile_youtube).setOnClickListener { callbacks.onHomeTile(HomeTile.YOUTUBE) }
        findViewById<View>(R.id.tile_links).setOnClickListener { callbacks.onHomeTile(HomeTile.LINKS) }
        findViewById<View>(R.id.tile_mirror).setOnClickListener { callbacks.onHomeTile(HomeTile.MIRROR) }

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                videoTitle.visibility = if (videoTitle.text.isNullOrEmpty()) View.GONE else visibility
            },
        )

        mirrorTexture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                val surface = Surface(texture)
                mirrorSurface = surface
                MirrorManager.setOutput(surface, mirrorContainer.width, mirrorContainer.height)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                mirrorSurface?.let {
                    MirrorManager.clearOutput(it)
                    it.release()
                }
                mirrorSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
        created = true
        applyMode()
    }

    fun showHome() = switchTo(CarMode.HOME)

    fun showVideo(player: Player, title: String) {
        switchTo(CarMode.VIDEO)
        if (!created) return
        playerView.player = player
        videoTitle.text = title
        playerView.showController()
    }

    fun showWeb(url: String?) {
        switchTo(CarMode.WEB)
        if (!created) return
        val web = webView ?: createWebView().also {
            webView = it
            webContainer.addView(it, 0, matchParent())
        }
        if (url != null) web.loadUrl(url)
    }

    fun showMirror() {
        switchTo(CarMode.MIRROR)
        updateMirror()
    }

    /** Refreshes the mirror picture's aspect ratio and the "waiting for phone" hint. */
    fun updateMirror() {
        if (!created) return
        val width = MirrorManager.phoneWidth
        val height = MirrorManager.phoneHeight
        if (width > 0 && height > 0) mirrorFrame.setAspectRatio(width.toFloat() / height)
        mirrorHint.visibility = if (MirrorManager.isActive) View.GONE else View.VISIBLE
    }

    fun setVideoZoom(zoom: Boolean) {
        if (!created) return
        playerView.resizeMode = if (zoom) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    fun showMessage(text: CharSequence, durationMs: Long = 4000) {
        if (!created) return
        message.text = text
        message.visibility = View.VISIBLE
        handler.removeCallbacks(hideMessage)
        handler.postDelayed(hideMessage, durationMs)
    }

    /** Browser back. Returns false when there is no page to go back to. */
    fun webBack(): Boolean {
        if (fullscreenView != null) {
            exitFullscreen()
            return true
        }
        val web = webView ?: return false
        if (!web.canGoBack()) return false
        web.goBack()
        return true
    }

    fun webScroll(down: Boolean) {
        val web = webView ?: return
        if (down) web.pageDown(false) else web.pageUp(false)
    }

    fun webReload() {
        webView?.reload()
    }

    /** Pinch zoom on web pages. */
    fun webZoom(scaleFactor: Float) {
        if (mode != CarMode.WEB || fullscreenView != null) return
        webView?.zoomBy(scaleFactor.coerceIn(0.5f, 2f))
    }

    /** Replays a tap from the car touch screen. */
    fun injectTap(x: Float, y: Float) {
        if (!created) return
        if (mode == CarMode.MIRROR) {
            mirrorPoint(x, y)?.let { callbacks.onMirrorTap(it.x, it.y) }
            return
        }
        val target = window?.decorView ?: return
        val downTime = SystemClock.uptimeMillis()
        dispatch(target, MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        handler.postDelayed({
            dispatch(target, MotionEvent.ACTION_UP, x, y, downTime, SystemClock.uptimeMillis())
        }, TAP_DURATION_MS)
    }

    /**
     * Replays a drag from the car touch screen. Distances follow GestureDetector conventions.
     * Only web pages and the mirrored phone scroll; the video and home views ignore drags.
     */
    fun injectScroll(distanceX: Float, distanceY: Float) {
        if (!created || (mode != CarMode.WEB && mode != CarMode.MIRROR)) return
        val target = window?.decorView ?: return
        val width = target.width.toFloat()
        val height = target.height.toFloat()
        if (width <= 2f || height <= 2f) return
        val now = SystemClock.uptimeMillis()
        if (dragDownTime == 0L) {
            dragDownTime = now
            dragStartX = width / 2f
            dragStartY = height / 2f
            dragX = dragStartX
            dragY = dragStartY
            if (mode != CarMode.MIRROR) dispatch(target, MotionEvent.ACTION_DOWN, dragX, dragY, now, now)
        }
        dragX = (dragX - distanceX).coerceIn(1f, width - 1f)
        dragY = (dragY - distanceY).coerceIn(1f, height - 1f)
        if (mode != CarMode.MIRROR) dispatch(target, MotionEvent.ACTION_MOVE, dragX, dragY, dragDownTime, now)
        handler.removeCallbacks(finishDrag)
        handler.postDelayed(finishDrag, DRAG_END_DELAY_MS)
    }

    private fun endDrag() {
        if (dragDownTime == 0L) return
        val downTime = dragDownTime
        dragDownTime = 0L
        val target = window?.decorView ?: return
        if (mode == CarMode.MIRROR) {
            // Replayed on the phone as one swipe, scaled from car pixels to the phone picture.
            val pictureWidth = mirrorTexture.width.toFloat()
            val pictureHeight = mirrorTexture.height.toFloat()
            if (pictureWidth <= 0f || pictureHeight <= 0f) return
            val dx = (dragX - dragStartX) / pictureWidth
            val dy = (dragY - dragStartY) / pictureHeight
            val duration = (SystemClock.uptimeMillis() - downTime).coerceIn(80L, 600L)
            callbacks.onMirrorSwipe(
                0.5f, 0.5f,
                (0.5f + dx).coerceIn(0.02f, 0.98f), (0.5f + dy).coerceIn(0.02f, 0.98f),
                duration,
            )
            return
        }
        dispatch(target, MotionEvent.ACTION_UP, dragX, dragY, downTime, SystemClock.uptimeMillis())
    }

    /** Frees the browser and detaches the player. Call before dismiss(). */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        if (!created) return
        playerView.player = null
        exitFullscreen()
        webView?.let {
            webContainer.removeView(it)
            it.destroy()
        }
        webView = null
    }

    private fun switchTo(newMode: CarMode) {
        if (mode == CarMode.WEB && newMode != CarMode.WEB) pauseWeb()
        if (newMode == CarMode.WEB) webView?.onResume()
        if (mode == CarMode.VIDEO && newMode != CarMode.VIDEO && created) playerView.player = null
        mode = newMode
        applyMode()
    }

    private fun applyMode() {
        if (!created) return
        home.visibility = visible(mode == CarMode.HOME)
        playerView.visibility = visible(mode == CarMode.VIDEO)
        if (mode != CarMode.VIDEO) videoTitle.visibility = View.GONE
        webContainer.visibility = visible(mode == CarMode.WEB)
        mirrorContainer.visibility = visible(mode == CarMode.MIRROR)
    }

    private fun pauseWeb() {
        val web = webView ?: return
        exitFullscreen()
        web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause();});", null)
        web.onPause()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val web = WebView(context)
        web.setBackgroundColor(Color.BLACK)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            // Look like the regular Chrome browser so sites serve their normal mobile pages.
            userAgentString = userAgentString
                .replace("; wv)", ")")
                .replace(Regex("Version/\\S+ "), "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Keep everything inside the car browser; links that would open other phone
                // apps (intent://, vnd.youtube://, market://) are ignored.
                val scheme = request.url.scheme?.lowercase() ?: return false
                return scheme != "http" && scheme != "https"
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (url != null) callbacks.onWebPageChanged(url, view.title)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                // A web video went full screen (e.g. the YouTube full-screen button).
                exitFullscreen()
                fullscreenView = view
                fullscreenCallback = callback
                webContainer.addView(view, matchParent())
                web.visibility = View.INVISIBLE
            }

            override fun onHideCustomView() {
                removeFullscreenView()
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                view.url?.let { callbacks.onWebPageChanged(it, title) }
            }
        }
        web.onResume()
        return web
    }

    private fun exitFullscreen() {
        val callback = fullscreenCallback ?: return
        removeFullscreenView()
        callback.onCustomViewHidden()
    }

    private fun removeFullscreenView() {
        fullscreenView?.let { webContainer.removeView(it) }
        fullscreenView = null
        fullscreenCallback = null
        webView?.visibility = View.VISIBLE
    }

    /** Converts a car screen point to 0..1 coordinates inside the mirrored phone picture. */
    private fun mirrorPoint(x: Float, y: Float): PointF? {
        val location = IntArray(2)
        mirrorTexture.getLocationInWindow(location)
        val width = mirrorTexture.width
        val height = mirrorTexture.height
        if (width <= 0 || height <= 0) return null
        val nx = (x - location[0]) / width
        val ny = (y - location[1]) / height
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
        return PointF(nx, ny)
    }

    /** Sends one finger touch event, shaped like one from a real touch screen. */
    private fun dispatch(target: View, action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime, eventTime, action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        target.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun visible(show: Boolean) = if (show) View.VISIBLE else View.GONE

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private companion object {
        const val TAP_DURATION_MS = 60L
        const val DRAG_END_DELAY_MS = 150L
    }
}
