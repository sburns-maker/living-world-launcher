package com.livingworld.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.View
import android.widget.*
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local launcher prototype: no installed-app inventory is sent to any server. */
class LauncherActivity : Activity() {
  private lateinit var root: LinearLayout
  private val bg = Color.rgb(242, 244, 249)
  private val textColor = Color.rgb(28, 38, 57)

  override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); home() }
  override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); home() }

  private fun base(): LinearLayout {
    val v = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
      setPadding(28, 42, 28, 24)
    }
    root = v
    setContentView(ScrollView(this).apply { fillViewport = true; addView(v) })
    return v
  }
  private fun label(s: String, sp: Float, bold: Boolean = false): TextView = TextView(this).apply {
    text = s; textSize = sp; setTextColor(textColor)
    if (bold) setTypeface(null, Typeface.BOLD)
    setPadding(0, 12, 0, 12)
  }
  private fun action(name: String, run: () -> Unit) = Button(this).apply { text = name; setOnClickListener { run() } }
  private fun home() {
    val v = base()
    v.addView(label(SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()), 18f))
    v.addView(label(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()), 47f, true))
    v.addView(label("Living World", 26f, true))
    v.addView(label("Your fictional world and your real apps, one home screen.", 15f))
    v.addView(action("Open Living World") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://screen-to-same-studio.lovable.app"))) })
    v.addView(action("All apps  ↑") { drawer() })
    v.addView(action("Android home settings") { startActivity(Intent("android.settings.HOME_SETTINGS")) })
  }
  private fun drawer() {
    val v = base()
    v.addView(action("← Home") { home() })
    v.addView(label("All apps", 28f, true))
    val filter = EditText(this).apply { hint = "Search apps"; singleLine = true }
    v.addView(filter)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    v.addView(list)
    val launcher = getSystemService(LauncherApps::class.java)
    val apps = launcher.getActivityList(null, Process.myUserHandle())
      .filter { it.componentName.packageName != packageName }
      .sortedBy { it.label.toString().lowercase(Locale.getDefault()) }
    fun update(query: String) {
      list.removeAllViews()
      for (app in apps.filter { it.label.toString().contains(query, ignoreCase = true) }) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = ImageView(this).apply { setImageDrawable(app.getIcon(0)) }
        row.addView(icon, LinearLayout.LayoutParams(58, 58))
        val button = action(app.label.toString()) {
          try { launcher.startMainActivity(app.componentName, Process.myUserHandle(), null, null) }
          catch (_: Exception) { Toast.makeText(this, "Unable to open app", Toast.LENGTH_SHORT).show() }
        }
        row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        list.addView(row)
      }
    }
    update("")
    filter.addTextChangedListener(object : android.text.TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, st: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) { update(s?.toString() ?: "") }
      override fun afterTextChanged(s: android.text.Editable?) {}
    })
  }
}
