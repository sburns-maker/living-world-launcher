package com.livingworld.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
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
  private var selectedImage: Bitmap? = null
  private var thumbnail: ImageView? = null
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
    root.addView(UiKit.clickable(this, "Attach picture") {
      startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "image/*"
        addCategory(Intent.CATEGORY_OPENABLE)
      }, 701)
    })
    thumbnail = ImageView(this).apply { adjustViewBounds = true; maxHeight = UiKit.dp(this@SmsComposeActivity, 180) }
    root.addView(thumbnail)
    root.addView(UiKit.clickable(this, "Send message") {
      val numbers = recipient.text.toString().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
      val message = body.text.toString()
      if (numbers.isEmpty() || (message.isBlank() && selectedImage == null)) {
        Toast.makeText(this, "Add a recipient and message or picture", Toast.LENGTH_LONG).show()
      } else {
        val result = if (numbers.size > 1 || selectedImage != null)
          MmsTransport.sendGroup(this, numbers, message, selectedImage)
        else {
          val verdict = SmsTransport.send(this, numbers.single(), message)
          if (verdict is SmsTransport.Validation.Blocked) Result.failure(IllegalStateException(verdict.reason))
          else Result.success(Unit)
        }
        Toast.makeText(this, result.exceptionOrNull()?.message ?: "Carrier send requested", Toast.LENGTH_LONG).show()
      }
    })
    root.addView(UiKit.clickable(this, "Save draft (not yet available)") {
      Toast.makeText(this, "Draft storage and sending are disabled until SMS engine validation", Toast.LENGTH_LONG).show()
    })
    root.addView(UiKit.text(this,
      "Preview only. We will not send or save texts until receiving, sending, and MMS are ready.", 13f, UiKit.muted))
  }
  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != 701 || resultCode != RESULT_OK) return
    val uri = data?.data ?: return
    selectedImage = try {
      val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
      val scale = maxOf(1, maxOf(options.outWidth, options.outHeight) / 1280)
      val sample = BitmapFactory.Options().apply { inSampleSize = scale }
      contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, sample) }
    } catch (_: Exception) { null }
    thumbnail?.setImageBitmap(selectedImage)
    if (selectedImage == null) Toast.makeText(this, "Unable to open picture", Toast.LENGTH_LONG).show()
  }
}
