package com.livingworld.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import java.security.MessageDigest

/** Persistent, bounded retry schedule for failed MMS downloads. */
object MmsRetryStore {
  private const val FILE = "mms_retry_queue"
  const val ACTION = "com.livingworld.launcher.RETRY_MMS"
  private val backoff = longArrayOf(30_000L, 120_000L, 600_000L)

  private fun id(subscription: Int, location: String) = MessageDigest.getInstance("SHA-256")
    .digest("$subscription:$location".toByteArray()).joinToString("") { "%02x".format(it) }

  @Synchronized fun completed(context: Context, subscription: Int, location: String) {
    context.getSharedPreferences(FILE, 0).edit().remove(id(subscription, location)).commit()
  }

  @Synchronized fun failure(context: Context, subscription: Int, location: String) {
    if (subscription < 0 || location.isBlank()) return
    val prefs = context.getSharedPreferences(FILE, 0)
    val key = id(subscription, location)
    val tries = prefs.getInt("$key.attempts", 0) + 1
    if (!prefs.edit().putInt("$key.attempts", tries)
        .putString("$key.url", location).putInt("$key.sub", subscription).commit()) return
    if (tries > backoff.size) return
    val intent = Intent(context, MmsRetryReceiver::class.java)
      .setAction(ACTION).setData(Uri.parse("mms-retry://pending/$key"))
    val token = PendingIntent.getBroadcast(context, 0, intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
      AlarmManager.ELAPSED_REALTIME_WAKEUP,
      SystemClock.elapsedRealtime() + backoff[tries - 1], token)
  }

  fun retry(context: Context, key: String) {
    if (!Regex("[a-f0-9]{64}").matches(key) || !SmsRole.isDefault(context)) return
    val prefs = context.getSharedPreferences(FILE, 0)
    val url = prefs.getString("$key.url", null) ?: return
    val sub = prefs.getInt("$key.sub", -1)
    MmsDownloadLedger.settle(context, sub, url, false)
    if (!MmsCarrierDownload.enqueue(context, url, sub)) failure(context, sub, url)
  }
}

class MmsRetryReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != MmsRetryStore.ACTION) return
    val key = intent.data?.lastPathSegment ?: return
    MmsRetryStore.retry(context.applicationContext, key)
  }
}
