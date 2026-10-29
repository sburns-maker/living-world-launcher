package com.livingworld.launcher

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

/** Local multi-page widget host using Android's standard consent/configure flows. */
class HomeWidgets(private val activity: Activity, private val prefs: SharedPreferences) {
  private val manager = AppWidgetManager.getInstance(activity)
  private val host = AppWidgetHost(activity, 0x4c57)
  private fun dp(n: Int) = UiKit.dp(activity, n)
  private fun key(page: Int) = "home_widget_page_" + page
  private var changed: (() -> Unit)? = null

  fun start() { runCatching { host.startListening() } }
  fun stop() { runCatching { host.stopListening() } }
  private fun entries(page: Int): MutableList<String> = try {
    val array = JSONArray(prefs.getString(key(page), "[]"))
    (0 until array.length()).mapNotNull {
      array.optString(it).takeIf(String::isNotBlank)
    }.take(8).toMutableList()
  } catch (_: Exception) { mutableListOf() }
  private fun save(page: Int, list: List<String>) {
    val array = JSONArray()
    list.take(8).forEach { array.put(it) }
    prefs.edit().putString(key(page), array.toString()).apply()
  }
  fun count(page: Int) = entries(page).size

  fun add(page: Int, onChange: () -> Unit) {
    changed = onChange
    if (count(page) >= 8) { toast("Maximum eight widgets per page"); return }
    val providers = try {
      manager.installedProviders.sortedBy {
        it.loadLabel(activity.packageManager).toString().lowercase()
      }
    } catch (_: Exception) { emptyList<AppWidgetProviderInfo>() }
    val types = listOf("Digital clock", "Date", "Message notifications")
    val labels = types + providers.map { it.loadLabel(activity.packageManager).toString() }
    AlertDialog.Builder(activity).setTitle("Add widget")
      .setItems(labels.toTypedArray()) { _, selected ->
        if (selected < types.size) {
          val items = entries(page)
          items.add("native:" + listOf("clock", "date", "notifications")[selected])
          save(page, items)
          onChange()
        } else bind(page, providers[selected - types.size])
      }.setNegativeButton("Cancel", null).show()
  }

  private fun bind(page: Int, info: AppWidgetProviderInfo) {
    val id = try { host.allocateAppWidgetId() }
      catch (_: Exception) { toast("Unable to allocate widget"); return }
    prefs.edit().putInt("widget_pending_id", id)
      .putInt("widget_pending_page", page).commit()
    val approved = try {
      manager.bindAppWidgetIdIfAllowed(id, info.provider)
    } catch (_: Exception) { false }
    if (approved) configure(id) else try {
      activity.startActivityForResult(
        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
          .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
          .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider), REQUEST_BIND)
    } catch (_: Exception) { abandon(id); toast("Widget consent unavailable") }
  }
  private fun configure(id: Int) {
    val info = manager.getAppWidgetInfo(id) ?: run { abandon(id); return }
    val component = info.configure
    if (component == null) { finish(id); return }
    try {
      activity.startActivityForResult(
        Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setComponent(component)
          .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), REQUEST_CONFIGURE)
    } catch (_: Exception) { abandon(id); toast("Widget setup unavailable") }
  }
  fun onResult(request: Int, result: Int, data: Intent?) {
    if (request != REQUEST_BIND && request != REQUEST_CONFIGURE) return
    val id = prefs.getInt("widget_pending_id", -1)
    if (id < 0) return
    val returned = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id) ?: id
    if (result != Activity.RESULT_OK || returned != id) {
      abandon(id)
    } else if (request == REQUEST_BIND) configure(id) else finish(id)
  }
  private fun finish(id: Int) {
    val page = prefs.getInt("widget_pending_page", 0)
    val list = entries(page)
    list.add("android:" + id)
    save(page, list)
    clearPending()
    changed?.invoke()
  }
  private fun abandon(id: Int) {
    runCatching { host.deleteAppWidgetId(id) }
    clearPending()
    changed?.invoke()
  }
  private fun clearPending() {
    prefs.edit().remove("widget_pending_id").remove("widget_pending_page").apply()
  }
  fun remove(page: Int, index: Int, onChange: () -> Unit) {
    val list = entries(page)
    val item = list.getOrNull(index) ?: return
    AlertDialog.Builder(activity).setTitle("Remove widget?")
      .setMessage("Remove this widget from your Home screen?")
      .setNegativeButton("Cancel", null)
      .setPositiveButton("Remove") { _, _ ->
        if (item.startsWith("android:"))
          item.removePrefix("android:").toIntOrNull()?.let { runCatching { host.deleteAppWidgetId(it) } }
        list.removeAt(index)
        save(page, list)
        onChange()
      }.show()
  }
  fun clearPage(page: Int) {
    entries(page).forEach { item ->
      if (item.startsWith("android:"))
        item.removePrefix("android:").toIntOrNull()?.let { runCatching { host.deleteAppWidgetId(it) } }
    }
    prefs.edit().remove(key(page)).apply()
  }
  fun movePage(from: Int, to: Int) {
    save(to, entries(from))
    prefs.edit().remove(key(from)).apply()
  }

  fun views(page: Int, onChange: () -> Unit): List<View> =
    entries(page).mapIndexed { index, entry ->
      val container = FrameLayout(activity).apply {
        background = UiKit.surface(activity, Color.argb(220, 25, 32, 55), 22)
      }
      val view: View = when {
        entry == "native:clock" -> nativeClock("h:mm", 36f)
        entry == "native:date" -> nativeClock("EEEE, MMM d", 24f)
        entry == "native:notifications" -> TextView(activity).apply {
          val count = if (GoogleMessagesBridge.hasAccess(activity))
            BridgeInbox.snapshot().size else 0
          text = if (count == 0) "No new message notifications" else
            count.toString() + " message notification(s)"
          textSize = 18f
          setTextColor(Color.WHITE)
          gravity = Gravity.CENTER_VERTICAL
          setPadding(dp(18), dp(12), dp(18), dp(12))
          setOnClickListener {
            activity.startActivity(Intent(activity, WorldInboxActivity::class.java))
          }
        }
        entry.startsWith("android:") -> {
          val id = entry.removePrefix("android:").toIntOrNull()
          val info = id?.let { manager.getAppWidgetInfo(it) }
          if (id != null && info != null)
            try { host.createView(activity, id, info) }
            catch (_: Exception) { missing("Widget unavailable") }
          else missing("Widget missing · tap × to remove")
        }
        else -> missing("Widget unavailable")
      }
      container.addView(view, FrameLayout.LayoutParams(-1, -1))
      container.addView(TextView(activity).apply {
        text = "×"
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = UiKit.surface(activity, Color.argb(245, 59, 67, 89), 20)
        contentDescription = "Remove widget"
        setOnClickListener { remove(page, index, onChange) }
      }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.END or Gravity.TOP).apply {
        setMargins(0, dp(3), dp(5), 0)
      })
      container
    }
  private fun nativeClock(pattern: String, size: Float): TextClock = TextClock(activity).apply {
    format12Hour = pattern
    format24Hour = pattern
    textSize = size
    setTextColor(Color.WHITE)
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(18), dp(10), dp(18), dp(10))
  }
  private fun missing(label: String) = TextView(activity).apply {
    text = label
    textSize = 14f
    gravity = Gravity.CENTER
    setTextColor(Color.WHITE)
  }
  private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
  companion object {
    const val REQUEST_BIND = 7101
    const val REQUEST_CONFIGURE = 7102
  }
}
