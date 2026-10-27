package com.livingworld.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

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
      is MmsWapEnvelope.Valid ->
        Log.i(TAG, "MMS WAP envelope accepted; carrier download adapter not enabled")
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
