package com.autocarplay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AutoCarPlayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MIRROR, getString(R.string.channel_mirror), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROMPT, getString(R.string.channel_prompt), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val CHANNEL_MIRROR = "mirror"
        const val CHANNEL_PROMPT = "prompt"
    }
}
