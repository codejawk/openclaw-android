package com.openclaw.native_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * SamsungBatteryReceiver
 *
 * Handles Samsung Smart Manager / One UI Game Optimizer kill signals.
 * When Samsung broadcasts a background restriction intent, we immediately
 * restart the GatewayService to keep it alive.
 *
 * This is a best-effort workaround; the real fix is disabling battery
 * optimization for this app in Samsung Device Care settings.
 */
class SamsungBatteryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w("SamsungBattery", "Samsung battery manager signal: ${intent.action}")
        // Re-assert our foreground service
        val prefs = context.getSharedPreferences("openclaw_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("auth_token", "") ?: ""
        if (token.isNotEmpty()) {
            try {
                context.startForegroundService(GatewayService.startIntent(context, token))
            } catch (e: Exception) {
                Log.e("SamsungBattery", "Could not restart service: ${e.message}")
            }
        }
    }
}
