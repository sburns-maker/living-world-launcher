package com.livingworld.launcher

import android.content.Context
import android.provider.Telephony

/** Read-only thread history from the system SMS provider. No duplicate local database. */
data class SmsMessageRow(val id: Long, val outgoing: Boolean, val body: String, val timestamp: Long)
object SmsThreads {
  /** Sent, pending, failed, queued and drafts all belong on the sender's side. */
  internal fun isOutgoing(messageType: Int): Boolean = when (messageType) {
    Telephony.Sms.MESSAGE_TYPE_SENT,
    Telephony.Sms.MESSAGE_TYPE_OUTBOX,
    Telephony.Sms.MESSAGE_TYPE_FAILED,
    Telephony.Sms.MESSAGE_TYPE_QUEUED,
    Telephony.Sms.MESSAGE_TYPE_DRAFT -> true
    else -> false
  }

  fun messages(context: Context, threadId: Long, limit: Int = 120): List<SmsMessageRow> {
    if (!SmsRole.isDefault(context) || threadId < 0) return emptyList()
    val result = ArrayList<SmsMessageRow>()
    val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
    try {
      context.contentResolver.query(
        Telephony.Sms.CONTENT_URI, projection,
        "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()),
        "${Telephony.Sms.DATE} DESC"
      )?.use { cursor ->
        val id = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
        val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
        val type = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
        while (cursor.moveToNext() && result.size < limit) {
          result.add(SmsMessageRow(cursor.getLong(id),
            isOutgoing(cursor.getInt(type)),
            cursor.getString(body) ?: "", cursor.getLong(date)))
        }
      }
    } catch (_: SecurityException) { return emptyList() }
    return result.asReversed()
  }
}
