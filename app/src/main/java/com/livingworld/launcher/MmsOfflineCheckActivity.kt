package com.livingworld.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream

/**
 * No-network, no-SMS-role visual smoke test.
 * This never calls SmsManager, never writes to Telephony and never asks to be
 * the default SMS handler. It cannot prove carrier MMS delivery.
 */
class MmsOfflineCheckActivity : Activity() {
  private var picture: Bitmap? = null
  private lateinit var photoPreview: ImageView
  private lateinit var captionInput: EditText
  private lateinit var result: TextView
  private lateinit var message: TextView
  private val imageRequest = 721

  private fun dp(n: Int) = UiKit.dp(this, n)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    window.statusBarColor = UiKit.bg
    window.navigationBarColor = UiKit.bg
    window.decorView.systemUiVisibility =
      View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    val root = UiKit.vertical(this, 14).apply {
      fitsSystemWindows = true
      setBackgroundColor(UiKit.bg)
    }
    val scroll = ScrollView(this)
    scroll.addView(root)
    setContentView(scroll)
    root.addView(UiKit.clickable(this, "‹  Carrier tests") { finish() })
    root.addView(UiKit.text(this, "Offline MMS checks", 25f, UiKit.ink, true))
    root.addView(UiKit.text(this,
      "Safe local preview — no text, photo or group message leaves your phone. Google Messages remains the default.",
      14f, UiKit.muted))
    root.addView(UiKit.clickable(this, "Run MMS input checks") { runChecks() })
    result = UiKit.text(this, "No input checks run yet.", 13f, UiKit.muted)
    root.addView(result)

    root.addView(UiKit.text(this, "Photo and group message preview",
      20f, UiKit.ink, true))
    root.addView(UiKit.text(this,
      "Sample conversation: You, Contact A, Contact B (local demonstration only)",
      13f, UiKit.muted))
    val incoming = UiKit.text(this, "Contact A: Here's the picture!", 16f, UiKit.ink).apply {
      background = UiKit.surface(this@MmsOfflineCheckActivity, Color.WHITE)
      setPadding(dp(12), dp(13), dp(12), dp(13))
    }
    root.addView(incoming, LinearLayout.LayoutParams(-1, -2))
    root.addView(UiKit.clickable(this, "Choose a photo (no sending)") {
      startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "image/*"
        addCategory(Intent.CATEGORY_OPENABLE)
      }, imageRequest)
    })
    photoPreview = ImageView(this).apply {
      adjustViewBounds = true
      maxHeight = dp(220)
      scaleType = ImageView.ScaleType.FIT_CENTER
      background = UiKit.surface(this@MmsOfflineCheckActivity,
        Color.rgb(232, 236, 247))
      contentDescription = "Selected photo preview"
    }
    root.addView(photoPreview, LinearLayout.LayoutParams(-1, dp(190)))
    captionInput = EditText(this).apply {
      hint = "Add a photo caption"
      setText("Here's my photo!")
      minLines = 1
      maxLines = 4
    }
    root.addView(captionInput)
    root.addView(UiKit.clickable(this, "Preview group message locally") {
      message.text = captionInput.text.toString().ifBlank { "Photo (no caption)" }
      if (picture == null) {
        Toast.makeText(this, "Select a photo to preview the attachment too", Toast.LENGTH_SHORT).show()
      }
    })
    message = UiKit.text(this, "Here's my photo!", 16f, UiKit.ink).apply {
      background = UiKit.surface(this@MmsOfflineCheckActivity,
        Color.rgb(220, 233, 254))
      gravity = Gravity.END
      setPadding(dp(12), dp(13), dp(12), dp(13))
    }
    root.addView(message, LinearLayout.LayoutParams(-1, -2))
    root.addView(UiKit.text(this,
      "This checks basic input parsing and local image preview only. It does not check carrier MMSC transport, MMS group delivery, receive callbacks, RCS, or message persistence.",
      12f, UiKit.muted))
    runChecks()
  }

  private fun runChecks() {
    val action = Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION
    val mime = "application/vnd.wap.mms-message"
    fun valid(intent: Intent) = MmsWapEnvelope.from(intent) is MmsWapEnvelope.Valid
    val cases = listOf(
      "Accept correctly typed bounded test envelope" to valid(
        Intent(action).setType(mime).putExtra("data", byteArrayOf(1,2,3))
      ),
      "Reject incorrect MMS MIME type" to !valid(
        Intent(action).setType("text/plain").putExtra("data", byteArrayOf(1))
      ),
      "Reject missing MMS payload" to !valid(Intent(action).setType(mime)),
      "Reject oversized MMS envelope" to !valid(
        Intent(action).setType(mime).putExtra("data", ByteArray(512 * 1024 + 1))
      ),
      "Reject unrelated broadcast action" to !valid(
        Intent("not-mms").setType(mime).putExtra("data", byteArrayOf(1))
      )
    )
    val count = cases.count { it.second }
    result.text = cases.joinToString("\n") { (description, pass) ->
      (if (pass) "✓ " else "✗ ") + description
    } + "\n$count / ${cases.size} offline envelope checks passed."
    result.setTextColor(if (count == cases.size) Color.rgb(20, 110, 80) else Color.RED)
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != imageRequest || resultCode != RESULT_OK) return
    val uri: Uri = data?.data ?: return
    val decoded = try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
        val scale = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 1280)
        val sample = BitmapFactory.Options().apply { inSampleSize = scale }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, sample) }
      }
    } catch (_: Exception) { null }
    if (decoded == null) {
      Toast.makeText(this, "This image couldn't be decoded safely", Toast.LENGTH_LONG).show()
      return
    }
    picture?.recycle()
    picture = decoded
    photoPreview.setImageBitmap(decoded)
    val out = ByteArrayOutputStream()
    val success = decoded.compress(Bitmap.CompressFormat.JPEG, 80, out)
    val bytes = if (success) out.size() else -1
    result.append("\nLocal photo: ${decoded.width}×${decoded.height}; JPEG estimate: " +
      (if (bytes >= 0) "${bytes / 1024} KB" else "unavailable") +
      ". No message sent.")
  }
}
