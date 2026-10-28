package com.livingworld.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Staging completion hook. Carrier processing remains disabled pending provider persistence. */
class MmsDownloadResultReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_RESULT) return
    Log.i("MmsDownloadResult", "Carrier result received; durable persistence not implemented")
  }
  companion object {
    const val ACTION_RESULT = "com.livingworld.launcher.MMS_DOWNLOAD_RESULT"
    const val EXTRA_TOKEN = "downloadToken"
  }
}
