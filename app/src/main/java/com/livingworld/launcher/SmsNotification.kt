package com.livingworld.launcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object SmsNotification {
  private const val CHANNEL = "incoming_sms"
  fun incoming(context: Context, address: String, body: String, threadId: Long?) {
    if (!SmsRole.isDefault(context)) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL, "Text messages",
      NotificationManager.IMPORTANCE_HIGH))
    val activity = Intent(context, WorldInboxActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val target = PendingIntent.getActivity(context, 0, activity,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = android.app.Notification.Builder(context, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_dialog_email)
      .setContentTitle(address)
      .setContentText(body.take(160))
      .setStyle(android.app.Notification.BigTextStyle().bigText(body.take(800)))
      .setContentIntent(target)
      .setAutoCancel(true)
      .build()
    manager.notify((threadId ?: System.currentTimeMillis()).toInt(), notification)
  }
}
