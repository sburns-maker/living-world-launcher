package com.livingworld.launcher

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** An explicitly enabled, local-only, short-lived preview of Google Messages notifications.
 * Does not access the SMS provider or persist/transmit message content.
 */
class GoogleMessagesBridge : NotificationListenerService() {
  override fun onListenerConnected() {
    super.onListenerConnected()
    BridgeInbox.clear()
    activeNotifications?.forEach { receive(it) }
  }
  override fun onNotificationPosted(sbn: StatusBarNotification) { receive(sbn) }
  override fun onNotificationRemoved(sbn: StatusBarNotification) {
    if (sbn.packageName == GOOGLE_MESSAGES) BridgeInbox.remove(sbn.key)
  }
  override fun onListenerDisconnected() { BridgeInbox.clear() }
  override fun onDestroy() { BridgeInbox.clear(); super.onDestroy() }

  private fun receive(sbn: StatusBarNotification) {
    if (sbn.packageName != GOOGLE_MESSAGES) return
    val n = sbn.notification ?: return
    // Notification extras can be absent or redacted; never infer a complete chat history.
    val title = n.extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
      ?: n.extras?.getCharSequence(Notification.EXTRA_TITLE)
    val preview = n.extras?.getCharSequence(Notification.EXTRA_TEXT)
    val displayName = title?.toString()?.take(120)?.trim().orEmpty()
    val excerpt = preview?.toString()?.take(240)?.trim().orEmpty()
    if (displayName.isBlank() && excerpt.isBlank()) return
    BridgeInbox.put(BridgeEntry(sbn.key, displayName.ifBlank { "Google Messages" }, excerpt, sbn.postTime))
  }
  companion object {
    const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"
    fun hasAccess(activity: android.app.Activity): Boolean {
      val enabled = Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners") ?: return false
      return enabled.split(':').any { ComponentName.unflattenFromString(it)?.packageName == activity.packageName }
    }
    fun settingsIntent() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
  }
}

/** Ephemeral in-process state only. App death or listener disconnect clears this preview. */
data class BridgeEntry(val notificationKey: String, val sender: String, val preview: String, val postedAt: Long)
object BridgeInbox {
  private val entries = LinkedHashMap<String, BridgeEntry>()
  @Synchronized fun put(entry: BridgeEntry) {
    entries[entry.notificationKey] = entry
    while (entries.size > 30) entries.remove(entries.keys.first())
  }
  @Synchronized fun remove(key: String) { entries.remove(key) }
  @Synchronized fun clear() { entries.clear() }
  @Synchronized fun snapshot(): List<BridgeEntry> = entries.values.sortedByDescending { it.postedAt }
}
