package dev.buddy

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Opt-in: keeps the last 30 notifications (app, title, text, time) in memory only, for Claude to read when asked. */
class BuddyNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        NotificationStore.clear()
        activeNotifications?.forEach(::keep)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = keep(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = NotificationStore.remove(sbn.key)

    override fun onListenerDisconnected() = NotificationStore.clear()

    private fun keep(sbn: StatusBarNotification) = try { remember(sbn) } catch (e: RuntimeException) { /* a bad notification must never take the listener down */ }

    private fun remember(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = sbn.notification.extras
        val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (e: Exception) { sbn.packageName }
        NotificationStore.add(
            Notif(
                sbn.key, app, extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(), sbn.postTime,
            ),
        )
    }
}
