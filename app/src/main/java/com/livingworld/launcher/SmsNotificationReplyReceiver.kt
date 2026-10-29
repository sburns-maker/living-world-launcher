package com.livingworld.launcher

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

/**
 * Receives Android's inline notification reply. Never bypasses SMS role,
 * send permissions, recipient checks or the two-hour carrier test gate.
 */
class SmsNotificationReplyReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_REPLY) return
    val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return
    val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
    if (notificationId == -1) return
    val body = RemoteInput.getResultsFromIntent(intent)
      ?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim().orEmpty()
    val pending = goAsync()
    worker.execute {
      try {
        if (body.isBlank()) {
          SmsNotification.replyAcknowledgment(context, notificationId,
            "Nothing was sent. Enter a message to reply.", true)
        } else {
          when (val result = SmsTransport.send(context, address, body)) {
            is SmsTransport.Validation.Ready ->
              SmsNotification.replyAcknowledgment(context, notificationId,
                "Sent to Android. Carrier confirmation is still pending.", false)
            is SmsTransport.Validation.Blocked ->
              SmsNotification.replyAcknowledgment(context, notificationId,
                result.reason + ". Open Carrier test setup to check access.", true)
          }
        }
      } catch (error: Exception) {
        Log.e("SmsNotificationReply", "Unable to process inline SMS reply", error)
        SmsNotification.replyAcknowledgment(context, notificationId,
          "Reply was not sent. Open the carrier test screen for details.", true)
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    const val ACTION_REPLY = "com.livingworld.launcher.REPLY_TO_SMS_NOTIFICATION"
    const val EXTRA_ADDRESS = "replyAddress"
    const val EXTRA_NOTIFICATION_ID = "replyNotificationId"
    const val KEY_TEXT_REPLY = "notification_reply_text"
    private val worker = Executors.newSingleThreadExecutor()
  }
}
