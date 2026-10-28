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
import android.widget.ScrollView
import android.widget.Toast

/**
 * Explicit, reversible QA entry point. Never requests the SMS role on launch.
 * Production users should continue using their existing SMS app.
 */
class CarrierTestActivity : Activity() {
  private lateinit var status: TextView
  private lateinit var sendResults: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = UiKit.vertical(this, 18).apply { setBackgroundColor(UiKit.bg) }
    val scroll = ScrollView(this)
    scroll.addView(root)
    setContentView(scroll)
    root.addView(UiKit.clickable(this, "‹  Messages") { finish() })
    root.addView(UiKit.text(this, "Carrier messaging test", 25f, UiKit.ink, true))
    root.addView(UiKit.text(this,
      "Samsung Galaxy Z Fold6 / T-Mobile test build. Back up messages first. Switching the default SMS app redirects incoming SMS and MMS to this experimental app.", 15f, UiKit.ink))
    status = UiKit.text(this, "", 15f, UiKit.ink)
    root.addView(status)
    root.addView(UiKit.clickable(this, "Refresh SMS/MMS carrier results") { refresh() })
    root.addView(UiKit.clickable(this, "Grant MMS Phone and notification permissions") {
      askPermissions()
    })
    root.addView(UiKit.clickable(this, "Send test notification") {
      SmsNotification.preview(this)
      refresh()
    })
    root.addView(UiKit.clickable(this, "Open notification settings") {
      startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    })
    sendResults = UiKit.text(this, "", 14f, UiKit.ink)
    root.addView(sendResults)
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
    val detail = SmsRole.inspect(this)
    val role = if (detail.roleHeld) "Living World (verified by Android)" else "Not held"
    val provider = when (detail.telephonyDefault) {
      null -> "not visible"
      detail.ownPackage -> "Living World"
      else -> "another messaging app"
    }
    val sending = if (CarrierTestMode.enabled(this)) "enabled" else "off"
    val smsPermission = if (checkSelfPermission(Manifest.permission.SEND_SMS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED) "granted" else "missing"
    status.text = "Android SMS role: $role\nProvider default: $provider\nSEND_SMS permission: $smsPermission\nOutgoing test session: $sending\nPending MMS recovery entries: ${MmsRetryStore.pendingCount(this)}"
    val phonePermission = if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED) "granted" else "missing"
    status.append("\nMMS Phone access: " + phonePermission +
      "\nNotifications: " + SmsNotification.permissionStatus(this))
    if (::sendResults.isInitialized) sendResults.text = SmsCarrierDiagnostics.summary(this) + "\n\n" + MmsSendLedger.recent(this)

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
      requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
        Manifest.permission.RECEIVE_MMS, Manifest.permission.SEND_SMS,
        Manifest.permission.POST_NOTIFICATIONS), 405)
    } else {
      requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
        Manifest.permission.RECEIVE_MMS, Manifest.permission.SEND_SMS), 405)
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int, permissions: Array<out String>, grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == 405) refresh()
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
