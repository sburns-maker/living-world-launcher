package com.livingworld.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Optional, short-lived conversation window for Google Messages notifications,
 * including RCS. This is not an RCS provider or a complete message history.
 * Replies use only the RemoteInput action Google Messages supplies.
 */
class BridgeConversationActivity : Activity() {
  private lateinit var title: TextView
  private lateinit var preview: TextView
  private lateinit var status: TextView
  private lateinit var composer: EditText
  private lateinit var send: TextView
  private val key: String by lazy { intent.getStringExtra(EXTRA_NOTIFICATION_KEY).orEmpty() }
  private var submitting = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = UiKit.bg
    window.navigationBarColor = UiKit.bg
    window.decorView.systemUiVisibility =
      View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    val root = UiKit.vertical(this, 14).apply {
      setBackgroundColor(UiKit.bg)
      fitsSystemWindows = true
    }
    setContentView(root)

    val header = UiKit.horizontal(this)
    header.addView(UiKit.clickable(this, "‹") { finish() })
    title = UiKit.text(this, "Conversation", 20f, UiKit.ink, true)
    header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
    header.addView(UiKit.clickable(this, "⋮") {
      AlertDialog.Builder(this).setItems(arrayOf("Open full chat in Google Messages")) { _, _ ->
        openOriginal()
      }.show()
    })
    root.addView(header)
    val body = UiKit.vertical(this, 10)
    body.addView(UiKit.text(this,
      "Latest notification preview", 12f, UiKit.muted))
    val incoming = UiKit.horizontal(this).apply { gravity = Gravity.START }
    preview = UiKit.text(this, "", 16f, UiKit.ink).apply {
      setPadding(UiKit.dp(this@BridgeConversationActivity, 14),
        UiKit.dp(this@BridgeConversationActivity, 12),
        UiKit.dp(this@BridgeConversationActivity, 14),
        UiKit.dp(this@BridgeConversationActivity, 12))
      background = UiKit.surface(this@BridgeConversationActivity, Color.WHITE)
      maxWidth = (resources.displayMetrics.widthPixels * .82f).toInt()
    }
    incoming.addView(preview)
    body.addView(incoming)
    body.addView(UiKit.text(this,
      "Only notifications currently provided by Google Messages appear here. Open the full chat for earlier messages and delivery details.",
      12f, UiKit.muted))
    root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    status = UiKit.text(this, "", 12f, UiKit.muted)
    root.addView(status)
    val bar = UiKit.horizontal(this)
    composer = EditText(this).apply {
      hint = "Message"
      minLines = 1
      maxLines = 5
      setSingleLine(false)
      background = UiKit.surface(this@BridgeConversationActivity)
      setPadding(UiKit.dp(this@BridgeConversationActivity, 14),
        UiKit.dp(this@BridgeConversationActivity, 9),
        UiKit.dp(this@BridgeConversationActivity, 14),
        UiKit.dp(this@BridgeConversationActivity, 9))
    }
    bar.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
    send = UiKit.clickable(this, "Send") { submitReply() }
    bar.addView(send)
    root.addView(bar)
    renderEntry()
  }

  override fun onResume() {
    super.onResume()
    if (::title.isInitialized) renderEntry()
  }

  private fun textReply(entry: BridgeEntry): Pair<android.app.Notification.Action, Array<RemoteInput>>? {
    val action = entry.replyAction ?: return null
    val inputs = action.remoteInputs?.filter { it.allowFreeFormInput }?.toTypedArray() ?: return null
    if (inputs.isEmpty()) return null
    return action to inputs
  }

  private fun renderEntry() {
    val entry = BridgeInbox.find(key)
    if (entry == null) {
      title.text = "Conversation"
      preview.text = "This notification is no longer available."
      composer.isEnabled = false
      send.isEnabled = false
      send.alpha = .45f
      if (!submitting) status.text = "Open Google Messages to continue."
      return
    }
    title.text = ContactNameResolver.display(entry.sender, ContactNameResolver.snapshot(this))
    preview.text = entry.preview.ifBlank { "New message" }
    val canReply = !submitting && textReply(entry) != null
    composer.isEnabled = canReply
    send.isEnabled = canReply
    send.alpha = if (canReply) 1f else .45f
    if (!canReply && !submitting) status.text = "Reply from Google Messages to continue."
  }

  private fun submitReply() {
    if (submitting) return
    val message = composer.text.toString().trim()
    if (message.isBlank()) return
    val entry = BridgeInbox.find(key)
    val reply = entry?.let(::textReply)
    if (reply == null) {
      renderEntry()
      Toast.makeText(this, "Reply action expired. Open Google Messages.", Toast.LENGTH_LONG).show()
      return
    }
    submitting = true
    send.isEnabled = false
    val (action, inputs) = reply
    try {
      val values = Bundle()
      inputs.forEach { input -> values.putCharSequence(input.resultKey, message) }
      val fillIn = Intent()
      RemoteInput.addResultsToIntent(inputs, fillIn, values)
      action.actionIntent.send(this, 0, fillIn)
      composer.setText("")
      status.text = "Reply submitted through Google Messages; delivery isn't confirmed."
    } catch (_: PendingIntent.CanceledException) {
      status.text = "Reply action expired. Your draft is preserved."
    } catch (_: Exception) {
      status.text = "Could not submit reply. Your draft is preserved."
    } finally {
      submitting = false
      renderEntry()
    }
  }

  private fun openOriginal() {
    val entry = BridgeInbox.find(key)
    try {
      val original = entry?.openIntent
      if (original != null) { original.send(); return }
    } catch (_: PendingIntent.CanceledException) { /* Fall back. */ }
    try {
      val system = packageManager.getLaunchIntentForPackage(GoogleMessagesBridge.GOOGLE_MESSAGES)
        ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
      startActivity(system)
    } catch (_: Exception) {
      Toast.makeText(this, "Google Messages unavailable", Toast.LENGTH_SHORT).show()
    }
  }

  companion object {
    const val EXTRA_NOTIFICATION_KEY = "notificationKey"
  }
}
