package com.autocarplay.mirror

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autocarplay.AutoCarPlayApp
import com.autocarplay.R
import com.autocarplay.phone.MainActivity

/**
 * Foreground service required by Android to capture the screen. It turns the user's consent
 * into a MediaProjection and keeps the phone screen awake while mirroring.
 */
class MirrorService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MirrorManager.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        // Android requires startForeground() before getMediaProjection().
        try {
            startForegroundWithNotification()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            MirrorManager.consentPending = false
            Toast.makeText(this, R.string.mirror_failed, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        val projection = data?.let {
            try {
                getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(resultCode, it)
            } catch (e: Exception) {
                Log.e(TAG, "getMediaProjection failed", e)
                null
            }
        }
        if (projection == null) {
            MirrorManager.consentPending = false
            Toast.makeText(this, R.string.mirror_failed, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        keepScreenOn()
        MirrorManager.start(this, projection)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        MirrorManager.stop()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, AutoCarPlayApp.CHANNEL_MIRROR)
            .setSmallIcon(R.drawable.ic_mirror)
            .setContentTitle(getString(R.string.mirror_notification_title))
            .setContentText(getString(R.string.mirror_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, getString(R.string.stop_mirroring), stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Mirroring shows whatever is on the phone screen, so the screen must stay on. */
    private fun keepScreenOn() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        @Suppress("DEPRECATION")
        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "AutoCarPlay:mirror",
        )
        lock.acquire(4 * 60 * 60 * 1000L)
        wakeLock = lock
    }

    companion object {
        private const val TAG = "MirrorService"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.autocarplay.action.STOP_MIRROR"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MirrorService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MirrorService::class.java))
        }
    }
}
