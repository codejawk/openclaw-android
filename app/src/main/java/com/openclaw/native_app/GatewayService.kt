package com.openclaw.native_app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * GatewayService
 *
 * Android Foreground Service that hosts the OpenClaw Node.js gateway.
 * Handles:
 *  - WakeLock acquisition to prevent Doze/Samsung killing the process
 *  - Bootstrap on first run
 *  - NodeRunner lifecycle
 *  - AndroidNodeProvider WebSocket connection
 *  - Voice recognition (SpeechRecognizer) lifecycle
 */
@AndroidEntryPoint
class GatewayService : Service() {

    companion object {
        private const val TAG             = "GatewayService"
        const val CHANNEL_ID              = "openclaw_gateway"
        const val NOTIFICATION_ID         = 1001
        const val ACTION_START            = "com.openclaw.ACTION_START"
        const val ACTION_STOP             = "com.openclaw.ACTION_STOP"
        const val EXTRA_AUTH_TOKEN        = "auth_token"

        fun startIntent(ctx: Context, token: String) =
            Intent(ctx, GatewayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_AUTH_TOKEN, token)
            }

        fun stopIntent(ctx: Context) =
            Intent(ctx, GatewayService::class.java).apply {
                action = ACTION_STOP
            }
    }

    @Inject lateinit var bootstrap: BootstrapManager
    @Inject lateinit var nodeRunner: NodeRunner
    @Inject lateinit var nodeProvider: AndroidNodeProvider
    @Inject lateinit var tokenTracker: TokenTracker

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        requestBatteryOptimizationExemption()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val token = intent.getStringExtra(EXTRA_AUTH_TOKEN) ?: generateToken()
                startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
                launchGateway(token)
            }
            ACTION_STOP -> {
                stopGateway()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        nodeRunner.stop()
        nodeProvider.disconnect()
        releaseWakeLock()
        super.onDestroy()
        Log.i(TAG, "GatewayService destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Re-schedule restart if task is removed from recents
        val restartIntent = Intent(applicationContext, GatewayService::class.java).apply {
            action = ACTION_START
        }
        val pi = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.ELAPSED_REALTIME, 1000, pi)
        super.onTaskRemoved(rootIntent)
    }

    // ──────────────────────────────────────────────────────────────
    // Private

    private fun launchGateway(token: String) {
        serviceScope.launch {
            // Bootstrap (first-run extraction)
            updateNotification("Bootstrapping…")
            withContext(Dispatchers.IO) { bootstrap.bootstrap() }

            // Start Node.js
            updateNotification("Starting gateway…")
            nodeRunner.start(token)

            // Wait for gateway to be ready, then connect node provider
            delay(4000)
            nodeProvider.connect(token)

            // Start token tracker interceptor
            tokenTracker.startIntercepting()

            updateNotification("Gateway running on :${BootstrapManager.GATEWAY_PORT}")

            // Observe state changes
            nodeRunner.state.collect { state ->
                when (state) {
                    NodeState.RUNNING  -> updateNotification("Gateway running on :${BootstrapManager.GATEWAY_PORT}")
                    NodeState.CRASHED  -> updateNotification("Gateway crashed — retrying…")
                    NodeState.STARTING -> updateNotification("Starting gateway…")
                    NodeState.STOPPED  -> updateNotification("Gateway stopped")
                }
            }
        }
    }

    private fun stopGateway() {
        nodeRunner.stop()
        nodeProvider.disconnect()
        tokenTracker.stopIntercepting()
    }

    // ──────────────────────────────────────────────────────────────
    // Notification helpers

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.gateway_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "OpenClaw AI Gateway foreground service"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val mainIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.gateway_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(mainIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ──────────────────────────────────────────────────────────────
    // WakeLock + Battery

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "openclaw:gateway"
        ).also { it.acquire(/* max 4 hours */ 4 * 60 * 60 * 1000L) }
        Log.i(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release failed: ${e.message}")
        }
        wakeLock = null
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(TAG, "Not ignoring battery optimizations — user should grant this in Settings")
            // Actual request is initiated from MainActivity via Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        }
    }

    private fun generateToken(): String {
        return java.util.UUID.randomUUID().toString().replace("-", "")
    }
}
