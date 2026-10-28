package com.livingworld.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Explicit, reversible QA entry point. Never requests the SMS role on launch.
 * Production users should continue using their existing SMS app.
 */
class CarrierTestActivity : Activity() {
  private lateinit var status: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = UiKit.vertical(this, 18).apply { setBackgroundColor(UiKit.bg) }
    setContentView(root)
    root.addView(UiKit.clickable(this, "‹  Messages") { finish() })
    root.addView(UiKit.text(this, "Carrier messaging test", 25f, UiKit.ink, true))
    root.addView(UiKit.text(this,
      "Samsung Galaxy Z Fold6 / T-Mobile test build. Back up messages first. Switching the default SMS app redirects incoming SMS and MMS to this experimental app.", 15f, UiKit.ink))
    status = UiKit.text(this, "", 15f, UiKit.ink)
    root.addView(status)
    root.addView(UiKit.clickable(this, "1. Start controlled SMS/MMS test") { confirmRole() })
    root.addView(UiKit.clickable(this, "2. New test message") {
      startActivity(Intent(this, SmsComposeActivity::class.java))
    })
    root.addView(UiKit.clickable(this, "Stop outgoing test sends") {
      CarrierTestMode.stop(this)
      refresh()
    })
    root.addView(UiKit.clickable(this, "Restore Google Messages (default apps settings)") {
      CarrierTestMode.stop(this)
      startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    })
    root.addView(UiKit.text(this,
      "Keep this screen available during testing. A two-hour timer restricts new sends, but incoming messages still require a working SMS default app. To finish the test, choose Google Messages again in Settings → Apps → Choose default apps → SMS app.", 13f, UiKit.muted))
    refresh()
  }

  override fun onResume() {
    super.onResume()
    if (::status.isInitialized) refresh()
  }

  private fun refresh() {
    val role = if (SmsRole.isDefault(this)) "Living World (test)" else "Google Messages or another app"
    val sending = if (CarrierTestMode.enabled(this)) "enabled" else "off"
    status.text = "Default SMS role: $role\nOutgoing test session: $sending\nPending MMS recovery entries: ${MmsRetryStore.pendingCount(this)}"

  }

  private fun confirmRole() {
    AlertDialog.Builder(this)
      .setTitle("Controlled carrier test only")
      .setMessage("Have you backed up your SMS/MMS messages and arranged another phone to send a test text and picture? While Living World is default, any messages it fails to handle may be lost. Return to Google Messages immediately after the test.")
      .setNegativeButton("Not yet", null)
      .setPositiveButton("Begin test") { _, _ -> requestRole() }
      .show()
  }

  private fun requestRole() {
    if (SmsRole.isDefault(this)) {
      CarrierTestMode.start(this)
      askPermissions()
      refresh()
      return
    }
    if (Build.VERSION.SDK_INT >= 29) {
      val roles = getSystemService(RoleManager::class.java)
      if (roles?.isRoleAvailable(RoleManager.ROLE_SMS) == true)
        startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_SMS), 404)
      else Toast.makeText(this, "SMS role unavailable on this device", Toast.LENGTH_LONG).show()
    } else {
      Toast.makeText(this, "This tester requires Android 10 or later", Toast.LENGTH_LONG).show()
    }
  }

  private fun askPermissions() {
    if (Build.VERSION.SDK_INT >= 33) {
      requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
        Manifest.permission.RECEIVE_MMS, Manifest.permission.SEND_SMS,
        Manifest.permission.POST_NOTIFICATIONS), 405)
    } else {
      requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
        Manifest.permission.RECEIVE_MMS, Manifest.permission.SEND_SMS), 405)
    }
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == 404 && SmsRole.isDefault(this)) {
      CarrierTestMode.start(this)
      askPermissions()
    }
    refresh()
  }
}
