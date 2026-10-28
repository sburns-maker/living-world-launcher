package com.livingworld.launcher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import java.security.MessageDigest

/**
 * Native incoming message previews, quick replies, and truthful carrier send statuses.
 * Sensitive message contents are not included in lockscreen public notifications.
 */
object SmsNotification {
  private const val INCOMING = "incoming_sms"
  private const val RESULTS = "carrier_send_results"
  private const val INCOMING_TAG = "living_world_incoming"

  private fun manager(context: Context): NotificationManager? =
    context.getSystemService(NotificationManager::class.java)

  fun permissionStatus(context: Context): String {
    val service = manager(context) ?: return "notification manager unavailable"
    if (Build.VERSION.SDK_INT >= 33 &&
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
      return "permission not granted"
    if (!service.areNotificationsEnabled()) return "notifications disabled in Samsung settings"
    val channel = service.getNotificationChannel(INCOMING)
    if (channel?.importance == NotificationManager.IMPORTANCE_NONE)
      return "incoming message channel muted in Samsung settings"
    return "enabled"
  }

  private fun canPost(context: Context): Boolean {
    val service = manager(context) ?: return false
    if (Build.VERSION.SDK_INT >= 33 &&
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
      return false
    return service.areNotificationsEnabled()
  }

  private fun ensureChannel(context: Context, id: String, name: String, importance: Int) {
    manager(context)?.createNotificationChannel(NotificationChannel(id, name, importance))
  }

  private fun openMessages(context: Context, threadId: Long? = null,
                           address: String? = null): PendingIntent {
    val intent = if (threadId != null && threadId > 0)
      Intent(context, SmsThreadActivity::class.java)
        .putExtra("threadId", threadId).putExtra("address", address)
    else Intent(context, WorldInboxActivity::class.java)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(context, threadId?.toInt() ?: 0, intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private fun displayName(context: Context, address: String): String {
    if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
        PackageManager.PERMISSION_GRANTED) return address
    return try {
      val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
        Uri.encode(address))
      context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
        null, null, null)?.use { cursor ->
          if (cursor.moveToFirst()) cursor.getString(0) else null
      }?.takeIf { it.isNotBlank() } ?: address
    } catch (_: Exception) { address }
  }

  // One stable notification per sender; never include the phone number in the intent URI.
  private fun senderId(address: String): Int =
    address.hashCode() xor 0x5A170000

  private fun replyAction(context: Context, address: String, notificationId: Int): Notification.Action {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(address.toByteArray(Charsets.UTF_8))
      .take(12).joinToString("") { "%02x".format(it) }
    val intent = Intent(context, SmsNotificationReplyReceiver::class.java)
      .setAction(SmsNotificationReplyReceiver.ACTION_REPLY)
      .setData(Uri.parse("living-world-reply://sender/$digest"))
      .putExtra(SmsNotificationReplyReceiver.EXTRA_ADDRESS, address)
      .putExtra(SmsNotificationReplyReceiver.EXTRA_NOTIFICATION_ID, notificationId)
    // Direct reply must be MUTABLE so Android can attach RemoteInput clip data.
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    val replyIntent = PendingIntent.getBroadcast(context, notificationId, intent, flags)
    val remoteInput = RemoteInput.Builder(SmsNotificationReplyReceiver.KEY_TEXT_REPLY)
      .setLabel("Type a reply")
      .build()
    return Notification.Action.Builder(R.drawable.ic_notification_message, "Reply", replyIntent)
      .addRemoteInput(remoteInput)
      .setAllowGeneratedReplies(true)
      .build()
  }

  private fun genericPublicVersion(context: Context): Notification =
    Notification.Builder(context, INCOMING)
      .setSmallIcon(R.drawable.ic_notification_message)
      .setContentTitle("New message")
      .setContentText("Unlock to view")
      .build()

  fun incoming(context: Context, address: String, body: String, threadId: Long?) {
    if (!SmsRole.isDefault(context) || !canPost(context)) return
    ensureChannel(context, INCOMING, "Incoming text and picture messages",
      NotificationManager.IMPORTANCE_HIGH)
    val id = threadId?.takeIf { it > 0 }?.toInt() ?: senderId(address)
    val name = displayName(context, address)
    val sender = Person.Builder().setName(name).build()
    val me = Person.Builder().setName("You").build()
    val style = Notification.MessagingStyle(me)
      .addMessage(body.take(2000), System.currentTimeMillis(), sender)
    val builder = Notification.Builder(context, INCOMING)
      .setSmallIcon(R.drawable.ic_notification_message)
      .setContentTitle(name)
      .setContentText(body.take(180))
      .setStyle(style)
      .setCategory(Notification.CATEGORY_MESSAGE)
      .setVisibility(Notification.VISIBILITY_PRIVATE)
      .setPublicVersion(genericPublicVersion(context))
      .setContentIntent(openMessages(context, threadId, address))
      .setAutoCancel(true)
    // Only SMS-compatible numeric senders can receive an SMS quick reply.
    if (Regex("^\\+?[0-9() .-]{3,24}$").matches(address))
      builder.addAction(replyAction(context, address, id))
    try { manager(context)?.notify(INCOMING_TAG, id, builder.build()) }
    catch (_: SecurityException) { /* Notification permission revoked */ }
  }

  /** Replace Android's "replying" spinner without claiming SMS delivery. */
  fun replyAcknowledgment(context: Context, id: Int, message: String, failed: Boolean) {
    if (!canPost(context)) return
    ensureChannel(context, INCOMING, "Incoming text and picture messages",
      NotificationManager.IMPORTANCE_HIGH)
    val notification = Notification.Builder(context, INCOMING)
      .setSmallIcon(R.drawable.ic_notification_message)
      .setContentTitle(if (failed) "Reply not sent" else "Reply submitted")
      .setContentText(message.take(180))
      .setContentIntent(openMessages(context))
      .setCategory(Notification.CATEGORY_MESSAGE)
      .setVisibility(Notification.VISIBILITY_PRIVATE)
      .setAutoCancel(true)
      .build()
    try { manager(context)?.notify(INCOMING_TAG, id, notification) }
    catch (_: SecurityException) { }
  }

  /** A submission acknowledgement is not a carrier sent or delivered confirmation. */
  fun submitted(context: Context, transport: String, id: Int) {
    if (!SmsRole.isDefault(context) || !canPost(context)) return
    ensureChannel(context, RESULTS, "Carrier send results", NotificationManager.IMPORTANCE_DEFAULT)
    val notification = Notification.Builder(context, RESULTS)
      .setSmallIcon(R.drawable.ic_notification_message)
      .setContentTitle("$transport submitted to Android")
      .setContentText("Carrier confirmation pending. This does not mean delivered.")
      .setContentIntent(openMessages(context))
      .setAutoCancel(true)
      .build()
    try { manager(context)?.notify(id, notification) }
    catch (_: SecurityException) { }
  }

  /** "Sent" is carrier submission success, not confirmation of recipient delivery. */
  fun outgoing(context: Context, transport: String, success: Boolean, id: Int) {
    if (!SmsRole.isDefault(context) || !canPost(context)) return
    ensureChannel(context, RESULTS, "Carrier send results", NotificationManager.IMPORTANCE_DEFAULT)
    val notification = Notification.Builder(context, RESULTS)
      .setSmallIcon(R.drawable.ic_notification_message)
      .setContentTitle(if (success) "$transport sent to carrier" else "$transport send failed")
      .setContentText(if (success) "Carrier reported send success; delivery is not guaranteed."
        else "Open Living World carrier test for failure details.")
      .setContentIntent(openMessages(context))
      .setAutoCancel(true)
      .build()
    try { manager(context)?.notify(id, notification) }
    catch (_: SecurityException) { }
  }

  fun preview(context: Context) {
    if (!canPost(context)) return
    ensureChannel(context, INCOMING, "Incoming text and picture messages",
      NotificationManager.IMPORTANCE_HIGH)
    val notification = Notification.Builder(context, INCOMING)
      .setSmallIcon(R.drawable.ic_notification_message)
      .setContentTitle("Living World notification test")
      .setContentText("Notifications are working on this phone.")
      .setContentIntent(openMessages(context))
      .setAutoCancel(true)
      .build()
    manager(context)?.notify(0x5055, notification)
  }
}
