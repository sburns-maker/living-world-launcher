package com.livingworld.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Opt-in Android Home replacement. Real apps always launch through Android. */
class LauncherActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); home() }
  override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); home() }
  private fun startWorld() {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://screen-to-same-studio.lovable.app")))
  }
  private fun screen(): LinearLayout {
    val root = UiKit.vertical(this, 22).apply { setBackgroundColor(UiKit.bg) }
    setContentView(ScrollView(this).apply { fillViewport = true; addView(root) })
    return root
  }
  private fun home() {
    val root = screen()
    root.addView(UiKit.gap(this, 35))
    root.addView(UiKit.text(this, SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()), 17f, UiKit.muted))
    root.addView(UiKit.text(this, SimpleDateFormat("h:mm", Locale.getDefault()).format(Date()), 59f, UiKit.ink, true))
    root.addView(UiKit.gap(this, 35))
    val feature = UiKit.vertical(this, 19).apply { background = UiKit.surface(this@LauncherActivity, Color.rgb(237, 232, 252)) }
    feature.addView(UiKit.text(this, "Living World", 22f, UiKit.ink, true))
    feature.addView(UiKit.text(this, "Your world, woven into your phone.", 14f, UiKit.muted))
    feature.addView(UiKit.gap(this, 10))
    feature.addView(UiKit.clickable(this, "Open your world  ›") { startWorld() })
    root.addView(feature)
    root.addView(UiKit.gap(this, 22))
    root.addView(UiKit.text(this, "Your apps", 19f, UiKit.ink, true))
    root.addView(UiKit.gap(this, 12))
    root.addView(UiKit.clickable(this, "Messages     ●") { startActivity(Intent(this, UnifiedMessagesActivity::class.java)) })
    root.addView(UiKit.gap(this, 9))
    root.addView(UiKit.clickable(this, "All installed apps     ↑") { drawer() })
    root.addView(UiKit.gap(this, 9))
    root.addView(UiKit.clickable(this, "Default home settings") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) })
  }
  private fun drawer() {
    val root = screen()
    root.addView(UiKit.clickable(this, "‹  Home") { home() })
    root.addView(UiKit.gap(this, 20))
    root.addView(UiKit.text(this, "All apps", 30f, UiKit.ink, true))
    val search = EditText(this).apply {
      hint = "Search apps"; singleLine = true; setPadding(UiKit.dp(this@LauncherActivity, 12), 8, 8, 8)
      background = UiKit.surface(this@LauncherActivity)
    }
    root.addView(search)
    root.addView(UiKit.gap(this, 14))
    val list = UiKit.vertical(this)
    root.addView(list)
    val service = getSystemService(LauncherApps::class.java)
    val apps = try {
      service.getActivityList(null, Process.myUserHandle())
        .filter { it.componentName.packageName != packageName }
        .sortedBy { it.label.toString().lowercase(Locale.getDefault()) }
    } catch (_: Exception) { emptyList() }
    fun render(query: String) {
      list.removeAllViews()
      for (app in apps.filter { it.label.toString().contains(query, ignoreCase = true) }) {
        val row = UiKit.horizontal(this)
        val image = ImageView(this).apply { setImageDrawable(app.getIcon(0)) }
        row.addView(image, LinearLayout.LayoutParams(UiKit.dp(this, 42), UiKit.dp(this, 42)))
        val title = UiKit.text(this, app.label.toString(), 16f).apply {
          setPadding(UiKit.dp(this@LauncherActivity, 15), UiKit.dp(this@LauncherActivity, 13), 0, UiKit.dp(this@LauncherActivity, 13))
          setOnClickListener {
            try { service.startMainActivity(app.componentName, Process.myUserHandle(), null, null) }
            catch (_: Exception) { Toast.makeText(this@LauncherActivity, "App unavailable", Toast.LENGTH_SHORT).show() }
          }
        }
        row.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { title.performClick() }
        list.addView(row)
        list.addView(UiKit.gap(this, 7))
      }
      if (list.childCount == 0) list.addView(UiKit.text(this, "No matching apps", 15f, UiKit.muted))
    }
    render("")
    search.addTextChangedListener(object : android.text.TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString() ?: "") }
      override fun afterTextChanged(s: android.text.Editable?) {}
    })
  }
}
