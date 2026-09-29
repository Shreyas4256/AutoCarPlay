package com.autocarplay.mirror

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.autocarplay.AutoCarPlayApp
import com.autocarplay.R

/**
 * Phone notification asking the user to allow screen capture. Used when mirroring is started
 * from the car and Android does not let the app open the prompt by itself.
 */
object MirrorPrompt {
    private const val NOTIFICATION_ID = 43

    fun show(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = PendingIntent.getActivity(
            context, 0, MirrorPermissionActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, AutoCarPlayApp.CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_mirror)
            .setContentTitle(context.getString(R.string.mirror_prompt_title))
            .setContentText(context.getString(R.string.mirror_prompt_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
