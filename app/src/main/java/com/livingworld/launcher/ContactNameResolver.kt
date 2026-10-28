package com.livingworld.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils

/**
 * One bounded Contacts provider query for the whole inbox, rather than
 * querying Contacts separately for every SMS row on the UI thread.
 * No contact data is uploaded or persisted by this resolver.
 */
object ContactNameResolver {
  private fun key(number: String): String? {
    val normalized = PhoneNumberUtils.normalizeNumber(number)
    val digits = normalized.filter { it.isDigit() }
    if (digits.length == 11 && digits.startsWith("1")) return digits.drop(1)
    if (digits.length == 10) return digits
    // Short codes and international numbers do not use last-10 matching.
    return normalized.takeIf { it.length >= 5 }
  }

  fun snapshot(context: Context): Map<String, String> {
    if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
      PackageManager.PERMISSION_GRANTED) return emptyMap()
    val result = LinkedHashMap<String, String>()
    try {
      context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
          ContactsContract.CommonDataKinds.Phone.NUMBER,
          ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        ),
        null, null, null
      )?.use { cursor ->
        val number = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val name = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        var rows = 0
        while (cursor.moveToNext() && rows++ < 10000) {
          val label = cursor.getString(name)?.trim().orEmpty()
          val phone = cursor.getString(number) ?: continue
          val match = key(phone) ?: continue
          if (label.isNotBlank()) result.putIfAbsent(match, label)
        }
      }
    } catch (_: Exception) {
      // Contacts access is optional; raw numbers remain readable.
    }
    return result
  }

  fun display(address: String, names: Map<String, String>): String {
    if (address.isBlank()) return "Unknown number"
    // MMS group conversations may list several phone numbers.
    if (',' in address || ';' in address) {
      return address.split(',', ';').map { raw ->
        key(raw.trim())?.let { names[it] } ?: raw.trim()
      }.joinToString(", ")
    }
    return key(address)?.let(names::get) ?: address
  }
}
