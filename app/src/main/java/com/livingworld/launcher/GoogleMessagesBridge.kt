package com.livingworld.launcher

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors

/**
 * Google Messages remains the default SMS/RCS application.
 * This listener extracts only posted notification data. It cannot read the
 * complete RCS inbox, initiate RCS sessions, or promise delivery.
 */
class GoogleMessagesBridge : NotificationListenerService() {
  private val worker = Executors.newSingleThreadExecutor()
  override fun onListenerConnected() {
    super.onListenerConnected()
    BridgeInbox.clear()
    activeNotifications?.forEach { receive(it) }
  }
  override fun onNotificationPosted(sbn: StatusBarNotification) { receive(sbn) }
  override fun onNotificationRemoved(sbn: StatusBarNotification) {
    if (sbn.packageName == GOOGLE_MESSAGES) {
      BridgeInbox.remove(sbn.key)
      changed()
    }
  }
  override fun onListenerDisconnected() { BridgeInbox.clear(); changed() }
  override fun onDestroy() {
    BridgeInbox.clear()
    worker.shutdown()
    super.onDestroy()
  }

  private fun changed() {
    sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))
  }

  private fun receive(sbn: StatusBarNotification) {
    if (sbn.packageName != GOOGLE_MESSAGES || SmsRole.isDefault(this)) return
    val n = sbn.notification ?: return
    if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
    val extras = n.extras
    val title = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
      ?: extras?.getCharSequence(Notification.EXTRA_TITLE)
    val preview = extras?.getCharSequence(Notification.EXTRA_TEXT)
    val displayName = title?.toString()?.trim()?.take(120).orEmpty()
    val excerpt = preview?.toString()?.trim()?.take(240).orEmpty()
    if (displayName.isBlank() && excerpt.isBlank()) return
    val threadId = n.shortcutId?.takeIf { it.isNotBlank() }
      ?.let { "shortcut:" + it }
      ?: "notification:" + sbn.key
    val reply = n.actions?.firstOrNull { action ->
      action.remoteInputs?.any { it.allowFreeFormInput } == true
    }
    BridgeInbox.put(BridgeEntry(
      sbn.key, displayName.ifBlank { "Google Messages" },
      excerpt, sbn.postTime, n.contentIntent, reply, threadId
    ))
    changed()

    val events = mutableListOf<RelayEvent>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && extras != null) {
      try {
        @Suppress("DEPRECATION")
        fun messages(key: String): List<Notification.MessagingStyle.Message> {
          val raw = extras.getParcelableArray(key) ?: return emptyList()
          return Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw)
        }
        for (m in (messages(Notification.EXTRA_HISTORIC_MESSAGES) +
          messages(Notification.EXTRA_MESSAGES)).takeLast(30)) {
          val from = m.senderPerson?.name?.toString()?.take(120)
            ?: displayName.ifBlank { "Message" }
          val outgoing = m.senderPerson == null
          val body = m.text?.toString()?.take(4000)?.trim().orEmpty()
            .ifBlank {
              if (m.dataMimeType?.startsWith("image/") == true) "Photo"
              else if (m.dataMimeType?.startsWith("video/") == true) "Video"
              else if (m.dataUri != null) "Attachment" else ""
            }
          if (body.isNotBlank()) events.add(RelayEvent(from, body, m.timestamp, outgoing))
        }
      } catch (_: Exception) { /* Redacted or non-MessagingStyle notification. */ }
    }
    if (events.isEmpty() && excerpt.isNotBlank()) {
      events.add(RelayEvent(displayName, excerpt, sbn.postTime, false))
    }
    if (events.isEmpty()) return
    worker.execute {
      try { RelayHistoryStore.record(applicationContext, threadId,
        displayName.ifBlank { "Google Messages" }, events) }
      catch (_: Exception) { /* Never break the system notification listener. */ }
      changed()
    }
  }

  companion object {
    const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"
    const val ACTION_UPDATED = "com.livingworld.launcher.GOOGLE_MESSAGES_RELAY_UPDATED"
    fun hasAccess(context: Context): Boolean {
      val enabled = Settings.Secure.getString(context.contentResolver,
        "enabled_notification_listeners") ?: return false
      return enabled.split(':').any {
        ComponentName.unflattenFromString(it)?.packageName == context.packageName
      }
    }
    fun settingsIntent() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
  }
}

data class BridgeEntry(
  val notificationKey: String, val sender: String, val preview: String, val postedAt: Long,
  val openIntent: PendingIntent?, val replyAction: Notification.Action?, val threadId: String
)

object BridgeInbox {
  private val entries = LinkedHashMap<String, BridgeEntry>()
  @Synchronized fun put(entry: BridgeEntry) {
    entries[entry.notificationKey] = entry
    while (entries.size > 60) entries.remove(entries.keys.first())
  }
  @Synchronized fun remove(key: String) { entries.remove(key) }
  @Synchronized fun clear() { entries.clear() }
  @Synchronized fun snapshot(): List<BridgeEntry> = entries.values.sortedByDescending { it.postedAt }
  @Synchronized fun find(key: String): BridgeEntry? = entries[key]
  @Synchronized fun findThread(threadId: String): BridgeEntry? =
    entries.values.filter { it.threadId == threadId }.maxByOrNull { it.postedAt }
}
