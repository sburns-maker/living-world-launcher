package com.livingworld.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast

/** SMS/MMS timeline with separate captions and inline media previews. */
class SmsThreadActivity : Activity() {
  private data class TimelineMessage(
    val time: Long,
    val outgoing: Boolean,
    val text: String,
    val media: List<MmsMediaPart>,
    val isMms: Boolean
  )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = UiKit.bg
    window.navigationBarColor = UiKit.bg
    window.decorView.systemUiVisibility =
      View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    if (!SmsRole.isDefault(this)) {
      Toast.makeText(this, "Message history requires the Android SMS role", Toast.LENGTH_LONG).show()
      finish()
      return
    }
    val id = intent.getLongExtra("threadId", -1L)
    if (id < 0) { finish(); return }
    val root = UiKit.vertical(this, 12).apply { setBackgroundColor(UiKit.bg) }
    setContentView(root)
    root.addView(UiKit.clickable(this, "‹  Messages") { finish() })
    val address = intent.getStringExtra("address").orEmpty()
    val title = ContactNameResolver.display(address, ContactNameResolver.snapshot(this))
    root.addView(UiKit.text(this, title, 23f, UiKit.ink, true))
    val scroll = ScrollView(this)
    val stack = UiKit.vertical(this, 8)
    scroll.addView(stack)
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

    val timeline = (
      SmsThreads.messages(this, id).map {
        TimelineMessage(it.timestamp, it.outgoing, it.body, emptyList(), false)
      } + MmsHistory.messages(this, id).map {
        TimelineMessage(it.timestampMillis, it.outgoing, it.text, it.mediaParts, true)
      }
    ).sortedBy { it.time }

    timeline.forEach { message ->
      val line = LinearLayout(this).apply {
        gravity = if (message.outgoing) Gravity.END else Gravity.START
      }
      val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = if (message.outgoing) Gravity.END else Gravity.START
      }

      // Pictures are the message; don't add an "image attachment" bubble.
      val pictures = message.media.filter { it.mimeType.startsWith("image/", true) }
        .mapNotNull { MmsThumbnail.view(this, it) }
      val otherMedia = message.media.filterNot { it.mimeType.startsWith("image/", true) }
      val caption = message.text.trim()
      if (caption.isNotBlank() || pictures.isEmpty()) {
        val fallback = when {
          otherMedia.any { it.mimeType.startsWith("video/", true) } -> "Video"
          otherMedia.any { it.mimeType.startsWith("audio/", true) } -> "Audio message"
          message.media.isNotEmpty() -> "Media"
          message.isMms -> "MMS message"
          else -> "Message"
        }
        val bubble = UiKit.text(this, caption.ifBlank { fallback }, 16f, UiKit.ink).apply {
          setPadding(UiKit.dp(this@SmsThreadActivity, 12),
            UiKit.dp(this@SmsThreadActivity, 9),
            UiKit.dp(this@SmsThreadActivity, 12),
            UiKit.dp(this@SmsThreadActivity, 9))
          background = UiKit.surface(this@SmsThreadActivity,
            if (message.outgoing) android.graphics.Color.rgb(222, 235, 255)
            else android.graphics.Color.WHITE)
          maxWidth = (resources.displayMetrics.widthPixels * 0.83f).toInt()
        }
        content.addView(bubble)
      }
      pictures.forEach { content.addView(it) }
      line.addView(content)
      stack.addView(line)
      stack.addView(UiKit.gap(this, 5))
    }
    // Keep text entry attached to the conversation instead of navigating
    // through a separate compose screen for every reply.
    val composer = UiKit.horizontal(this)
    val textInput = EditText(this).apply {
      hint = "Message"
      minLines = 1
      maxLines = 5
      setSingleLine(false)
      background = UiKit.surface(this@SmsThreadActivity)
      setPadding(UiKit.dp(this@SmsThreadActivity, 12),
        UiKit.dp(this@SmsThreadActivity, 9),
        UiKit.dp(this@SmsThreadActivity, 12),
        UiKit.dp(this@SmsThreadActivity, 9))
    }
    val destination = address
    composer.addView(UiKit.clickable(this, "＋") {
      startActivity(Intent(this, SmsComposeActivity::class.java)
        .putExtra("address", destination))
    })
    composer.addView(textInput, LinearLayout.LayoutParams(0, -2, 1f))
    composer.addView(UiKit.clickable(this, "➤") {
      val message = textInput.text.toString().trim()
      if (message.isBlank()) return@clickable
      when (val result = SmsTransport.send(this, destination, message)) {
        is SmsTransport.Validation.Ready -> {
          textInput.setText("")
          Toast.makeText(this, "Submitted to carrier; delivery pending", Toast.LENGTH_SHORT).show()
          // Refresh provider history after the sender writes the outbox record.
          recreate()
        }
        is SmsTransport.Validation.Blocked ->
          Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
      }
    })
    root.addView(composer)
  }
}
