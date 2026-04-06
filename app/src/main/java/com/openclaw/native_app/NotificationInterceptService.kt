package com.openclaw.native_app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Captures active notifications for AndroidNodeProvider's "notifications.list" command.
 * Requires user grant in System Settings → Notification Access.
 */
class NotificationInterceptService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifIntercept"
        private val lock = Any()

        /** Shared snapshot read by AndroidNodeProvider */
        val notificationList = mutableListOf<StatusBarNotification>()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        synchronized(lock) {
            notificationList.removeAll { it.key == sbn.key }
            notificationList.add(0, sbn)
            if (notificationList.size > 100) notificationList.removeLast()
        }
        Log.d(TAG, "Notification posted: ${sbn.packageName}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(lock) {
            notificationList.removeAll { it.key == sbn.key }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
        try {
            val current: Array<StatusBarNotification>? = getActiveNotifications()
            synchronized(lock) {
                notificationList.clear()
                current?.forEach { notificationList.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get initial notifications: ${e.message}")
        }
    }
}
