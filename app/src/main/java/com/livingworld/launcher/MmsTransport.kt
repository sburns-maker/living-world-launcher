package com.livingworld.launcher

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
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
    // Fossify's SendReq.prepareFromAddress uses getActiveSubscriptionInfo,
    // which requires READ_PHONE_STATE on Samsung / Android.
    if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
      return Result.failure(SecurityException("MMS requires Phone permission. Open Carrier test setup and grant MMS access."))
    val subscription = SubscriptionManager.getDefaultSmsSubscriptionId()
    if (subscription == SubscriptionManager.INVALID_SUBSCRIPTION_ID)
      return Result.failure(IllegalStateException("Choose a default SMS SIM before sending MMS"))
    if (recipients.isEmpty() || recipients.size > 10 || recipients.any { !Regex("^\\+?[0-9() .-]{3,24}$").matches(it) } || bitmap?.isRecycled == true)
      return Result.failure(IllegalArgumentException("Invalid MMS recipients or image"))
    return runCatching {
      val token = MmsSendLedger.start(context)
      try {
        val settings = Settings().apply {
          setUseSystemSending(true)
          setGroup(true)
          setSubscriptionId(subscription)
        }
        val message = Message(caption, recipients.toTypedArray()).apply {
          if (bitmap != null) setImage(bitmap)
        }
        val callback = Intent(context, MmsSentResultReceiver::class.java)
          .setAction(MmsSentResultReceiver.ACTION_SENT)
          .putExtra(MmsSentResultReceiver.EXTRA_TOKEN, token)
        Transaction(context, settings)
          .setExplicitBroadcastForSentMms(callback)
          .sendNewMessage(message)
        MmsSendLedger.update(context, token, "SUBMITTED_TO_ANDROID")
        SmsNotification.submitted(context, "MMS", token.hashCode())
      } catch (error: Exception) {
        MmsSendLedger.update(context, token, "REQUEST_FAILED")
        throw error
      }
    }
  }
}
