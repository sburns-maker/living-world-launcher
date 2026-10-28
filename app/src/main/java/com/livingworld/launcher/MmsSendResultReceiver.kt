package com.livingworld.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.klinker.android.send_message.MmsSentReceiver
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Metadata-only, durable MMS send outcomes for carrier QA. */
object MmsSendLedger {
  private const val PREFS = "mms_send_results"
  private fun preferences(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun start(context: Context): String {
    val token = UUID.randomUUID().toString()
    val state = JSONObject().put("created", System.currentTimeMillis())
      .put("status", "SUBMITTING")
    check(preferences(context).edit().putString(token, state.toString()).commit()) {
      "Could not record MMS send attempt"
    }
    return token
  }

  @Synchronized fun update(context: Context, token: String, status: String,
                           resultCode: Int? = null, httpStatus: Int? = null) {
    if (!TOKEN.matches(token)) return
    val prefs = preferences(context)
    val state = JSONObject(prefs.getString(token, null) ?: return)
    state.put("status", status)
    if (resultCode != null) state.put("code", resultCode)
    if (httpStatus != null) state.put("http", httpStatus)
    prefs.edit().putString(token, state.toString()).commit()
  }

  fun recent(context: Context): String {
    val entries = preferences(context).all.mapNotNull { (key, raw) ->
      if (!TOKEN.matches(key) || raw !is String) return@mapNotNull null
      try {
        val json = JSONObject(raw)
        Pair(json.optLong("created", 0L), json)
      } catch (_: Exception) { null }
    }.sortedByDescending { it.first }.take(5)
    if (entries.isEmpty()) return "MMS carrier results: none recorded."
    return "MMS carrier results:\n" + entries.joinToString("\n") { (_, state) ->
      val result = if (state.has("code")) " | result=" + state.optInt("code") else ""
      val http = if (state.has("http")) " | HTTP=" + state.optInt("http") else ""
      state.optString("status") + result + http
    }
  }

  private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
}

/**
 * The Fossify library needs an explicit MMS callback; otherwise the platform
 * can finish sending with no receiver to update the outbox or notify the user.
 */
class MmsSentResultReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_SENT || !SmsRole.isDefault(context)) return
    val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
    val result = resultCode
    val http = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, -1)
    val async = goAsync()
    pool.execute {
      try {
        val sent = result == Activity.RESULT_OK
        MmsSendLedger.update(context, token, if (sent) "SENT_TO_CARRIER" else "FAILED", result, http)
        val uri = intent.getStringExtra(MmsSentReceiver.EXTRA_CONTENT_URI)
          ?.let(Uri::parse)
        if (uri?.scheme == "content" && uri.authority == "mms") {
          val values = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX,
              if (sent) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED)
          }
          try { context.contentResolver.update(uri, values, null, null) }
          catch (error: Exception) { Log.w("MmsSentResult", "Could not set MMS outbox status", error) }
        }
        SmsNotification.outgoing(context, "MMS", sent, token.hashCode())
        // Only delete the exact temporary source file created by pinned mmslib.
        intent.getStringExtra(MmsSentReceiver.EXTRA_FILE_PATH)?.let { name ->
          val file = File(name)
          val cache = context.cacheDir.canonicalFile
          if (file.parentFile?.canonicalFile == cache &&
            Regex("send\\.[0-9]+\\.dat").matches(file.name)) file.delete()
        }
      } catch (error: Exception) {
        Log.e("MmsSentResult", "MMS send callback failed", error)
      } finally { async.finish() }
    }
  }

  companion object {
    const val ACTION_SENT = "com.livingworld.launcher.MMS_SENT_RESULT"
    const val EXTRA_TOKEN = "mmsSendToken"
    private val pool = Executors.newSingleThreadExecutor()
  }
}
