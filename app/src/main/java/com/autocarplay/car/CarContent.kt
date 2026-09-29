package com.autocarplay.car

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.autocarplay.R
import com.autocarplay.core.CarMode
import com.autocarplay.mirror.MirrorManager
import kotlin.math.hypot

/**
 * The views shown on the car screen (layout `car_presentation`): home tiles, video player,
 * web browser, mirrored phone screen and a simple list panel.
 *
 * It is hosted either by [CarPresentation] on the Android Auto map surface, where taps and
 * drags arrive as coordinates and are replayed here ([injectTap], [injectScroll]), or directly
 * by [CarScreenActivity] on the car display, where real touch events arrive ([directTouch]).
 */
class CarContent(
    private val root: View,
    private val callbacks: Callbacks,
    private val directTouch: Boolean,
) {

    interface Callbacks {
        fun onHomeTile(tile: HomeTile)
        fun onControl(control: Control)
        fun onWebPageChanged(url: String, title: String?)

        /** A tap on the mirrored phone screen, in 0..1 coordinates of the phone screen. */
        fun onMirrorTap(x: Float, y: Float)

        /** A drag on the mirrored phone screen, in 0..1 coordinates of the phone screen. */
        fun onMirrorSwipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long)
    }

    enum class HomeTile { VIDEOS, YOUTUBE, LINKS, MIRROR }

    /** On-screen buttons, shown only when the content runs as a car activity. */
    enum class Control { BACK, CLOSE }

    data class ListRow(
        val title: String,
        val subtitle: String?,
        val icon: Int,
        val thumbnail: Bitmap?,
        val onClick: () -> Unit,
    )

    private val context = root.context
    private val handler = Handler(Looper.getMainLooper())
    private val home: View = root.findViewById(R.id.home)
    private val playerView: PlayerView = root.findViewById(R.id.player_view)
    private val videoTitle: TextView = root.findViewById(R.id.video_title)
    private val webContainer: FrameLayout = root.findViewById(R.id.web_container)
    private val mirrorContainer: FrameLayout = root.findViewById(R.id.mirror_container)
    private val mirrorFrame: AspectRatioFrameLayout = root.findViewById(R.id.mirror_frame)
    private val mirrorTexture: TextureView = root.findViewById(R.id.mirror_texture)
    private val mirrorHint: TextView = root.findViewById(R.id.mirror_hint)
    private val listPanel: View = root.findViewById(R.id.list_panel)
    private val listTitle: TextView = root.findViewById(R.id.list_title)
    private val listScroll: ScrollView = root.findViewById(R.id.list_scroll)
    private val listItems: LinearLayout = root.findViewById(R.id.list_items)
    private val controls: View = root.findViewById(R.id.controls)
    private val controlBack: View = root.findViewById(R.id.control_back)
    private val message: TextView = root.findViewById(R.id.message)

    private var webView: WebView? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var mirrorSurface: Surface? = null
    private var mode = CarMode.HOME
    private var playerControlsVisible = true

    // State of a drag being replayed from Android Auto scroll events.
    private var dragDownTime = 0L
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private val finishDrag = Runnable { endDrag() }
    private val hideMessage = Runnable { message.visibility = View.GONE }

    val isListShown: Boolean get() = listPanel.visibility == View.VISIBLE

    init {
        root.findViewById<View>(R.id.tile_videos).setOnClickListener { callbacks.onHomeTile(HomeTile.VIDEOS) }
        root.findViewById<View>(R.id.tile_youtube).setOnClickListener { callbacks.onHomeTile(HomeTile.YOUTUBE) }
        root.findViewById<View>(R.id.tile_links).setOnClickListener { callbacks.onHomeTile(HomeTile.LINKS) }
        root.findViewById<View>(R.id.tile_mirror).setOnClickListener { callbacks.onHomeTile(HomeTile.MIRROR) }
        root.findViewById<View>(R.id.list_back).setOnClickListener { hideList() }
        controlBack.setOnClickListener { callbacks.onControl(Control.BACK) }
        root.findViewById<View>(R.id.control_close).setOnClickListener { callbacks.onControl(Control.CLOSE) }

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                playerControlsVisible = visibility == View.VISIBLE
                videoTitle.visibility = if (videoTitle.text.isNullOrEmpty()) View.GONE else visibility
                updateControls()
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
        if (directTouch) enableDirectMirrorTouch()
        applyMode()
    }

    fun showHome() = switchTo(CarMode.HOME)

    fun showVideo(player: Player, title: String) {
        switchTo(CarMode.VIDEO)
        playerView.player = player
        videoTitle.text = title
        playerView.showController()
    }

    fun showWeb(url: String?) {
        switchTo(CarMode.WEB)
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
        val width = MirrorManager.phoneWidth
        val height = MirrorManager.phoneHeight
        if (width > 0 && height > 0) mirrorFrame.setAspectRatio(width.toFloat() / height)
        mirrorHint.visibility = if (MirrorManager.isActive) View.GONE else View.VISIBLE
    }

    fun setVideoZoom(zoom: Boolean) {
        playerView.resizeMode = if (zoom) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    fun showMessage(text: CharSequence, durationMs: Long = 4000) {
        message.text = text
        message.visibility = View.VISIBLE
        handler.removeCallbacks(hideMessage)
        handler.postDelayed(hideMessage, durationMs)
    }

    /** Shows a list over the current content; `rows == null` means "still loading". */
    fun showList(title: String, rows: List<ListRow>?, emptyText: String) {
        listTitle.text = title
        listItems.removeAllViews()
        val inflater = LayoutInflater.from(context)
        when {
            rows == null -> listItems.addView(listNote(inflater, context.getString(R.string.loading)))
            rows.isEmpty() -> listItems.addView(listNote(inflater, emptyText))
            else -> rows.forEach { row ->
                val view = inflater.inflate(R.layout.car_list_row, listItems, false)
                val image = view.findViewById<ImageView>(R.id.row_image)
                if (row.thumbnail != null) image.setImageBitmap(row.thumbnail) else image.setImageResource(row.icon)
                view.findViewById<TextView>(R.id.row_title).text = row.title
                val subtitle = view.findViewById<TextView>(R.id.row_subtitle)
                subtitle.text = row.subtitle
                subtitle.visibility = if (row.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
                view.setOnClickListener {
                    hideList()
                    row.onClick()
                }
                listItems.addView(view)
            }
        }
        listScroll.scrollTo(0, 0)
        listPanel.visibility = View.VISIBLE
        updateControls()
    }

    fun hideList() {
        listPanel.visibility = View.GONE
        updateControls()
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

    /** Replays a tap reported by Android Auto, in window coordinates. */
    fun injectTap(x: Float, y: Float) {
        if (mode == CarMode.MIRROR && !isListShown) {
            mirrorPoint(x, y)?.let { callbacks.onMirrorTap(it.x, it.y) }
            return
        }
        val (localX, localY) = toRoot(x, y)
        val downTime = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, localX, localY, downTime, downTime)
        handler.postDelayed({
            dispatch(MotionEvent.ACTION_UP, localX, localY, downTime, SystemClock.uptimeMillis())
        }, TAP_DURATION_MS)
    }

    /**
     * Replays a drag reported by Android Auto. Distances follow GestureDetector conventions.
     * Only lists, web pages and the mirrored phone scroll; video and home ignore drags.
     */
    fun injectScroll(distanceX: Float, distanceY: Float) {
        val scrollable = isListShown || mode == CarMode.WEB || mode == CarMode.MIRROR
        if (!scrollable) return
        val width = root.width.toFloat()
        val height = root.height.toFloat()
        if (width <= 2f || height <= 2f) return
        val replayOnPhone = mode == CarMode.MIRROR && !isListShown
        val now = SystemClock.uptimeMillis()
        if (dragDownTime == 0L) {
            dragDownTime = now
            dragStartX = width / 2f
            dragStartY = height / 2f
            dragX = dragStartX
            dragY = dragStartY
            if (!replayOnPhone) dispatch(MotionEvent.ACTION_DOWN, dragX, dragY, now, now)
        }
        dragX = (dragX - distanceX).coerceIn(1f, width - 1f)
        dragY = (dragY - distanceY).coerceIn(1f, height - 1f)
        if (!replayOnPhone) dispatch(MotionEvent.ACTION_MOVE, dragX, dragY, dragDownTime, now)
        handler.removeCallbacks(finishDrag)
        handler.postDelayed(finishDrag, DRAG_END_DELAY_MS)
    }

    /** Frees the browser and detaches the player. */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        exitFullscreen()
        webView?.let {
            webContainer.removeView(it)
            it.destroy()
        }
        webView = null
    }

    private fun endDrag() {
        if (dragDownTime == 0L) return
        val downTime = dragDownTime
        dragDownTime = 0L
        if (mode == CarMode.MIRROR && !isListShown) {
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
        dispatch(MotionEvent.ACTION_UP, dragX, dragY, downTime, SystemClock.uptimeMillis())
    }

    /** Real touches on the mirrored picture (car activity): a tap or a swipe on the phone. */
    @SuppressLint("ClickableViewAccessibility")
    private fun enableDirectMirrorTouch() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        mirrorTexture.setOnTouchListener { view, event ->
            val width = view.width.toFloat()
            val height = view.height.toFloat()
            if (width <= 0f || height <= 0f) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = event.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    val upX = event.x.coerceIn(0f, width)
                    val upY = event.y.coerceIn(0f, height)
                    if (hypot(upX - downX, upY - downY) > slop) {
                        val duration = (event.eventTime - downTime).coerceIn(50L, 1000L)
                        callbacks.onMirrorSwipe(downX / width, downY / height, upX / width, upY / height, duration)
                    } else {
                        callbacks.onMirrorTap(upX / width, upY / height)
                    }
                }
            }
            true
        }
    }

    private fun switchTo(newMode: CarMode) {
        if (mode == CarMode.WEB && newMode != CarMode.WEB) pauseWeb()
        if (newMode == CarMode.WEB) webView?.onResume()
        if (mode == CarMode.VIDEO && newMode != CarMode.VIDEO) playerView.player = null
        mode = newMode
        hideList()
        applyMode()
    }

    private fun applyMode() {
        home.visibility = visible(mode == CarMode.HOME)
        playerView.visibility = visible(mode == CarMode.VIDEO)
        if (mode != CarMode.VIDEO) videoTitle.visibility = View.GONE
        webContainer.visibility = visible(mode == CarMode.WEB)
        mirrorContainer.visibility = visible(mode == CarMode.MIRROR)
        updateControls()
    }

    private fun updateControls() {
        val show = directTouch && !isListShown && mode != CarMode.HOME &&
            (mode != CarMode.VIDEO || playerControlsVisible)
        controls.visibility = visible(show)
        controlBack.visibility = visible(mode == CarMode.WEB || mode == CarMode.MIRROR)
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

    private fun toRoot(x: Float, y: Float): Pair<Float, Float> {
        val location = IntArray(2)
        root.getLocationInWindow(location)
        return (x - location[0]) to (y - location[1])
    }

    /** Sends one finger touch event to the content, shaped like one from a real touch screen. */
    private fun dispatch(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
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
        root.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun listNote(inflater: LayoutInflater, text: String): View {
        val note = inflater.inflate(R.layout.car_list_note, listItems, false) as TextView
        note.text = text
        return note
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
