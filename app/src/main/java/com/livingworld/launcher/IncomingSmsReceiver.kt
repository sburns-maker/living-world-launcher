package com.livingworld.launcher

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Android sends SMS_DELIVER only to the default SMS app.
 * Persist the received PDU into Android's shared SMS provider before refreshing UI.
 * This receiver is not registered/enabled until MMS and role migration are ready.
 */
class IncomingSmsReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION || !SmsRole.isDefault(context)) return
    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
    if (messages.isEmpty()) return
    // Keep multipart PDUs together. Android usually delivers all segments in one intent.
    val address = messages.first().displayOriginatingAddress ?: return
    val body = messages.joinToString("") { it.messageBody ?: "" }
    val timestamp = messages.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
    if (body.isBlank()) return
    val values = ContentValues().apply {
      put(Telephony.Sms.ADDRESS, address)
      put(Telephony.Sms.BODY, body)
      put(Telephony.Sms.DATE, timestamp)
      put(Telephony.Sms.READ, 0)
      put(Telephony.Sms.SEEN, 0)
      put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
    }
    try {
      val inserted = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
      if (inserted != null) SmsNotification.incoming(context, address, body, null)
      // TODO: notify, deduplicate repeated deliveries, and reconcile carrier multipart status.
    } catch (e: SecurityException) {
      Log.e("IncomingSmsReceiver", "SMS provider rejected incoming message", e)
    }
  }
}
