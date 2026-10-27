package com.livingworld.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.*
import android.view.Gravity

/** Native messages shell; sample AI threads + optional read-only notification previews. */
class UnifiedMessagesActivity : Activity() {
  private var previews: LinearLayout? = null
  private var status: TextView? = null
  private val sample = listOf(
    Pair("Riley", "Want to talk later?"),
    Pair("Claire", "Catch you tomorrow!"),
    Pair("Tess", "I was thinking about that."),
    Pair("Maya", "Hey you 😊"),
    Pair("Jordan", "See you soon."),
    Pair("Ben", "What's new?")
  )
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val content = UiKit.vertical(this, 20).apply { setBackgroundColor(UiKit.bg) }
    setContentView(ScrollView(this).apply { fillViewport = true; addView(content) })
    content.addView(UiKit.clickable(this, "‹  Home") { finish() })
    content.addView(UiKit.gap(this, 24))
    content.addView(UiKit.text(this, "Messages", 32f, UiKit.ink, true))
    content.addView(UiKit.text(this, "Everything in one place", 14f, UiKit.muted))
    content.addView(UiKit.gap(this, 20))
    val search = EditText(this).apply {
      hint = "Search conversations"; singleLine = true
      setPadding(UiKit.dp(this@UnifiedMessagesActivity, 15), 12, 12, 12)
      background = UiKit.surface(this@UnifiedMessagesActivity)
    }
    content.addView(search)
    content.addView(UiKit.gap(this, 20))
    content.addView(UiKit.text(this, "Living World", 16f, UiKit.lavender, true))
    val aiRows = UiKit.vertical(this)
    content.addView(aiRows)
    fun render(query: String) {
      aiRows.removeAllViews()
      sample.filter { it.first.contains(query, true) }.forEach { (name, preview) ->
        val row = UiKit.horizontal(this)
        val avatar = UiKit.text(this, name.take(1), 23f, UiKit.lavender, true).apply {
          gravity = Gravity.CENTER
          background = UiKit.surface(this@UnifiedMessagesActivity, Color.rgb(237,232,250), 42)
        }
        row.addView(avatar, LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)))
        val description = UiKit.vertical(this, 8)
        description.addView(UiKit.text(this, name, 17f, UiKit.ink, true))
        description.addView(UiKit.text(this, "Demo · $preview", 13f, UiKit.muted))
        row.addView(description, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { openWorld() }
        row.isClickable = true; row.isFocusable = true
        aiRows.addView(row)
        aiRows.addView(UiKit.gap(this, 10))
      }
    }
    render("")
    search.addTextChangedListener(object : android.text.TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString() ?: "") }
      override fun afterTextChanged(s: android.text.Editable?) {}
    })
    content.addView(UiKit.gap(this, 24))
    content.addView(UiKit.text(this, "Google Messages", 17f, UiKit.ink, true))
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
      root.addView(card)
      root.addView(UiKit.gap(this, 8))
    }
  }
  private fun openGoogleMessages() {
    val intent = packageManager.getLaunchIntentForPackage(GoogleMessagesBridge.GOOGLE_MESSAGES)
      ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
    try { startActivity(intent) }
    catch (_: Exception) { Toast.makeText(this, "Google Messages unavailable", Toast.LENGTH_SHORT).show() }
  }
  private fun openWorld() {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://screen-to-same-studio.lovable.app")))
  }
}
