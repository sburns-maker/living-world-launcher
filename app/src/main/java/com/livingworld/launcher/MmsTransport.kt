package com.livingworld.launcher

import android.content.Context
import android.graphics.Bitmap
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction

/**
 * Fossify mmslib-backed MMS transport.
 * Send remains disabled until the MMS delivery/download receivers, provider persistence,
 * SMS role qualification and carrier tests are complete.
 */
object MmsTransport {
  const val SEND_ENABLED = false

  fun sendPhoto(context: Context, address: String, caption: String, bitmap: Bitmap): Result<Unit> {
    if (!SmsRole.isDefault(context)) return Result.failure(IllegalStateException("Not default SMS handler"))
    if (!SEND_ENABLED) return Result.failure(IllegalStateException("MMS sending is not enabled"))
    if (address.isBlank() || bitmap.isRecycled) return Result.failure(IllegalArgumentException("Invalid MMS contents"))
    return runCatching {
      val settings = Settings().apply { setUseSystemSending(true) }
      val message = Message(caption, address).apply { setImage(bitmap) }
      Transaction(context, settings).sendNewMessage(message)
    }
  }
}
