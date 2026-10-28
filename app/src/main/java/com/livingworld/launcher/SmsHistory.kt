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
  data class Status(
    val roleHeld: Boolean,
    val roleAvailable: Boolean,
    val telephonyDefault: String?,
    val ownPackage: String
  ) {
    val telephonyMatches: Boolean get() = telephonyDefault == ownPackage
    val isDefault: Boolean get() = roleHeld
  }

  /**
   * RoleManager is authoritative on Android 10+. Telephony's package lookup
   * is diagnostic only because OEM package visibility can return null even
   * when the role is held. Never infer the role from SMS runtime permissions.
   */
  fun inspect(context: Context): Status {
    val manager = context.getSystemService(RoleManager::class.java)
    val held = try { manager?.isRoleHeld(RoleManager.ROLE_SMS) == true }
      catch (_: Exception) { false }
    val available = try { manager?.isRoleAvailable(RoleManager.ROLE_SMS) == true }
      catch (_: Exception) { false }
    val smsPackage = try { Telephony.Sms.getDefaultSmsPackage(context) }
      catch (_: Exception) { null }
    return Status(held, available, smsPackage, context.packageName)
  }

  fun isDefault(context: Context): Boolean = inspect(context).isDefault
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
    // MMS-only and new group conversations must appear even without an SMS row.
    val mmsColumns = arrayOf(
      Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE,
      Telephony.Mms.READ, Telephony.Mms.MESSAGE_BOX
    )
    try {
      context.contentResolver.query(Telephony.Mms.CONTENT_URI, mmsColumns, null, null,
        "${Telephony.Mms.DATE} DESC")?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
        val threadCol = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
        val dateCol = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
        val readCol = cursor.getColumnIndexOrThrow(Telephony.Mms.READ)
        val boxCol = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
        var scanned = 0
        while (cursor.moveToNext() && scanned++ < 160) {
          val threadId = cursor.getLong(threadCol)
          if (threadId < 0) continue
          val timestamp = cursor.getLong(dateCol) * 1000L
          if ((results[threadId]?.timestamp ?: 0L) >= timestamp) continue
          val messageId = cursor.getLong(idCol)
          val incoming = cursor.getInt(boxCol) == Telephony.Mms.MESSAGE_BOX_INBOX
          results[threadId] = SmsSummary(
            threadId, mmsSender(context, messageId, incoming),
            "Picture or group message", timestamp, cursor.getInt(readCol) == 0
          )
        }
      }
    } catch (_: Exception) { /* Preserve SMS history when a carrier MMS query fails. */ }
    return results.values.sortedByDescending { it.timestamp }.take(limit)
  }

  private fun mmsSender(context: Context, messageId: Long, incoming: Boolean): String {
    val addresses = mutableListOf<String>()
    try {
      context.contentResolver.query(
        android.net.Uri.parse("content://mms/$messageId/addr"),
        arrayOf("address", "type"), null, null, null
      )?.use { cursor ->
        val addressColumn = cursor.getColumnIndexOrThrow("address")
        val typeColumn = cursor.getColumnIndexOrThrow("type")
        while (cursor.moveToNext()) {
          val kind = cursor.getInt(typeColumn)
          val address = cursor.getString(addressColumn) ?: continue
          if (address == "insert-address-token" || address.isBlank()) continue
          if ((incoming && kind == 137) || (!incoming && kind == 151))
            addresses.add(address)
        }
      }
    } catch (_: Exception) { }
    return addresses.take(3).joinToString(", ").ifBlank { "MMS conversation" }
  }
}
