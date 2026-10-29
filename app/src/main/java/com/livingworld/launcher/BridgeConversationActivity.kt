package com.livingworld.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/**
 * Native notification-derived Google Messages conversation.
 * Google Messages remains the SMS/RCS default. No carrier or RCS sends occur here:
 * reply is delegated only through an active Google Messages RemoteInput.
 */
class BridgeConversationActivity : Activity() {
  private val threadId: String by lazy { intent.getStringExtra(EXTRA_THREAD_ID).orEmpty() }
  private lateinit var title: TextView
  private lateinit var messages: LinearLayout
  private lateinit var scroll: ScrollView
  private lateinit var status: TextView
  private lateinit var composer: EditText
  private lateinit var send: TextView
  private var submitting = false
  private var relayListening = false
  private var lastSignature: String? = null
  private val relayReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == GoogleMessagesBridge.ACTION_UPDATED) renderThread()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = UiKit.bg
    window.navigationBarColor = UiKit.bg
    window.decorView.systemUiVisibility =
      View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    if (threadId.isBlank()) { finish(); return }
    val root = UiKit.vertical(this, 10).apply {
      setBackgroundColor(UiKit.bg)
      fitsSystemWindows = true
    }
    setContentView(root)
    val header = UiKit.horizontal(this)
    header.addView(UiKit.clickable(this, "‹") { finish() })
    title = UiKit.text(this, "Conversation", 19f, UiKit.ink, true)
    header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
    header.addView(UiKit.clickable(this, "⋮") {
      AlertDialog.Builder(this)
        .setItems(arrayOf("Open full conversation in Google Messages",
          "Clear local notification previews")) { _, choice ->
          if (choice == 0) openOriginal()
          else AlertDialog.Builder(this).setTitle("Clear saved previews?")
            .setMessage("This removes Living World's cached notification snippets, not the original messages.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
              RelayHistoryStore.clear(this)
              renderThread()
            }.show()
        }.show()
    })
    root.addView(header)
    scroll = ScrollView(this).apply { isFillViewport = true }
    messages = UiKit.vertical(this, 8)
    scroll.addView(messages)
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    status = UiKit.text(this, "", 12f, UiKit.muted)
    root.addView(status)
    val bar = UiKit.horizontal(this)
    composer = EditText(this).apply {
      hint = "Message"
      minLines = 1
      maxLines = 5
      setSingleLine(false)
      background = UiKit.surface(this@BridgeConversationActivity)
      setPadding(UiKit.dp(this@BridgeConversationActivity, 12),
        UiKit.dp(this@BridgeConversationActivity, 9),
        UiKit.dp(this@BridgeConversationActivity, 12),
        UiKit.dp(this@BridgeConversationActivity, 9))
    }
    bar.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
    send = UiKit.clickable(this, "➤") { submitReply() }
    send.contentDescription = "Send through Google Messages"
    bar.addView(send)
    root.addView(bar)
    RelayHistoryStore.markRead(this, threadId)
    renderThread()
  }

  override fun onResume() {
    super.onResume()
    if (!relayListening) {
      val filter = IntentFilter(GoogleMessagesBridge.ACTION_UPDATED)
      if (Build.VERSION.SDK_INT >= 33)
        registerReceiver(relayReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
      else {
        @Suppress("DEPRECATION")
        registerReceiver(relayReceiver, filter)
      }
      relayListening = true
    }
    if (::messages.isInitialized) renderThread()
  }
  override fun onPause() {
    if (relayListening) { unregisterReceiver(relayReceiver); relayListening = false }
    super.onPause()
  }

  private fun liveAction(): Pair<Notification.Action, Array<RemoteInput>>? {
    val entry = BridgeInbox.findThread(threadId) ?: return null
    val action = entry.replyAction ?: return null
    val inputs = action.remoteInputs?.filter { it.allowFreeFormInput }?.toTypedArray()
      ?: return null
    if (inputs.isEmpty()) return null
    return action to inputs
  }

  private fun renderThread() {
    val cached = RelayHistoryStore.threads(this).firstOrNull { it.id == threadId }
    val active = BridgeInbox.findThread(threadId)
    title.text = cached?.title ?: active?.sender ?: "Conversation"
    val saved = RelayHistoryStore.messages(this, threadId)
    val shown = if (saved.isEmpty() && active != null) listOf(RelayMessage(
      active.notificationKey, active.sender, active.preview, active.postedAt, false
    )) else saved

    val signature = shown.joinToString("|") { it.id } + ":" + title.text
    if (signature != lastSignature) {
      lastSignature = signature
      messages.removeAllViews()
      if (shown.isEmpty()) {
        messages.addView(UiKit.text(this,
          "No saved notification previews. Open Google Messages for the conversation.",
          14f, UiKit.muted))
      }
      shown.forEach { msg ->
        val line = UiKit.horizontal(this).apply {
          gravity = if (msg.outgoing) Gravity.END else Gravity.START
        }
        val bubble = UiKit.vertical(this, 3)
        if (!msg.outgoing && msg.sender != title.text.toString()) {
          bubble.addView(UiKit.text(this, msg.sender, 12f, UiKit.muted))
        }
        val label = UiKit.text(this, msg.body, 16f, UiKit.ink).apply {
          background = UiKit.surface(this@BridgeConversationActivity,
            if (msg.outgoing) Color.rgb(220, 233, 254) else Color.WHITE, 20)
          setPadding(UiKit.dp(this@BridgeConversationActivity, 13),
            UiKit.dp(this@BridgeConversationActivity, 10),
            UiKit.dp(this@BridgeConversationActivity, 13),
            UiKit.dp(this@BridgeConversationActivity, 10))
          maxWidth = (resources.displayMetrics.widthPixels * .83f).toInt()
        }
        bubble.addView(label)
        bubble.addView(UiKit.text(this,
          DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(msg.at)),
          11f, UiKit.muted))
        line.addView(bubble)
        messages.addView(line)
        messages.addView(UiKit.gap(this, 8))
      }
      scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    val canReply = !submitting && liveAction() != null
    composer.isEnabled = canReply
    send.isEnabled = canReply
    send.alpha = if (canReply) 1f else .45f
    if (!canReply && status.text.isBlank()) {
      status.text = "Reply unavailable here. Open Google Messages to continue."
    }
  }

  private fun submitReply() {
    if (submitting) return
    val message = composer.text.toString().trim()
    if (message.isBlank()) return
    val reply = liveAction()
    if (reply == null) {
      renderThread()
      Toast.makeText(this, "Open Google Messages to reply.", Toast.LENGTH_LONG).show()
      return
    }
    submitting = true
    send.isEnabled = false
    try {
      val (action, inputs) = reply
      val data = Bundle()
      inputs.forEach { input -> data.putCharSequence(input.resultKey, message) }
      val intent = Intent()
      RemoteInput.addResultsToIntent(inputs, intent, data)
      action.actionIntent.send(this, 0, intent)
      composer.setText("")
      RelayHistoryStore.record(this, threadId, title.text.toString(),
        listOf(RelayEvent("You", message, System.currentTimeMillis(), true)))
      status.text = "Submitted through Google Messages; delivery not confirmed."
      renderThread()
    } catch (_: PendingIntent.CanceledException) {
      status.text = "Reply expired. Your draft was kept."
    } catch (_: Exception) {
      status.text = "Reply could not be submitted. Your draft was kept."
    } finally {
      submitting = false
      renderThread()
    }
  }

  private fun openOriginal() {
    try {
      val original = BridgeInbox.findThread(threadId)?.openIntent
      if (original != null) { original.send(); return }
    } catch (_: PendingIntent.CanceledException) { /* Use app launch fallback. */ }
    try {
      val destination = packageManager.getLaunchIntentForPackage(GoogleMessagesBridge.GOOGLE_MESSAGES)
        ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
      startActivity(destination)
    } catch (_: Exception) {
      Toast.makeText(this, "Google Messages unavailable", Toast.LENGTH_SHORT).show()
    }
  }

  companion object {
    const val EXTRA_THREAD_ID = "relayThreadId"
  }
}
