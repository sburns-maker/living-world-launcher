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
import java.util.HashMap

/** Processes results of platform-managed carrier MMS downloads. */
class MmsDownloadResultReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_RESULT || !SmsRole.isDefault(context)) return
    val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
    if (!TOKEN.matches(token)) return
    val subscription = intent.getIntExtra(EXTRA_SUBSCRIPTION, -1)
    val location = intent.getStringExtra(EXTRA_LOCATION) ?: return
    val staged = File(File(context.noBackupFilesDir, "mms_pending"), "$token.pdu")
    var persisted = false
    try {
      if (resultCode != Activity.RESULT_OK) {
        Log.w(TAG, "MMS carrier download failed, code=$resultCode")
        return
      }
      if (!staged.isFile || staged.length() !in 1..MAX_BYTES) {
        Log.w(TAG, "MMS payload missing or exceeds limit")
        return
      }
      val payload = staged.readBytes()
      val decoded = PduParser(payload).parse() as? RetrieveConf ?: return
      // The upstream persister owns address, body and attachment writes to
      // Telephony.Mms. A failed persist does not count as message delivery.
      val result: Uri = PduPersister.getPduPersister(context).persist(
        decoded, Telephony.Mms.Inbox.CONTENT_URI, true, true, HashMap(), subscription
      )
      persisted = true
      Log.i(TAG, "MMS stored in system provider")
    } catch (e: Exception) {
      Log.e(TAG, "Unable to persist downloaded MMS", e)
    } finally {
      MmsDownloadLedger.settle(context, subscription, location, persisted)
      // Retain a bounded failed payload for explicit recovery instead of discarding
      // a successfully downloaded message after a transient provider error.
      if (persisted && staged.exists()) staged.delete()
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
  }
}
