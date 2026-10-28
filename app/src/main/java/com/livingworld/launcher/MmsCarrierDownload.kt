package com.livingworld.launcher

import android.app.PendingIntent
import android.content.Intent
import java.util.UUID
import android.content.Context
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log

/**
 * Carrier MMS downloader using Android's subscription-specific transport.
 * No T-Mobile APN or MMSC URL is hardcoded: Android supplies carrier routing.
 *
 * Disabled until a private writable content provider, result receiver,
 * PDU persistence, deduplication and device tests are completed.
 */
object MmsCarrierDownload {
  const val DOWNLOAD_ENABLED = true
  private const val TAG = "MmsCarrierDownload"

  fun enqueue(context: Context, contentLocation: String, subscriptionId: Int): Boolean {
    if (!DOWNLOAD_ENABLED || !SmsRole.isDefault(context)) return false
    if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID || subscriptionId < 0)
      return false
    if (!contentLocation.startsWith("http://", true) &&
        !contentLocation.startsWith("https://", true)) return false
    if (!MmsDownloadLedger.begin(context, subscriptionId, contentLocation)) return false
    val token = UUID.randomUUID().toString()
    val destination = MmsDownloadProvider.destination(context, token)
    val callback = Intent(context, MmsDownloadResultReceiver::class.java)
      .setAction(MmsDownloadResultReceiver.ACTION_RESULT)
      .setData(destination)
      .putExtra(MmsDownloadResultReceiver.EXTRA_TOKEN, token)
      .putExtra(MmsDownloadResultReceiver.EXTRA_SUBSCRIPTION, subscriptionId)
      .putExtra(MmsDownloadResultReceiver.EXTRA_LOCATION, contentLocation)
      .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val completion = PendingIntent.getBroadcast(
      context, 0, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    return try {
      // Limited grants to the two common Android carrier MMS service packages.
      for (servicePackage in listOf("com.android.phone", "com.android.mms.service")) {
        try {
          context.grantUriPermission(servicePackage, destination,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
      }
      downloadTo(context, contentLocation, subscriptionId, destination, completion)
      true
    } catch (error: Exception) {
      MmsDownloadLedger.settle(context, subscriptionId, contentLocation, false)
      Log.e(TAG, "Unable to request carrier MMS download", error)
      false
    }
  }

  /**
   * Android-managed carrier transport. Call only after a private writable
   * content Uri and a unique, immutable result PendingIntent are prepared.
   * The downloaded byte stream still needs verified PDU parsing and durable
   * Telephony.Mms persistence before it appears in the inbox.
   */
  internal fun downloadTo(
    context: Context,
    contentLocation: String,
    subscriptionId: Int,
    destination: Uri,
    completion: PendingIntent
  ) {
    require(DOWNLOAD_ENABLED) { "Carrier MMS download disabled" }
    require(SmsRole.isDefault(context)) { "Not default SMS app" }
    require(destination.scheme == "content") { "MMS destination must be content Uri" }
    require(contentLocation.startsWith("http://", true) ||
      contentLocation.startsWith("https://", true)) { "Unexpected MMS location scheme" }
    val base = context.getSystemService(SmsManager::class.java)
      ?: error("Android SMS manager unavailable")
    val manager = base.createForSubscriptionId(subscriptionId)
    manager.downloadMultimediaMessage(context, contentLocation, destination, null, completion)
  }
}
