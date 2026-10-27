package com.livingworld.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.telephony.PhoneNumberUtils
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Intentionally non-sending composer: never dispatches a test SMS.
 * Production delivery requires provider persistence, result receivers, and MMS.
 */
class SmsComposeActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = UiKit.bg
    window.navigationBarColor = UiKit.bg
    window.decorView.systemUiVisibility =
      View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    val root = UiKit.vertical(this, 18).apply { setBackgroundColor(UiKit.bg) }
    setContentView(root)
    root.addView(UiKit.clickable(this, "‹  Messages") { finish() })
    root.addView(UiKit.text(this, "New message", 28f, UiKit.ink, true))
    val recipient = EditText(this).apply {
      hint = "To"
      setSingleLine(true)
      setText(intent.getStringExtra("address") ?: intent.data?.schemeSpecificPart.orEmpty())
    }
    root.addView(recipient)
    val body = EditText(this).apply {
      hint = "Message"
      minLines = 3
      setText(intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra("sms_body") ?: "")
    }
    root.addView(body)
    root.addView(UiKit.clickable(this, "Save draft (not yet available)") {
      Toast.makeText(this, "Draft storage and sending are disabled until SMS engine validation", Toast.LENGTH_LONG).show()
    })
    root.addView(UiKit.text(this,
      "Preview only. We will not send or save texts until receiving, sending, and MMS are ready.", 13f, UiKit.muted))
  }
}
