package com.livingworld.launcher

import android.app.IntentService
import android.content.Intent

/** Placeholder for Android's role-required response service. Disabled in manifest. */
@Suppress("DEPRECATION")
class SmsRespondService : IntentService("SmsRespondService") {
  override fun onHandleIntent(intent: Intent?) {
    // Carrier messaging remains disabled until role and phone tests pass.
  }
}
