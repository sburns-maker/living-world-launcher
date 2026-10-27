package com.livingworld.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, local-only per-part send results. Never stores message body or recipient.
 * State is keyed to the Android SMS provider row ID and survives process death.
 */
object SmsSendLedger {
  private const val FILE = "sms_delivery_state"
  private const val PREFIX = "message_"
  private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  @Synchronized fun start(context: Context, id: Long, parts: Int): Boolean {
    if (id <= 0 || parts <= 0 || parts > 100) return false
    val state = JSONObject().put("parts", parts).put("sent", JSONArray())
      .put("failed", false).put("delivered", JSONArray())
    return prefs(context).edit().putString(PREFIX + id, state.toString()).commit()
  }

  /** Returns a final state only when every segment reported. Null means pending/unknown. */
  @Synchronized fun recordSent(context: Context, id: Long, part: Int, total: Int, success: Boolean): Boolean? {
    val p = prefs(context)
    val key = PREFIX + id
    val state = try { JSONObject(p.getString(key, null) ?: return null) } catch (_: Exception) { return null }
    if (state.optInt("parts") != total || part !in 0 until total) return null
    val seen = state.optJSONArray("sent") ?: JSONArray()
    if (!(0 until seen.length()).any { seen.optInt(it, -1) == part }) seen.put(part)
    state.put("sent", seen)
    if (!success) state.put("failed", true)
    if (!p.edit().putString(key, state.toString()).commit()) return null
    if (state.optBoolean("failed")) return false
    return if (seen.length() == total) true else null
  }

  @Synchronized fun recordDelivery(context: Context, id: Long, part: Int, total: Int): Boolean {
    val p = prefs(context)
    val key = PREFIX + id
    val state = try { JSONObject(p.getString(key, null) ?: return false) } catch (_: Exception) { return false }
    if (state.optInt("parts") != total || part !in 0 until total) return false
    val seen = state.optJSONArray("delivered") ?: JSONArray()
    if (!(0 until seen.length()).any { seen.optInt(it, -1) == part }) seen.put(part)
    state.put("delivered", seen)
    return p.edit().putString(key, state.toString()).commit()
  }

  @Synchronized fun clear(context: Context, id: Long) {
    prefs(context).edit().remove(PREFIX + id).apply()
  }
}
