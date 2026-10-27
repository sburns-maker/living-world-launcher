package com.livingworld.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.view.Gravity
import android.widget.*

/** First-pass unified inbox shell. No SMS permissions or private-message reads. */
class UnifiedMessagesActivity : Activity() {
  private val purple = Color.rgb(121, 87, 202)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(22, 35, 22, 22)
      setBackgroundColor(Color.rgb(249, 249, 252))
    }
    val scroll = ScrollView(this).apply { addView(layout) }
    setContentView(scroll)
    fun title(text: String, size: Float, tint: Int = Color.BLACK): TextView =
      TextView(this).apply { this.text = text; textSize = size; setTextColor(tint); setPadding(6, 13, 6, 13) }
    layout.addView(title("Messages", 32f))
    layout.addView(title("Your conversations • real and fictional", 14f, Color.DKGRAY))
    val search = EditText(this).apply { hint = "Search conversations"; singleLine = true }
    layout.addView(search)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    layout.addView(list)
    data class Thread(val name: String, val preview: String, val fictional: Boolean)
    // Example contacts only, not actual imported texts or contacts.
    val examples = listOf(
      Thread("Riley", "Example · Want to talk later?", true),
      Thread("Claire", "Example · Catch you tomorrow!", true),
      Thread("Tess", "Example · I was thinking about that.", true)
    )
    fun render(filter: String) {
      list.removeAllViews()
      examples.filter { it.name.contains(filter, true) }.forEach { t ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dot = TextView(this).apply {
          text = "●"; textSize = 23f
          setTextColor(if (t.fictional) purple else Color.GRAY)
          setPadding(8, 8, 18, 8)
        }
        row.addView(dot)
        val button = Button(this).apply {
          text = "${t.name}\n${t.preview}"
          isAllCaps = false
          setOnClickListener { openLivingWorld() }
        }
        row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        list.addView(row)
      }
    }
    render("")
    search.addTextChangedListener(object : android.text.TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString() ?: "") }
      override fun afterTextChanged(s: android.text.Editable?) {}
    })
    layout.addView(Button(this).apply {
      text = "Open real Google Messages"
      setOnClickListener {
        val launch = packageManager.getLaunchIntentForPackage("com.google.android.apps.messaging")
        if (launch != null) startActivity(launch)
        else startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING))
      }
    })
    layout.addView(title("Real SMS/MMS is NOT imported yet. This screen does not request SMS access, change your default texting app, or send texts.", 13f, Color.DKGRAY))
    layout.addView(Button(this).apply { text = "← Home"; setOnClickListener { finish() } })
  }

  private fun openLivingWorld() {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://screen-to-same-studio.lovable.app")))
  }
}
