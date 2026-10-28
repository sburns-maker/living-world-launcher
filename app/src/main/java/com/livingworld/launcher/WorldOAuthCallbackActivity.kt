package com.livingworld.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Explicitly handles the external Chrome OAuth return. Only encrypted data
 * reaches this exported activity. Validation/decryption is off the UI thread.
 */
class WorldOAuthCallbackActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val uri = intent?.data
    if (uri == null) {
      Toast.makeText(this, "Missing Google sign-in response", Toast.LENGTH_LONG).show()
      finish()
      return
    }
    val pending = uri
    val context = applicationContext
    executor.execute {
      val result = runCatching { WorldOAuthHandoff.accept(context, pending) }
      runOnUiThread {
        if (result.isSuccess) {
          WorldOAuthHandoff.publish(result.getOrThrow())
          startActivity(Intent(this, WorldInboxActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } else {
          Toast.makeText(this,
            result.exceptionOrNull()?.message ?: "Google sign-in could not be completed",
            Toast.LENGTH_LONG).show()
          startActivity(Intent(this, WorldInboxActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
      }
    }
  }

  companion object {
    private val executor = Executors.newSingleThreadExecutor()
  }
}
