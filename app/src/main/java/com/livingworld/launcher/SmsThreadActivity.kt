package com.livingworld.launcher

import android.app.Activity
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
    SmsThreads.messages(this, id).forEach { m ->
      val bubble = UiKit.text(this, m.body, 16f, UiKit.ink).apply {
        setPadding(UiKit.dp(this@SmsThreadActivity, 12), UiKit.dp(this@SmsThreadActivity, 9),
          UiKit.dp(this@SmsThreadActivity, 12), UiKit.dp(this@SmsThreadActivity, 9))
        background = UiKit.surface(this@SmsThreadActivity,
          if (m.outgoing) android.graphics.Color.rgb(222, 235, 255) else android.graphics.Color.WHITE)
        maxWidth = (resources.displayMetrics.widthPixels * 0.83f).toInt()
      }
      val line = LinearLayout(this).apply {
        gravity = if (m.outgoing) Gravity.END else Gravity.START
        addView(bubble)
      }
      stack.addView(line)
      stack.addView(UiKit.gap(this, 5))
    }
    root.addView(UiKit.text(this,
      "Read-only preview. Sending and MMS are not enabled yet.", 12f, UiKit.muted))
  }
}
