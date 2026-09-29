package com.autocarplay.phone

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.autocarplay.R
import com.autocarplay.core.CarHub
import com.autocarplay.core.CarMode
import com.autocarplay.core.LinkStore
import com.autocarplay.core.PhoneVideos
import com.autocarplay.core.PlayRequest
import com.autocarplay.core.SourceKind
import com.autocarplay.core.Sources
import com.autocarplay.mirror.MirrorManager
import com.autocarplay.mirror.MirrorPermissionActivity
import com.autocarplay.mirror.TouchControlService
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Phone-side remote control and setup guide. */
class MainActivity : AppCompatActivity() {

    private lateinit var store: LinkStore
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var urlLayout: TextInputLayout
    private lateinit var urlInput: TextInputEditText
    private lateinit var mirrorButton: Button
    private lateinit var touchButton: Button
    private lateinit var touchStatus: TextView
    private lateinit var linksList: LinearLayout
    private lateinit var permissionsCard: View
    private lateinit var grantVideos: Button
    private lateinit var grantNotifications: Button

    private var carConnected = false
    private val hubListener: () -> Unit = { updateUi() }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) playPickedVideo(uri)
    }
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = LinkStore(this)

        val scroll = findViewById<View>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        statusTitle = findViewById(R.id.status_title)
        statusText = findViewById(R.id.status_text)
        urlLayout = findViewById(R.id.url_layout)
        urlInput = findViewById(R.id.url_input)
        mirrorButton = findViewById(R.id.mirror_button)
        touchButton = findViewById(R.id.touch_button)
        touchStatus = findViewById(R.id.touch_status)
        linksList = findViewById(R.id.links_list)
        permissionsCard = findViewById(R.id.permissions_card)
        grantVideos = findViewById(R.id.grant_videos)
        grantNotifications = findViewById(R.id.grant_notifications)

        findViewById<View>(R.id.play_url).setOnClickListener { submitUrl(play = true) }
        findViewById<View>(R.id.save_url).setOnClickListener { submitUrl(play = false) }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submitUrl(play = true)
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.pick_video).setOnClickListener {
            pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        findViewById<View>(R.id.open_youtube).setOnClickListener {
            send(PlayRequest(SourceKind.WEB, Sources.YOUTUBE_HOME, "YouTube"))
        }
        mirrorButton.setOnClickListener {
            if (MirrorManager.isActive) {
                MirrorManager.stop()
            } else {
                MirrorPermissionActivity.launch(this)
                CarHub.startMirror()
            }
            updateUi()
        }
        touchButton.setOnClickListener { openAccessibilitySettings() }
        grantVideos.setOnClickListener { requestPermission.launch(PhoneVideos.permission) }
        grantNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        findViewById<View>(R.id.open_aa_settings).setOnClickListener { openAndroidAutoSettings() }
        findViewById<TextView>(R.id.compat_note).text = getString(
            if (Build.VERSION.SDK_INT >= 35) R.string.compat_ok else R.string.compat_old,
            Build.VERSION.RELEASE,
        )

        CarConnection(this).type.observe(this) { type ->
            carConnected = type == CarConnection.CONNECTION_TYPE_PROJECTION
            updateUi()
        }
    }

    override fun onStart() {
        super.onStart()
        CarHub.addListener(hubListener)
        updateUi()
        renderLinks()
    }

    override fun onStop() {
        CarHub.removeListener(hubListener)
        super.onStop()
    }

    private fun submitUrl(play: Boolean) {
        val text = urlInput.text?.toString().orEmpty()
        val request = Sources.requestFor(text)
        if (request == null) {
            urlLayout.error = getString(R.string.invalid_link)
            return
        }
        urlLayout.error = null
        store.add(request.title, request.uri)
        urlInput.setText("")
        renderLinks()
        if (play) send(request) else toast(R.string.link_saved)
    }

    private fun playPickedVideo(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not every picker offers persistable access; access still lasts while the app runs.
        }
        val name = displayName(uri) ?: getString(R.string.picked_video)
        send(PlayRequest(SourceKind.VIDEO, uri.toString(), name))
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun send(request: PlayRequest) {
        val shown = CarHub.open(request)
        toast(if (shown) R.string.sent_to_car else R.string.queued_for_car)
        updateUi()
    }

    private fun updateUi() {
        val screen = CarHub.screen
        statusTitle.setText(
            when {
                screen != null -> R.string.status_car_open
                carConnected -> R.string.status_connected
                else -> R.string.status_not_connected
            },
        )
        statusText.text = when {
            screen != null && screen.mode != CarMode.HOME ->
                getString(R.string.status_showing, screen.title ?: getString(modeName(screen.mode)))
            screen != null -> getString(R.string.status_car_idle)
            CarHub.hasPendingRequest -> getString(R.string.status_pending)
            carConnected -> getString(R.string.status_open_on_car)
            else -> getString(R.string.status_how_to_connect)
        }

        mirrorButton.setText(if (MirrorManager.isActive) R.string.stop_mirroring else R.string.start_mirroring)
        val touchOn = TouchControlService.instance != null
        touchStatus.setText(if (touchOn) R.string.touch_on else R.string.touch_off)
        touchButton.setText(if (touchOn) R.string.touch_settings else R.string.touch_enable)

        val needsVideos = !PhoneVideos.hasAccess(this)
        val needsNotifications = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        grantVideos.visibility = if (needsVideos) View.VISIBLE else View.GONE
        grantNotifications.visibility = if (needsNotifications) View.VISIBLE else View.GONE
        permissionsCard.visibility = if (needsVideos || needsNotifications) View.VISIBLE else View.GONE
    }

    private fun renderLinks() {
        linksList.removeAllViews()
        val links = store.links()
        if (links.isEmpty()) {
            val empty = layoutInflater.inflate(R.layout.item_empty, linksList, false) as TextView
            empty.setText(R.string.no_links)
            linksList.addView(empty)
            return
        }
        links.forEach { link ->
            val row = layoutInflater.inflate(R.layout.item_link, linksList, false)
            row.findViewById<TextView>(R.id.link_title).text = link.title
            row.findViewById<TextView>(R.id.link_url).text = link.url
            row.findViewById<View>(R.id.link_play).setOnClickListener {
                Sources.requestFor(link.url, link.title)?.let { send(it) }
            }
            row.findViewById<View>(R.id.link_delete).setOnClickListener {
                store.remove(link.id)
                renderLinks()
            }
            linksList.addView(row)
        }
    }

    private fun openAccessibilitySettings() {
        toast(R.string.touch_enable_hint)
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAndroidAutoSettings() {
        val intent = packageManager.getLaunchIntentForPackage(ANDROID_AUTO_PACKAGE)
            ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$ANDROID_AUTO_PACKAGE"))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.android_auto_missing)
        }
    }

    private fun modeName(mode: CarMode): Int = when (mode) {
        CarMode.HOME -> R.string.app_name
        CarMode.VIDEO -> R.string.mode_video
        CarMode.WEB -> R.string.mode_web
        CarMode.MIRROR -> R.string.mirror_title
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    }
}
