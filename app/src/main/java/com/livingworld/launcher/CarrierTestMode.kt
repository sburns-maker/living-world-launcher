package com.livingworld.launcher

import android.content.Context

/** Manual, time-limited opt-in for carrier SEND testing, not an everyday release switch. */
object CarrierTestMode {
  private const val PREFS = "carrier_test_settings"
  private const val EXPIRES = "expires"
  private const val MAX_SESSION = 2 * 60 * 60 * 1000L

  fun enabled(context: Context): Boolean =
    SmsRole.isDefault(context) &&
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong(EXPIRES, 0L) > System.currentTimeMillis()

  fun start(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putLong(EXPIRES, System.currentTimeMillis() + MAX_SESSION).commit()
  }

  fun stop(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .remove(EXPIRES).commit()
  }
}
