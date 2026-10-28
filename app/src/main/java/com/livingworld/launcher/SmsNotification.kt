package com.livingworld.launcher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Inbound texts and carrier send outcomes use separate user-controllable channels. */
object SmsNotification {
  private const val INCOMING = "incoming_sms"
  private const val RESULTS = "carrier_send_results"
  private const val TAG = "SmsNotification"

  private fun manager(context: Context): NotificationManager? =
    context.getSystemService(NotificationManager::class.java)

  fun permissionStatus(context: Context): String {
    val service = manager(context) ?: return "notification manager unavailable"
    if (Build.VERSION.SDK_INT >= 33 &&
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
      return "permission not granted"
    if (!service.areNotificationsEnabled()) return "notifications disabled in Samsung settings"
    val incomingChannel = service.getNotificationChannel(INCOMING)
    if (incomingChannel?.importance == NotificationManager.IMPORTANCE_NONE)
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

  private fun ensureChannel(context: Context, id: String, name: String, priority: Int) {
    manager(context)?.createNotificationChannel(NotificationChannel(id, name, priority))
  }

  private fun openMessages(context: Context): PendingIntent {
    val activity = Intent(context, WorldInboxActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(context, 0, activity,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  fun incoming(context: Context, address: String, body: String, threadId: Long?) {
    if (!SmsRole.isDefault(context) || !canPost(context)) return
    ensureChannel(context, INCOMING, "Incoming text and picture messages",
      NotificationManager.IMPORTANCE_HIGH)
    val message = Notification.Builder(context, INCOMING)
      .setSmallIcon(android.R.drawable.ic_dialog_email)
      .setContentTitle(address)
      .setContentText(body.take(160))
      .setStyle(Notification.BigTextStyle().bigText(body.take(800)))
      .setContentIntent(openMessages(context))
      .setAutoCancel(true)
      .build()
    try { manager(context)?.notify((threadId ?: System.currentTimeMillis()).toInt(), message) }
    catch (_: SecurityException) { /* Samsung permission may have been revoked */ }
  }

  /** A submission acknowledgement is not a carrier sent or delivered confirmation. */
  fun submitted(context: Context, transport: String, id: Int) {
    if (!SmsRole.isDefault(context) || !canPost(context)) return
    ensureChannel(context, RESULTS, "Carrier send results",
      NotificationManager.IMPORTANCE_DEFAULT)
    val notification = Notification.Builder(context, RESULTS)
      .setSmallIcon(android.R.drawable.ic_dialog_email)
      .setContentTitle("$transport submitted to Android")
      .setContentText("Carrier confirmation pending. This does not mean delivered.")
      .setContentIntent(openMessages(context))
      .setAutoCancel(true)
      .build()
    try { manager(context)?.notify(id, notification) }
    catch (_: SecurityException) { }
  }

  /** "Sent" here is a send confirmation, NOT proof of delivery to recipient. */
  fun outgoing(context: Context, transport: String, success: Boolean, id: Int) {
    if (!SmsRole.isDefault(context) || !canPost(context)) return
    ensureChannel(context, RESULTS, "Carrier send results",
      NotificationManager.IMPORTANCE_DEFAULT)
    val notification = Notification.Builder(context, RESULTS)
      .setSmallIcon(android.R.drawable.ic_dialog_email)
      .setContentTitle(if (success) "$transport sent to carrier" else "$transport send failed")
      .setContentText(if (success) "Carrier reported send success; delivery is not guaranteed."
        else "Open Living World carrier test for the failure details.")
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
      .setSmallIcon(android.R.drawable.ic_dialog_email)
      .setContentTitle("Living World notification test")
      .setContentText("Notifications are working on this phone.")
      .setContentIntent(openMessages(context))
      .setAutoCancel(true)
      .build()
    manager(context)?.notify(0x5055, notification)
  }
}
