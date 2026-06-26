package com.honorguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class HonorGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Incoming call channel (high priority — shows heads-up)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING_CALL,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming call notifications"
                setShowBadge(false)
            }
        )

        // Active call channel
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ACTIVE_CALL,
                "Active Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing call status"
                setShowBadge(false)
            }
        )

        // Recording channel
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECORDING,
                "Call Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Call recording in progress"
            }
        )
    }

    companion object {
        const val CHANNEL_INCOMING_CALL = "incoming_call"
        const val CHANNEL_ACTIVE_CALL   = "active_call"
        const val CHANNEL_RECORDING     = "recording"
    }
}
