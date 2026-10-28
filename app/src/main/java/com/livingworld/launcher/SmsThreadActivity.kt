package com.livingworld.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Read-only conversation viewer. Sending is intentionally unavailable until
 * SMS/MMS delivery and persistence are implemented and tested.
 */
class SmsThreadActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = UiKit.bg
    window.navigationBarColor = UiKit.bg
    window.decorView.systemUiVisibility =
      View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    if (!SmsRole.isDefault(this)) {
      Toast.makeText(this, "SMS history is unavailable until this app is the default SMS handler", Toast.LENGTH_LONG).show()
      finish()
      return
    }
    val id = intent.getLongExtra("threadId", -1L)
    if (id < 0) { finish(); return }
    val root = UiKit.vertical(this, 12).apply { setBackgroundColor(UiKit.bg) }
    setContentView(root)
    root.addView(UiKit.clickable(this, "‹  Messages") { finish() })
    root.addView(UiKit.text(this, intent.getStringExtra("address") ?: "Conversation", 23f, UiKit.ink, true))
    val scroll = ScrollView(this)
    val stack = UiKit.vertical(this, 8)
    scroll.addView(stack)
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    val mmsRows = MmsHistory.messages(this, id)
    val timeline = (
      SmsThreads.messages(this, id).map { Triple(it.timestamp, it.outgoing, it.body) } +
      mmsRows.map { m ->
        val attachmentLabel = if (m.mediaParts.isEmpty()) "" else
          "\n" + m.mediaParts.joinToString(", ") { it.mimeType.substringBefore("/") + " attachment" }
        Triple(m.timestampMillis, m.outgoing, m.text + attachmentLabel)
      }
    ).sortedBy { it.first }
    timeline.forEach { m ->
      val bubble = UiKit.text(this, m.third.ifBlank { "MMS message" }, 16f, UiKit.ink).apply {
        setPadding(UiKit.dp(this@SmsThreadActivity, 12), UiKit.dp(this@SmsThreadActivity, 9),
          UiKit.dp(this@SmsThreadActivity, 12), UiKit.dp(this@SmsThreadActivity, 9))
        background = UiKit.surface(this@SmsThreadActivity,
          if (m.second) android.graphics.Color.rgb(222, 235, 255) else android.graphics.Color.WHITE)
        maxWidth = (resources.displayMetrics.widthPixels * 0.83f).toInt()
      }
      val line = LinearLayout(this).apply {
        gravity = if (m.second) Gravity.END else Gravity.START
        addView(bubble)
      }
      mmsRows.firstOrNull { it.timestampMillis == m.first }?.mediaParts?.firstOrNull()?.let { part -> MmsThumbnail.view(this, part)?.let { line.addView(it) } }
      stack.addView(line)
      stack.addView(UiKit.gap(this, 5))
    }
    root.addView(UiKit.clickable(this, "Reply or send a picture") {
      startActivity(Intent(this, SmsComposeActivity::class.java)
        .putExtra("address", intent.getStringExtra("address").orEmpty()))
    })
    root.addView(UiKit.text(this,
      "Carrier test mode: verify sent and delivered results in Messages > Carrier test.", 12f, UiKit.muted))
  }
}
