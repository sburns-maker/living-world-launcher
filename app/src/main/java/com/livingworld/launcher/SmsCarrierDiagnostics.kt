package com.livingworld.launcher

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager
import java.text.DateFormat
import java.util.Date

/** Local-only send diagnostics. Never reads message bodies or recipient numbers. */
object SmsCarrierDiagnostics {
  private fun errorLabel(result: Int?): String = when (result) {
    null -> "No failure code reported"
    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic modem/carrier failure"
    SmsManager.RESULT_ERROR_RADIO_OFF -> "Mobile radio disabled"
    SmsManager.RESULT_ERROR_NULL_PDU -> "Invalid message data"
    SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular service"
    SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "SMS send limit exceeded"
    SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "SIM fixed-dialing restriction"
    else -> "Android SMS result code " + result
  }

  private fun providerState(context: Context, id: Long): String = try {
    context.contentResolver.query(
      ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
      arrayOf(Telephony.Sms.TYPE), null, null, null
    )?.use { cursor ->
      if (!cursor.moveToFirst()) return@use "Message row not found"
      when (val type = cursor.getInt(0)) {
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "OUTBOX"
        Telephony.Sms.MESSAGE_TYPE_SENT -> "SENT"
        Telephony.Sms.MESSAGE_TYPE_FAILED -> "FAILED"
        Telephony.Sms.MESSAGE_TYPE_QUEUED -> "QUEUED"
        else -> "Other (" + type + ")"
      }
    } ?: "Unable to query SMS provider"
  } catch (_: Exception) { "SMS provider unavailable" }

  fun summary(context: Context): String {
    val attempts = SmsSendLedger.recent(context)
    if (attempts.isEmpty()) return "Recent carrier results: None recorded on this installation."
    return "Recent carrier results (no phone numbers or message text):\n" +
      attempts.joinToString("\n\n") { item ->
        val time = if (item.created > 0) DateFormat.getTimeInstance(DateFormat.SHORT)
          .format(Date(item.created)) else "Earlier attempt"
        val error = if (item.result != null) "\n" + errorLabel(item.result) else ""
        val modem = if (item.radioError != null && item.radioError >= 0)
          "\nModem error code: " + item.radioError else ""
        time + " · SMS #" + item.id + "\n" +
          "Carrier: " + item.phase +
          " | sent callbacks " + item.reported + "/" + item.parts +
          " | delivery receipts " + item.delivered + "/" + item.parts +
          "\nSIM: " + item.subscription +
          " | Android SMS row: " + providerState(context, item.id) + error + modem
      }
  }
}
