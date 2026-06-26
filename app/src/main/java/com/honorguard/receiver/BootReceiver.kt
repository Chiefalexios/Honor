package com.honorguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Nothing needed — InCallService is registered with Telecom system
        // and will be auto-started by Android on any call after reboot.
        // Add any startup maintenance tasks here if needed.
    }
}
