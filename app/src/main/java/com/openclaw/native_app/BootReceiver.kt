package com.openclaw.native_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver — auto-start OpenClaw gateway after device reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i("BootReceiver", "Boot/update received — starting gateway")
            val prefs = context.getSharedPreferences("openclaw_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("auth_token", "") ?: ""
            if (token.isNotEmpty()) {
                val svcIntent = GatewayService.startIntent(context, token)
                context.startForegroundService(svcIntent)
            }
        }
    }
}
