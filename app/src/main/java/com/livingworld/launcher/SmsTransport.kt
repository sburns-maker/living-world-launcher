package com.livingworld.launcher

import android.app.PendingIntent
import android.app.Activity
import android.content.ContentValues
import android.provider.Telephony
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager

/**
 * SMS transport isolated from the UI.
 * Production send is feature-gated until MMS, receive, provider persistence,
 * delivery callbacks and role verification pass on a real device.
 */
object SmsTransport {
  const val SEND_ENABLED = true

  sealed class Validation {
    data class Ready(val to: String, val text: String) : Validation()
    data class Blocked(val reason: String) : Validation()
  }

  fun validate(context: Context, address: String, message: String): Validation {
    if (!SmsRole.isDefault(context)) return Validation.Blocked("Not the default SMS app")
    if (!SEND_ENABLED || !CarrierTestMode.enabled(context)) return Validation.Blocked("Open Carrier Test and enable a test session first")
    val to = address.trim()
    if (!Regex("^\\+?[0-9() .-]{3,24}$").matches(to))
      return Validation.Blocked("Enter a valid phone number")
    if (message.isBlank()) return Validation.Blocked("Write a message")
    if (message.length > 10000) return Validation.Blocked("Message exceeds size limit")
    return Validation.Ready(to, message)
  }

  /**
   * Carrier-facing send implementation for later qualification testing.
   * Disabled by SEND_ENABLED until default-app role, callbacks and MMS pass testing.
   * Persist as an OUTBOX record first. Sent result handling must move to SENT/FAILED.
   */
  fun send(context: Context, address: String, body: String): Validation {
    val verdict = validate(context, address, body)
    if (verdict !is Validation.Ready) return verdict
    val subscription = android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId()
    if (subscription == android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID)
      return Validation.Blocked("Choose a default SMS SIM before sending")
    val manager = context.getSystemService(SmsManager::class.java)
      ?.createForSubscriptionId(subscription)
      ?: return Validation.Blocked("SMS service unavailable")
    val parts = manager.divideMessage(body)
    if (parts.isEmpty()) return Validation.Blocked("No message parts to send")
    val values = ContentValues().apply {
      put(Telephony.Sms.ADDRESS, address)
      put(Telephony.Sms.BODY, body)
      put(Telephony.Sms.DATE, System.currentTimeMillis())
      put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
      put(Telephony.Sms.READ, 1)
    }
    // No send without a durable provider record: avoid silent loss.
    val saved = try {
      context.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)
    } catch (_: Exception) { null }
    if (saved == null) return Validation.Blocked("Could not save outgoing message")
    val id = android.content.ContentUris.parseId(saved)
    if (!SmsSendLedger.start(context, id, parts.size)) {
      context.contentResolver.delete(saved, null, null)
      return Validation.Blocked("Could not initialize send tracking")
    }
    val sent = ArrayList<PendingIntent>()
    val delivered = ArrayList<PendingIntent>()
    for (i in parts.indices) {
      val extras = Intent(context, SmsSentResultReceiver::class.java)
        .setAction(SmsSentResultReceiver.ACTION_SENT)
        .setData(android.net.Uri.parse("sms-result://sent/$id/$i"))
        .putExtra(SmsSentResultReceiver.EXTRA_MESSAGE_ID, id)
        .putExtra(SmsSentResultReceiver.EXTRA_PART, i)
        .putExtra(SmsSentResultReceiver.EXTRA_TOTAL, parts.size)
      val receipt = Intent(context, SmsDeliveryResultReceiver::class.java)
        .setAction(SmsDeliveryResultReceiver.ACTION_DELIVERED)
        .setData(android.net.Uri.parse("sms-result://delivered/$id/$i"))
        .putExtra(SmsSentResultReceiver.EXTRA_MESSAGE_ID, id)
        .putExtra(SmsSentResultReceiver.EXTRA_PART, i)
        .putExtra(SmsSentResultReceiver.EXTRA_TOTAL, parts.size)
      val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      sent.add(PendingIntent.getBroadcast(context, 0, extras, flags))
      delivered.add(PendingIntent.getBroadcast(context, 0, receipt, flags))
    }
    try {
      if (parts.size == 1) manager.sendTextMessage(address, null, body, sent[0], delivered[0])
      else manager.sendMultipartTextMessage(address, null, parts, sent, delivered)
    } catch (e: Exception) {
      val failed = ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) }
      context.contentResolver.update(saved, failed, null, null)
      return Validation.Blocked("Carrier rejected send request")
    }
    return verdict
  }
}
