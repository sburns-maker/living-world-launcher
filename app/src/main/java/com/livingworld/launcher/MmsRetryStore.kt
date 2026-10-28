package com.livingworld.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Bounded retries; successful downloads are persisted locally before fetching again. */
object MmsRetryStore {
  private const val FILE = "mms_retry_queue"
  const val ACTION = "com.livingworld.launcher.RETRY_MMS"
  private val backoff = longArrayOf(60_000L, 180_000L, 600_000L)
  private const val MAX_AGE = 48L * 60 * 60 * 1000

  private fun id(subscription: Int, location: String) = MessageDigest.getInstance("SHA-256")
    .digest("$subscription:$location".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

  @Synchronized fun completed(context: Context, subscription: Int, location: String) {
    val key = id(subscription, location)
    context.getSharedPreferences(FILE, 0).edit()
      .remove("$key.attempts").remove("$key.url").remove("$key.sub")
      .remove("$key.token").remove("$key.at").commit()
  }

  @Synchronized fun failure(context: Context, subscription: Int, location: String, token: String = "") {
    if (subscription < 0 || location.isBlank()) return
    val prefs = context.getSharedPreferences(FILE, 0)
    val key = id(subscription, location)
    val tries = prefs.getInt("$key.attempts", 0) + 1
    val editor = prefs.edit().putInt("$key.attempts", tries)
      .putString("$key.url", location).putInt("$key.sub", subscription)
      .putLong("$key.at", System.currentTimeMillis())
    if (token.isNotBlank()) editor.putString("$key.token", token)
    if (!editor.commit()) return
    if (tries <= backoff.size) schedule(context, key, backoff[tries - 1])
    else Log.w("MmsRetryStore", "MMS automatic retries exhausted; staged payload retained")
  }

  private fun schedule(context: Context, key: String, delay: Long) {
    val intent = Intent(context, MmsRetryReceiver::class.java)
      .setAction(ACTION).setData(Uri.parse("mms-retry://pending/$key"))
    val pending = PendingIntent.getBroadcast(context, 0, intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
      AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pending)
  }

  fun retry(context: Context, key: String) {
    if (!Regex("[0-9a-f]{64}").matches(key) || !SmsRole.isDefault(context)) return
    val prefs = context.getSharedPreferences(FILE, 0)
    val location = prefs.getString("$key.url", null) ?: return
    val subscription = prefs.getInt("$key.sub", -1)
    val token = prefs.getString("$key.token", "") ?: ""
    if (subscription < 0) return
    if (token.isNotBlank() && MmsDownloadResultReceiver.persistStaged(
        context, token, subscription, location)) {
      completed(context, subscription, location)
      return
    }
    if (prefs.getInt("$key.attempts", 0) > backoff.size) return
    MmsDownloadLedger.settle(context, subscription, location, false)
    if (!MmsCarrierDownload.enqueue(context, location, subscription))
      failure(context, subscription, location, token)
  }

  fun restore(context: Context) {
    val prefs = context.getSharedPreferences(FILE, 0)
    val now = System.currentTimeMillis()
    prefs.all.keys.filter { it.endsWith(".attempts") }.take(100).forEach { entry ->
      val key = entry.removeSuffix(".attempts")
      val last = prefs.getLong("$key.at", 0L)
      if (now - last > MAX_AGE) {
        prefs.edit().remove("$key.attempts").remove("$key.url")
          .remove("$key.sub").remove("$key.token").remove("$key.at").apply()
      } else if (prefs.getInt(entry, 0) <= backoff.size) schedule(context, key, 60_000)
    }
    // Staging remains in app-private storage for manual review if retries exhausted.
  }

  fun pendingCount(context: Context): Int =
    context.getSharedPreferences(FILE, 0).all.keys.count { it.endsWith(".attempts") }
}

class MmsRetryReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != MmsRetryStore.ACTION) return
    val key = intent.data?.lastPathSegment ?: return
    val async = goAsync()
    executor.execute {
      try { MmsRetryStore.retry(context.applicationContext, key) }
      catch (error: Exception) { Log.e("MmsRetryReceiver", "MMS retry failed", error) }
      finally { async.finish() }
    }
  }
  companion object { private val executor = Executors.newSingleThreadExecutor() }
}

class MmsBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED)
      MmsRetryStore.restore(context.applicationContext)
  }
}
