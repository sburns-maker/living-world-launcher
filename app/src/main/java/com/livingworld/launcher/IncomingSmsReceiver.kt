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
    // A single broadcast normally contains all parts. Never combine unrelated senders.
    val address = messages.first().displayOriginatingAddress ?: return
    if (messages.any { it.displayOriginatingAddress != address }) {
      Log.w("IncomingSmsReceiver", "Discarding inconsistent sender set")
      return
    }
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
      // SMS_DELIVER can be redelivered after process restart. Avoid identical provider
      // rows for the same sender, timestamp and body; do not conflate distinct texts
      // merely because their contents are equal.
      if (alreadyPersisted(context, address, timestamp, body)) return
      val inserted = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
      if (inserted != null) SmsNotification.incoming(context, address, body, null)
      // TODO: notify, deduplicate repeated deliveries, and reconcile carrier multipart status.
    } catch (e: Exception) {
      Log.e("IncomingSmsReceiver", "Unable to persist incoming SMS", e)
    }
  }

  private fun alreadyPersisted(context: Context, sender: String, timestamp: Long, body: String): Boolean {
    context.contentResolver.query(
      Telephony.Sms.Inbox.CONTENT_URI,
      arrayOf(Telephony.Sms._ID),
      "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.DATE} = ? AND ${Telephony.Sms.BODY} = ?",
      arrayOf(sender, timestamp.toString(), body),
      null
    )?.use { return it.moveToFirst() }
    // A failed query must not be interpreted as proof this is a new SMS.
    throw IllegalStateException("Unable to query SMS inbox for duplicates")
  }
}
