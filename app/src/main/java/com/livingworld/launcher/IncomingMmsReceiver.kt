package com.livingworld.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.NotificationInd
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/**
 * Staging handler for MMS WAP_PUSH_DELIVER. NOT registered in the manifest.
 *
 * The default SMS app receives application/vnd.wap.mms-message carrier PDUs;
 * a working implementation must decode NotificationInd, obtain the correct
 * subscription's mobile data connection, download through the MMSC, and
 * persist parsed content/addresses/parts atomically in Telephony.Mms.
 *
 * This receiver deliberately never claims receipt or writes incomplete MMS
 * records. It is safe to call with synthetic intents in instrumentation tests.
 */
class IncomingMmsReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!SmsRole.isDefault(context)) return
    when (val envelope = MmsWapEnvelope.from(intent)) {
      is MmsWapEnvelope.Valid -> {
        val notification = try {
          PduParser(envelope.pdu).parse() as? NotificationInd
        } catch (e: Exception) {
          Log.w(TAG, "Unable to decode MMS notification", e)
          null
        }
        if (notification == null) return
        val location = notification.contentLocation?.toString(Charsets.ISO_8859_1)
        if (location.isNullOrBlank()) return
        val subscription = envelope.subscriptionId
          ?: SmsManager.getDefaultSmsSubscriptionId().takeIf {
            it != SubscriptionManager.INVALID_SUBSCRIPTION_ID
          }
        if (subscription == null) {
          Log.w(TAG, "No valid SMS subscription for incoming MMS")
          return
        }
        MmsCarrierDownload.enqueue(context, location, subscription)
      }
      is MmsWapEnvelope.Invalid ->
        Log.w(TAG, "Discarded invalid MMS WAP envelope: ${envelope.reason}")
    }
  }

  companion object { private const val TAG = "IncomingMmsReceiver" }
}

/** Non-sensitive metadata only; raw MMS PDU is never logged or saved. */
sealed class MmsWapEnvelope {
  data class Valid(val pdu: ByteArray, val subscriptionId: Int?) : MmsWapEnvelope()
  data class Invalid(val reason: String) : MmsWapEnvelope()

  companion object {
    private const val MIME = "application/vnd.wap.mms-message"
    private const val MAX_PDU_BYTES = 512 * 1024
    private const val EXTRA_SUBSCRIPTION = "subscription"

    fun from(intent: Intent): MmsWapEnvelope {
      if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION)
        return Invalid("unexpected action")
      if (intent.type != MIME) return Invalid("unexpected MIME type")
      @Suppress("DEPRECATION")
      val data = intent.getByteArrayExtra("data") ?: return Invalid("missing PDU")
      if (data.isEmpty() || data.size > MAX_PDU_BYTES) return Invalid("invalid PDU size")
      val subId = if (intent.hasExtra(EXTRA_SUBSCRIPTION))
        intent.getIntExtra(EXTRA_SUBSCRIPTION, -1).takeIf { it >= 0 }
        else null
      return Valid(data.copyOf(), subId)
    }
  }
}
