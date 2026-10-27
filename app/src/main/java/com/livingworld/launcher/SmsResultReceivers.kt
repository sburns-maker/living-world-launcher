package com.livingworld.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log

/**
 * Receives per-segment sent results. Not registered in the manifest until the
 * app is qualified for the SMS role and persistent multi-part bookkeeping exists.
 *
 * Never mark an SMS sent on a single successful segment of a multipart send.
 */
class SmsSentResultReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_SENT || !SmsRole.isDefault(context)) return
    val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
    val part = intent.getIntExtra(EXTRA_PART, -1)
    val total = intent.getIntExtra(EXTRA_TOTAL, -1)
    if (messageId <= 0 || part < 0 || total <= 0 || part >= total) return
    val final = SmsSendLedger.recordSent(context, messageId, part, total, resultCode == Activity.RESULT_OK)
    Log.i(TAG, "SMS segment result id=$messageId segment=$part/$total result=$resultCode final=$final")
    // TODO: persist each segment result to durable pending-send state.
    // Only transition OUTBOX to SENT after ALL segments succeeded;
    // a failure must transition to FAILED and retain a retry path.
  }
  companion object {
    const val ACTION_SENT = "com.livingworld.launcher.SMS_SENT"
    const val EXTRA_MESSAGE_ID = "messageId"
    const val EXTRA_PART = "part"
    const val EXTRA_TOTAL = "total"
    private const val TAG = "SmsSentResult"
  }
}

class SmsDeliveryResultReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_DELIVERED || !SmsRole.isDefault(context)) return
    val id = intent.getLongExtra(SmsSentResultReceiver.EXTRA_MESSAGE_ID, -1L)
    if (id < 0) return
    val part = intent.getIntExtra(SmsSentResultReceiver.EXTRA_PART, -1)
    val total = intent.getIntExtra(SmsSentResultReceiver.EXTRA_TOTAL, -1)
    if (part !in 0 until total) return
    SmsSendLedger.recordDelivery(context, id, part, total)
    Log.i("SmsDeliveryResult", "Carrier delivery receipt for message $id segment=$part")
    // TODO: correlate all delivery receipts with persisted message/segment identifiers.
  }
  companion object {
    const val ACTION_DELIVERED = "com.livingworld.launcher.SMS_DELIVERED"
  }
}
