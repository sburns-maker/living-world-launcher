package com.livingworld.launcher

import android.app.IntentService
import android.content.Intent
import android.util.Log

/**
 * Role-required respond-via-message handler. Sends only within an explicitly
 * enabled test session; never silently bypasses the manual QA send gate.
 */
@Suppress("DEPRECATION")
class SmsRespondService : IntentService("SmsRespondService") {
  override fun onHandleIntent(intent: Intent?) {
    if (intent?.action != "android.intent.action.RESPOND_VIA_MESSAGE") return
    if (!CarrierTestMode.enabled(this)) {
      Log.w("SmsRespondService", "Call response blocked outside test session")
      return
    }
    val address = intent.data?.schemeSpecificPart ?: return
    val body = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
    if (body.isBlank() || address.isBlank()) return
    val result = SmsTransport.send(this, address, body)
    if (result is SmsTransport.Validation.Blocked)
      Log.w("SmsRespondService", "Carrier call response not accepted")
  }
}
