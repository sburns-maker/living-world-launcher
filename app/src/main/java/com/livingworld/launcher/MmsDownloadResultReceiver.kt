package com.livingworld.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.RetrieveConf
import java.io.File
import java.io.InputStream
import java.util.HashMap
import java.util.concurrent.Executors

/** Background completion with recoverable staging and duplicate callback protection. */
class MmsDownloadResultReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_RESULT || !SmsRole.isDefault(context)) return
    val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
    val location = intent.getStringExtra(EXTRA_LOCATION) ?: return
    val subscription = intent.getIntExtra(EXTRA_SUBSCRIPTION, -1)
    if (!TOKEN.matches(token) || subscription < 0 || location.isBlank()) return
    val carrierSucceeded = resultCode == Activity.RESULT_OK
    val async = goAsync()
    executor.execute {
      try {
        val saved = carrierSucceeded && persistStaged(context.applicationContext, token, subscription, location)
        if (saved) {
          MmsRetryStore.completed(context, subscription, location)
        } else {
          MmsRetryStore.failure(context, subscription, location, token)
        }
      } catch (error: Exception) {
        Log.e(TAG, "MMS completion handler failed", error)
        MmsRetryStore.failure(context, subscription, location, token)
      } finally {
        async.finish()
      }
    }
  }

  companion object {
    const val ACTION_RESULT = "com.livingworld.launcher.MMS_DOWNLOAD_RESULT"
    const val EXTRA_TOKEN = "downloadToken"
    const val EXTRA_SUBSCRIPTION = "downloadSubscription"
    const val EXTRA_LOCATION = "downloadLocation"
    private const val TAG = "MmsDownloadResult"
    private const val MAX_BYTES = 5L * 1024 * 1024
    private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val executor = Executors.newSingleThreadExecutor()

    @Synchronized fun persistStaged(context: Context, token: String, subscription: Int, location: String): Boolean {
      if (!TOKEN.matches(token) || !SmsRole.isDefault(context)) return false
      if (MmsDownloadLedger.isComplete(context, subscription, location)) return true
      val file = File(File(context.noBackupFilesDir, "mms_pending"), "$token.pdu")
      if (!file.isFile || file.length() !in 1..MAX_BYTES) return false
      return try {
        val decoded = PduParser(file.readBytes()).parse() as? RetrieveConf ?: return false
        val body = decoded.body ?: return false
        if (body.partsNum !in 1..30) return false
        PduPersister.getPduPersister(context).persist(
          decoded, Telephony.Mms.Inbox.CONTENT_URI, true, true,
          HashMap<Uri, InputStream>(), subscription
        )
        MmsDownloadLedger.settle(context, subscription, location, true)
        MmsRetryStore.completed(context, subscription, location)
        if (!file.delete()) Log.w(TAG, "Could not clean persisted MMS staging file")
        SmsNotification.incoming(context, "Picture message", "New MMS received", null)
        true
      } catch (error: Exception) {
        Log.e(TAG, "Unable to persist MMS; retaining staged payload for retry", error)
        false
      }
    }
  }
}
