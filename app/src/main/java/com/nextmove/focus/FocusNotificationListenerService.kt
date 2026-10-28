package com.nextmove.focus

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Clears ordinary incoming notifications while focus is active; DND also removes their noise. */
class FocusNotificationListenerService : NotificationListenerService() {
    private val store by lazy { FocusSessionStore(this) }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (store.activeSession() == null || notification.packageName == packageName) return
        // Keep the playback notification and controls for apps the user explicitly allowed.
        if (notification.packageName in store.allowedPackages()) return
        val category = notification.notification.category
        if (category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_ALARM) return
        cancelNotification(notification.key)
    }
}
