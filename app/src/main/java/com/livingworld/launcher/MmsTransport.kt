package com.livingworld.launcher

import android.content.Context
import android.graphics.Bitmap
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction

/**
 * Fossify mmslib-backed MMS transport.
 * Send remains disabled until the MMS delivery/download receivers, provider persistence,
 * SMS role qualification and carrier tests are complete.
 */
object MmsTransport {
  const val SEND_ENABLED = true

  fun sendPhoto(context: Context, address: String, caption: String, bitmap: Bitmap): Result<Unit> =
    sendGroup(context, listOf(address), caption, bitmap)

  fun sendGroup(context: Context, recipients: List<String>, caption: String, bitmap: Bitmap? = null): Result<Unit> {
    if (!SmsRole.isDefault(context)) return Result.failure(IllegalStateException("Not default SMS handler"))
    if (!SEND_ENABLED || !CarrierTestMode.enabled(context)) return Result.failure(IllegalStateException("Enable a Carrier Test session first"))
    if (recipients.isEmpty() || recipients.size > 10 || recipients.any { !Regex("^\\+?[0-9() .-]{3,24}$").matches(it) } || bitmap?.isRecycled == true)
      return Result.failure(IllegalArgumentException("Invalid MMS recipients or image"))
    return runCatching {
      val settings = Settings().apply {
        setUseSystemSending(true)
        setGroup(true)
        setSubscriptionId(android.telephony.SmsManager.getDefaultSmsSubscriptionId())
      }
      val message = Message(caption, recipients.toTypedArray()).apply { if (bitmap != null) setImage(bitmap) }
      Transaction(context, settings).sendNewMessage(message)
    }
  }
}
