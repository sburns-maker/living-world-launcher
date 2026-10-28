package com.livingworld.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private, durable per-segment carrier status. Does not store message text or numbers.
 * "Queued" means Android accepted a request, NOT that a carrier sent anything.
 */
object SmsSendLedger {
  private const val FILE = "sms_delivery_state"
  private const val PREFIX = "message_"
  private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  data class Attempt(
    val id: Long, val created: Long, val subscription: Int, val parts: Int,
    val reported: Int, val delivered: Int, val phase: String,
    val result: Int?, val radioError: Int?
  )

  @Synchronized fun start(context: Context, id: Long, parts: Int, subscription: Int): Boolean {
    if (id <= 0 || parts <= 0 || parts > 100) return false
    val state = JSONObject().put("parts", parts).put("sent", JSONArray())
      .put("failed", false).put("delivered", JSONArray())
      .put("created", System.currentTimeMillis()).put("sub", subscription)
      .put("phase", "QUEUED")
    return prefs(context).edit().putString(PREFIX + id, state.toString()).commit()
  }

  /**
   * Final true/false means every sent segment has reported OR any segment failed.
   * Failure is surfaced immediately so a lost callback cannot hide a failed send.
   */
  @Synchronized fun recordSent(
    context: Context, id: Long, part: Int, total: Int,
    resultCode: Int, radioError: Int = -1
  ): Boolean? {
    val preferences = prefs(context)
    val key = PREFIX + id
    val state = try { JSONObject(preferences.getString(key, null) ?: return null) }
      catch (_: Exception) { return null }
    if (state.optInt("parts") != total || part !in 0 until total) return null
    val seen = state.optJSONArray("sent") ?: JSONArray()
    val alreadySeen = (0 until seen.length()).any { seen.optInt(it, -1) == part }
    if (!alreadySeen) seen.put(part)
    state.put("sent", seen)
    if (resultCode != android.app.Activity.RESULT_OK) {
      state.put("failed", true)
      state.put("result", resultCode)
      state.put("radioError", radioError)
    }
    val failed = state.optBoolean("failed")
    val allSent = seen.length() == total
    state.put("phase", if (failed) "FAILED" else if (allSent) "SENT" else "WAITING_FOR_CARRIER")
    if (!preferences.edit().putString(key, state.toString()).commit()) return null
    return if (failed) false else if (allSent) true else null
  }

  @Synchronized fun recordDelivery(context: Context, id: Long, part: Int, total: Int): Boolean {
    val preferences = prefs(context)
    val key = PREFIX + id
    val state = try { JSONObject(preferences.getString(key, null) ?: return false) }
      catch (_: Exception) { return false }
    if (state.optInt("parts") != total || part !in 0 until total) return false
    val seen = state.optJSONArray("delivered") ?: JSONArray()
    if (!(0 until seen.length()).any { seen.optInt(it, -1) == part }) seen.put(part)
    state.put("delivered", seen)
    if (seen.length() == total && !state.optBoolean("failed"))
      state.put("phase", "DELIVERED")
    return preferences.edit().putString(key, state.toString()).commit()
  }

  @Synchronized fun markDispatchFailure(context: Context, id: Long) {
    val preferences = prefs(context)
    val key = PREFIX + id
    val state = try { JSONObject(preferences.getString(key, null) ?: return) }
      catch (_: Exception) { return }
    state.put("failed", true).put("phase", "DISPATCH_FAILED")
    preferences.edit().putString(key, state.toString()).commit()
  }

  @Synchronized fun recent(context: Context, limit: Int = 8): List<Attempt> {
    return prefs(context).all.mapNotNull { (key, value) ->
      if (!key.startsWith(PREFIX) || value !is String) return@mapNotNull null
      val id = key.removePrefix(PREFIX).toLongOrNull() ?: return@mapNotNull null
      val data = try { JSONObject(value) } catch (_: Exception) { return@mapNotNull null }
      Attempt(
        id, data.optLong("created"), data.optInt("sub", -1),
        data.optInt("parts", 0), data.optJSONArray("sent")?.length() ?: 0,
        data.optJSONArray("delivered")?.length() ?: 0,
        data.optString("phase", "QUEUED"),
        if (data.has("result")) data.optInt("result") else null,
        if (data.has("radioError")) data.optInt("radioError") else null
      )
    }.sortedByDescending { it.created }.take(limit)
  }

  @Synchronized fun clear(context: Context, id: Long) {
    prefs(context).edit().remove(PREFIX + id).apply()
  }
}
