package com.livingworld.launcher

import android.content.Context
import java.security.MessageDigest

/**
 * Persistent attempt tracker for incoming carrier notifications.
 * An MMS is only marked complete after system-provider persistence succeeds.
 * Failed attempts remain eligible for a subsequent notification/retry.
 */
object MmsDownloadLedger {
  private const val PREFS = "incoming_mms_attempts"
  private const val PENDING = "pending"
  private const val COMPLETE = "complete"
  private const val STALE_MS = 15 * 60 * 1000L
  private const val COMPLETE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

  private fun key(subscription: Int, location: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
      .digest("$subscription:$location".toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }

  @Synchronized fun isComplete(context: Context, subscription: Int, location: String): Boolean {
    val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val key = key(subscription, location)
    return preferences.getString(key, null) == COMPLETE
  }

  @Synchronized fun begin(context: Context, subscription: Int, location: String): Boolean {
    val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val key = key(subscription, location)
    val status = preferences.getString(key, null)
    val timestamp = preferences.getLong("time_$key", 0L)
    if (status == COMPLETE && System.currentTimeMillis() - timestamp < COMPLETE_RETENTION_MS) return false
    if (status == PENDING && System.currentTimeMillis() - timestamp < STALE_MS) return false
    return preferences.edit().putString(key, PENDING)
      .putLong("time_$key", System.currentTimeMillis()).commit()
  }

  @Synchronized fun settle(context: Context, subscription: Int, location: String, success: Boolean) {
    val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val key = key(subscription, location)
    if (success) preferences.edit().putString(key, COMPLETE)
      .putLong("time_$key", System.currentTimeMillis()).commit()
    else preferences.edit().remove(key).remove("time_$key").commit()
  }
}
