package com.livingworld.launcher

import android.app.PendingIntent
import android.app.Activity
import android.content.ContentValues
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/**
 * SMS transport isolated from the UI.
 * Production send is feature-gated until MMS, receive, provider persistence,
 * delivery callbacks and role verification pass on a real device.
 */
object SmsTransport {
  const val SEND_ENABLED = false

  sealed class Validation {
    data class Ready(val to: String, val text: String) : Validation()
    data class Blocked(val reason: String) : Validation()
  }

  fun validate(context: Context, address: String, message: String): Validation {
    if (!SmsRole.isDefault(context)) return Validation.Blocked("Not the default SMS app")
    if (!SEND_ENABLED) return Validation.Blocked("SMS transport is in development")
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
    val manager = SmsManager.getDefault()
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
    // TODO: attach per-part SENT and DELIVERED PendingIntent callbacks with durable
    // provider message IDs and mark failed/partial sends. No dispatch yet.
    return Validation.Blocked("Saved in outbox, carrier dispatch not enabled")
  }
}
