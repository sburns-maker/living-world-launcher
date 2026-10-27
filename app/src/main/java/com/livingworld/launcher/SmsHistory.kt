package com.livingworld.launcher

import android.app.role.RoleManager
import android.content.Context
import android.provider.Telephony

/**
 * No SMS provider access unless the system has selected this app as its SMS handler.
 * This milestone deliberately does not request the SMS role: MMS and delivery handling
 * must be implemented and tested before replacing a working daily-driver SMS app.
 */
object SmsRole {
  fun isDefault(context: Context): Boolean {
    val manager = context.getSystemService(RoleManager::class.java)
    return manager?.isRoleHeld(RoleManager.ROLE_SMS) == true &&
      Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
  }
}

data class SmsSummary(val threadId: Long, val address: String, val body: String, val timestamp: Long, val unread: Boolean)

object SmsHistory {
  fun recent(context: Context, limit: Int = 80): List<SmsSummary> {
    if (!SmsRole.isDefault(context)) return emptyList()
    val results = LinkedHashMap<Long, SmsSummary>()
    val columns = arrayOf(
      Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
      Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ)
    try {
      context.contentResolver.query(
        Telephony.Sms.CONTENT_URI, columns, null, null,
        "${Telephony.Sms.DATE} DESC"
      )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
        val addressCol = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val bodyCol = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val dateCol = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
        val readCol = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
        while (cursor.moveToNext() && results.size < limit) {
          val id = cursor.getLong(idCol)
          if (results.containsKey(id)) continue
          results[id] = SmsSummary(id, cursor.getString(addressCol) ?: "",
            cursor.getString(bodyCol) ?: "", cursor.getLong(dateCol),
            cursor.getInt(readCol) == 0)
        }
      }
    } catch (_: SecurityException) { return emptyList() }
    return results.values.toList()
  }
}
