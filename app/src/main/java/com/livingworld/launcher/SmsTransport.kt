package com.livingworld.launcher

import android.app.PendingIntent
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
   * Never called by a shipped UI while SEND_ENABLED=false.
   * Multipart APIs invoke sent callbacks per segment; the provider must persist
   * successful outgoing messages and handle partial failures before enabling.
   */
  fun send(context: Context, address: String, body: String): Validation {
    val verdict = validate(context, address, body)
    if (verdict !is Validation.Ready) return verdict
    // Intentionally no SmsManager dispatch yet: validation alone is not a working SMS engine.
    return Validation.Blocked("Delivery/persistence pipeline not enabled")
  }
}
