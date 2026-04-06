package com.openclaw.native_app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationServices
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AndroidNodeProvider
 *
 * Connects to the OpenClaw gateway via WebSocket and registers this Android device
 * as a Node that can execute device commands.
 *
 * Implements all 15 device commands using native Android APIs (no Termux needed).
 */
@Singleton
class AndroidNodeProvider @Inject constructor(
    private val context: Context,
    private val bootstrap: BootstrapManager,
    private val tokenTracker: TokenTracker
) {
    companion object {
        private const val TAG        = "AndroidNodeProvider"
        private const val NODE_ID    = "android-native-node"
        private const val NODE_NAME  = "Android (Native)"
    }

    private val gson   = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Shared mutable state for photo captures (set by CameraX callback)
    var lastCapturedPhotoPath: String? = null

    // ──────────────────────────────────────────────────────────────

    fun connect(authToken: String) {
        val url = "ws://127.0.0.1:${BootstrapManager.GATEWAY_PORT}/node/ws?token=$authToken"
        Log.i(TAG, "Connecting to gateway WebSocket: $url")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                registerNode(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleMessage(webSocket, text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                scope.launch {
                    delay(5000)
                    connect(authToken)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Service stopping")
        webSocket = null
        scope.cancel()
    }

    // ──────────────────────────────────────────────────────────────
    // Protocol

    private fun registerNode(ws: WebSocket) {
        val msg = JSONObject().apply {
            put("type", "register")
            put("nodeId", NODE_ID)
            put("name", NODE_NAME)
            put("platform", "android")
            put("version", "1.0.0")
            put("capabilities", gson.toJson(listOf(
                "camera.capture", "camera.front", "location.get",
                "calendar.list", "calendar.create",
                "contacts.list",
                "sms.send", "sms.list",
                "notifications.list", "notifications.send",
                "screen.capture", "media.photos",
                "audio.record", "haptic.vibrate", "app.launch"
            )))
        }
        ws.send(msg.toString())
    }

    private suspend fun handleMessage(ws: WebSocket, text: String) {
        try {
            val msg   = JSONObject(text)
            val type  = msg.optString("type")
            val reqId = msg.optString("requestId", "")
            val cmd   = msg.optString("command", "")
            val args  = msg.optJSONObject("args") ?: JSONObject()

            if (type != "command") return
            Log.d(TAG, "Command received: $cmd (reqId=$reqId)")

            val result = try {
                executeCommand(cmd, args)
            } catch (e: Exception) {
                Log.e(TAG, "Command $cmd failed: ${e.message}", e)
                mapOf("error" to (e.message ?: "Unknown error"))
            }

            val response = JSONObject().apply {
                put("type", "commandResult")
                put("requestId", reqId)
                put("nodeId", NODE_ID)
                put("success", !result.containsKey("error"))
                put("result", JSONObject(gson.toJson(result)))
            }
            ws.send(response.toString())
        } catch (e: Exception) {
            Log.e(TAG, "handleMessage failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Command Dispatch (15 commands)

    private suspend fun executeCommand(cmd: String, args: JSONObject): Map<String, Any?> {
        return when (cmd) {
            "camera.capture"       -> cmdCameraCapture(args, useFront = false)
            "camera.front"         -> cmdCameraCapture(args, useFront = true)
            "location.get"         -> cmdLocationGet()
            "calendar.list"        -> cmdCalendarList(args)
            "calendar.create"      -> cmdCalendarCreate(args)
            "contacts.list"        -> cmdContactsList(args)
            "sms.send"             -> cmdSmsSend(args)
            "sms.list"             -> cmdSmsList(args)
            "notifications.list"   -> cmdNotificationsList()
            "notifications.send"   -> cmdNotificationsSend(args)
            "screen.capture"       -> cmdScreenCapture()
            "media.photos"         -> cmdMediaPhotos(args)
            "audio.record"         -> cmdAudioRecord(args)
            "haptic.vibrate"       -> cmdHapticVibrate(args)
            "app.launch"           -> cmdAppLaunch(args)
            else -> mapOf("error" to "Unknown command: $cmd")
        }
    }

    // ── 1. camera.capture / camera.front ──────────────────────────
    @Suppress("UNUSED_PARAMETER")
    private suspend fun cmdCameraCapture(args: JSONObject, useFront: Boolean): Map<String, Any?> {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            return mapOf("error" to "CAMERA permission not granted")
        }
        // Trigger intent-based capture via MainActivity broadcast
        val captureFile = File(File(bootstrap.filesDir, "captures"),
            "capture_${System.currentTimeMillis()}.jpg")
        captureFile.parentFile?.mkdirs()

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", captureFile)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = if (useFront) MainActivity.ACTION_CAMERA_FRONT else MainActivity.ACTION_CAMERA_BACK
            putExtra(MainActivity.EXTRA_CAPTURE_URI, uri.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)

        // Wait up to 15 seconds for photo to be captured
        var waited = 0
        while (waited < 15000) {
            if (captureFile.exists() && captureFile.length() > 0) break
            delay(500)
            waited += 500
        }

        return if (captureFile.exists()) {
            mapOf(
                "path"      to captureFile.absolutePath,
                "size"      to captureFile.length(),
                "timestamp" to System.currentTimeMillis(),
                "lens"      to if (useFront) "front" else "back"
            )
        } else {
            mapOf("error" to "Photo capture timed out")
        }
    }

    // ── 2. location.get ──────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private suspend fun cmdLocationGet(): Map<String, Any?> {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return mapOf("error" to "LOCATION permission not granted")
        }
        return suspendCancellableCoroutine { cont ->
            val client = LocationServices.getFusedLocationProviderClient(context)
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    cont.resume(mapOf(
                        "latitude"  to loc.latitude,
                        "longitude" to loc.longitude,
                        "accuracy"  to loc.accuracy,
                        "altitude"  to loc.altitude,
                        "timestamp" to loc.time
                    ), null)
                } else {
                    cont.resume(mapOf("error" to "Location unavailable"), null)
                }
            }.addOnFailureListener { e ->
                cont.resume(mapOf("error" to e.message), null)
            }
        }
    }

    // ── 3. calendar.list ─────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun cmdCalendarList(args: JSONObject): Map<String, Any?> {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            return mapOf("error" to "CALENDAR permission not granted")
        }
        val limit = args.optInt("limit", 20)
        val events = mutableListOf<Map<String, Any?>>()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION
        )
        val now = System.currentTimeMillis()
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            "${CalendarContract.Events.DTSTART} >= ?",
            arrayOf(now.toString()),
            "${CalendarContract.Events.DTSTART} ASC LIMIT $limit"
        )
        cursor?.use {
            while (it.moveToNext()) {
                events.add(mapOf(
                    "id"          to it.getLong(0),
                    "title"       to (it.getString(1) ?: ""),
                    "start"       to it.getLong(2),
                    "end"         to it.getLong(3),
                    "description" to (it.getString(4) ?: ""),
                    "location"    to (it.getString(5) ?: "")
                ))
            }
        }
        return mapOf("events" to events, "count" to events.size)
    }

    // ── 4. calendar.create ───────────────────────────────────────
    private fun cmdCalendarCreate(args: JSONObject): Map<String, Any?> {
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            return mapOf("error" to "WRITE_CALENDAR permission not granted")
        }
        val title       = args.optString("title", "OpenClaw Event")
        val description = args.optString("description", "")
        val location    = args.optString("location", "")
        val dtStart     = args.optLong("dtStart", System.currentTimeMillis() + 3600_000)
        val dtEnd       = args.optLong("dtEnd",   dtStart + 3600_000)

        val cv = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, 1)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, dtStart)
            put(CalendarContract.Events.DTEND, dtEnd)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv)
        return if (uri != null) {
            mapOf("success" to true, "eventId" to uri.lastPathSegment, "title" to title)
        } else {
            mapOf("error" to "Failed to create calendar event")
        }
    }

    // ── 5. contacts.list ─────────────────────────────────────────
    private fun cmdContactsList(args: JSONObject): Map<String, Any?> {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return mapOf("error" to "CONTACTS permission not granted")
        }
        val limit   = args.optInt("limit", 50)
        val query   = args.optString("query", "")
        val contacts = mutableListOf<Map<String, Any?>>()
        val selection = if (query.isNotEmpty())
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?" else null
        val selArgs = if (query.isNotEmpty()) arrayOf("%$query%") else null
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER),
            selection, selArgs,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC LIMIT $limit"
        )
        cursor?.use {
            while (it.moveToNext()) {
                val id    = it.getLong(0)
                val name  = it.getString(1) ?: ""
                val hasPh = it.getInt(2) > 0
                var phone = ""
                if (hasPh) {
                    val phCursor = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(id.toString()), null
                    )
                    phCursor?.use { pc ->
                        if (pc.moveToFirst()) phone = pc.getString(0) ?: ""
                    }
                }
                contacts.add(mapOf("id" to id, "name" to name, "phone" to phone))
            }
        }
        return mapOf("contacts" to contacts, "count" to contacts.size)
    }

    // ── 6. sms.send ──────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun cmdSmsSend(args: JSONObject): Map<String, Any?> {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            return mapOf("error" to "SEND_SMS permission not granted")
        }
        val to   = args.optString("to", "")
        val body = args.optString("body", "")
        if (to.isEmpty()) return mapOf("error" to "Missing 'to' field")
        return try {
            val sm = context.getSystemService(SmsManager::class.java)
            sm.sendTextMessage(to, null, body, null, null)
            mapOf("success" to true, "to" to to)
        } catch (e: Exception) {
            mapOf("error" to e.message)
        }
    }

    // ── 7. sms.list ──────────────────────────────────────────────
    private fun cmdSmsList(args: JSONObject): Map<String, Any?> {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            return mapOf("error" to "READ_SMS permission not granted")
        }
        val limit   = args.optInt("limit", 20)
        val messages = mutableListOf<Map<String, Any?>>()
        val cursor  = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                    Telephony.Sms.DATE, Telephony.Sms.TYPE),
            null, null,
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )
        cursor?.use {
            while (it.moveToNext()) {
                messages.add(mapOf(
                    "id"      to it.getLong(0),
                    "address" to (it.getString(1) ?: ""),
                    "body"    to (it.getString(2) ?: ""),
                    "date"    to it.getLong(3),
                    "type"    to it.getInt(4)
                ))
            }
        }
        return mapOf("messages" to messages, "count" to messages.size)
    }

    // ── 8. notifications.list ─────────────────────────────────────
    private fun cmdNotificationsList(): Map<String, Any?> {
        val notifications = NotificationInterceptService.notificationList.map {
            mapOf(
                "key"     to it.key,
                "pkg"     to it.packageName,
                "title"   to (it.notification.extras.getString("android.title") ?: ""),
                "text"    to (it.notification.extras.getString("android.text") ?: ""),
                "time"    to it.postTime
            )
        }
        return mapOf("notifications" to notifications, "count" to notifications.size)
    }

    // ── 9. notifications.send ─────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun cmdNotificationsSend(args: JSONObject): Map<String, Any?> {
        val title   = args.optString("title", "OpenClaw")
        val text    = args.optString("text", "")
        val channel = "openclaw_alerts"
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        if (nm.getNotificationChannel(channel) == null) {
            nm.createNotificationChannel(android.app.NotificationChannel(
                channel, "OpenClaw Alerts", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = android.app.Notification.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), n)
        return mapOf("success" to true, "title" to title)
    }

    // ── 10. screen.capture ────────────────────────────────────────
    private fun cmdScreenCapture(): Map<String, Any?> {
        // MediaProjection requires a foreground activity — trigger via MainActivity
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SCREEN_CAPTURE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
        return mapOf("status" to "capture_requested", "note" to "Screenshot will be saved to captures/")
    }

    // ── 11. media.photos ─────────────────────────────────────────
    private fun cmdMediaPhotos(args: JSONObject): Map<String, Any?> {
        if (!hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) {
            return mapOf("error" to "READ_MEDIA_IMAGES permission not granted")
        }
        val limit = args.optInt("limit", 20)
        val photos = mutableListOf<Map<String, Any?>>()
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_TAKEN),
            null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT $limit"
        )
        cursor?.use {
            while (it.moveToNext()) {
                val id   = it.getLong(0)
                val uri  = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                photos.add(mapOf(
                    "id"        to id,
                    "name"      to (it.getString(1) ?: ""),
                    "size"      to it.getLong(2),
                    "dateTaken" to it.getLong(3),
                    "uri"       to uri.toString()
                ))
            }
        }
        return mapOf("photos" to photos, "count" to photos.size)
    }

    // ── 12. audio.record ─────────────────────────────────────────
    private suspend fun cmdAudioRecord(args: JSONObject): Map<String, Any?> {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            return mapOf("error" to "RECORD_AUDIO permission not granted")
        }
        val durationSec = args.optInt("duration", 5).coerceIn(1, 60)
        val outFile = File(File(bootstrap.filesDir, "captures"),
            "audio_${System.currentTimeMillis()}.m4a")
        outFile.parentFile?.mkdirs()

        return try {
            val recorder = android.media.MediaRecorder(context).apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            delay(durationSec * 1000L)
            recorder.stop()
            recorder.release()
            mapOf("path" to outFile.absolutePath, "duration" to durationSec, "size" to outFile.length())
        } catch (e: Exception) {
            mapOf("error" to e.message)
        }
    }

    // ── 13. haptic.vibrate ───────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun cmdHapticVibrate(args: JSONObject): Map<String, Any?> {
        val durationMs  = args.optLong("duration", 200)
        val amplitude   = args.optInt("amplitude", VibrationEffect.DEFAULT_AMPLITUDE)
        val vibrator    = context.getSystemService(Vibrator::class.java)
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        return mapOf("success" to true, "duration" to durationMs)
    }

    // ── 14. app.launch ───────────────────────────────────────────
    private fun cmdAppLaunch(args: JSONObject): Map<String, Any?> {
        val pkg   = args.optString("package", "")
        val query = args.optString("query", "")

        val packageName = when {
            pkg.isNotEmpty() -> pkg
            query.isNotEmpty() -> resolveAppByQuery(query)
            else -> return mapOf("error" to "Need 'package' or 'query'")
        } ?: return mapOf("error" to "App not found: $query")

        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return mapOf("error" to "No launch intent for $packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            mapOf("success" to true, "package" to packageName)
        } catch (e: Exception) {
            mapOf("error" to e.message)
        }
    }

    private fun resolveAppByQuery(query: String): String? {
        val pm  = context.packageManager
        val q   = query.lowercase()
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps.firstOrNull { app ->
            val label = pm.getApplicationLabel(app).toString().lowercase()
            label.contains(q) || app.packageName.lowercase().contains(q)
        }?.packageName
    }

    // ──────────────────────────────────────────────────────────────
    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}
