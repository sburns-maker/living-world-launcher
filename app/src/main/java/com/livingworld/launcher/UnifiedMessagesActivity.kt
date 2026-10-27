package com.livingworld.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.RemoteInput
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import android.view.Gravity

/** Native messages shell; sample AI threads + optional read-only notification previews. */
class UnifiedMessagesActivity : Activity() {
  private var previews: LinearLayout? = null
  private var status: TextView? = null
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = UiKit.bg
    window.navigationBarColor = UiKit.bg
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    val content = UiKit.vertical(this, 20).apply { setBackgroundColor(UiKit.bg) }
    setContentView(ScrollView(this).apply { isFillViewport = true; addView(content) })
    content.addView(UiKit.clickable(this, "‹  Home") { finish() })
    content.addView(UiKit.gap(this, 24))
    content.addView(UiKit.text(this, "Messages", 32f, UiKit.ink, true))

    content.addView(UiKit.gap(this, 20))
    val worldCard = UiKit.vertical(this, 16).apply {
      background = UiKit.surface(this@UnifiedMessagesActivity, Color.rgb(239, 234, 252))
    }
    worldCard.addView(UiKit.text(this, "Conversations", 21f, UiKit.ink, true))
    worldCard.addView(UiKit.text(this, "Your current chats, photos and replies", 14f, UiKit.muted))
    worldCard.addView(UiKit.clickable(this, "Open conversations  ›") { openWorld() })
    content.addView(worldCard)
    content.addView(UiKit.gap(this, 24))
    content.addView(UiKit.text(this, "Recent real messages", 17f, UiKit.ink, true))
    content.addView(UiKit.text(this, "Recent notification previews only — not full RCS history", 13f, UiKit.muted))
    content.addView(UiKit.gap(this, 12))
    status = UiKit.text(this, "", 13f, UiKit.muted)
    content.addView(status)
    previews = UiKit.vertical(this)
    content.addView(previews)
    content.addView(UiKit.clickable(this, "Refresh previews") { refresh() })
    content.addView(UiKit.gap(this, 8))
    content.addView(UiKit.clickable(this, "Notification access settings") {
      startActivity(GoogleMessagesBridge.settingsIntent())
    })
    content.addView(UiKit.gap(this, 8))
    content.addView(UiKit.clickable(this, "Open Google Messages  ↗") { openGoogleMessages() })
    content.addView(UiKit.gap(this, 20))
    content.addView(UiKit.text(this, "Google Messages remains your default SMS/RCS app. No real messages are sent, changed, or imported here.", 13f, UiKit.muted))
    refresh()
  }
  override fun onResume() { super.onResume(); if (previews != null) refresh() }
  private fun refresh() {
    val allowed = GoogleMessagesBridge.hasAccess(this)
    status?.text = if (allowed) "Notification preview access enabled" else "Notification previews off"
    val root = previews ?: return
    root.removeAllViews()
    if (!allowed) return
    val items = BridgeInbox.snapshot()
    if (items.isEmpty()) root.addView(UiKit.text(this, "No active Google Messages notifications", 14f, UiKit.muted))
    items.forEach { item ->
      val card = UiKit.vertical(this, 12).apply { background = UiKit.surface(this@UnifiedMessagesActivity) }
      card.addView(UiKit.text(this, item.sender, 15f, UiKit.ink, true))
      card.addView(UiKit.text(this, item.preview, 13f, UiKit.muted))
      val actions = UiKit.horizontal(this)
      if (item.openIntent != null) {
        actions.addView(UiKit.clickable(this, "Open chat") {
          try { item.openIntent.send() }
          catch (_: PendingIntent.CanceledException) { openGoogleMessages() }
        })
      }
      if (item.replyAction?.remoteInputs?.isNotEmpty() == true) {
        actions.addView(UiKit.clickable(this, "Reply") { replyTo(item.notificationKey) })
      }
      card.addView(actions)
      root.addView(card)
      root.addView(UiKit.gap(this, 8))
    }
  }
  private fun replyTo(key: String) {
    val entry = BridgeInbox.find(key) ?: return
    val action = entry.replyAction ?: return
    val inputs = action.remoteInputs ?: return
    if (inputs.isEmpty()) return
    val field = EditText(this).apply { hint = "Reply"; isSingleLine = false; maxLines = 5 }
    AlertDialog.Builder(this).setTitle("Reply to " + entry.sender)
      .setView(field)
      .setNegativeButton("Cancel", null)
      .setPositiveButton("Send") { _, _ ->
        val message = field.text.toString().trim()
        if (message.isEmpty()) return@setPositiveButton
        val results = android.os.Bundle()
        inputs.forEach { input -> results.putCharSequence(input.resultKey, message) }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        try {
          action.actionIntent.send(this, 0, intent)
          Toast.makeText(this, "Reply submitted to Google Messages", Toast.LENGTH_SHORT).show()
        } catch (_: PendingIntent.CanceledException) {
          Toast.makeText(this, "Reply expired. Open Google Messages.", Toast.LENGTH_LONG).show()
        }
      }.show()
  }
  private fun openGoogleMessages() {
    val intent = packageManager.getLaunchIntentForPackage(GoogleMessagesBridge.GOOGLE_MESSAGES)
      ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
    try { startActivity(intent) }
    catch (_: Exception) { Toast.makeText(this, "Google Messages unavailable", Toast.LENGTH_SHORT).show() }
  }
  private fun openWorld() {
    startActivity(Intent(this, WorldInboxActivity::class.java))
  }
}
