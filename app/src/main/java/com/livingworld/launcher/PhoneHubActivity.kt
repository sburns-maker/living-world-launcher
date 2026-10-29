package com.livingworld.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Permissionless native dial pad: delegates placing a call to the user's real
 * phone app via ACTION_DIAL. Never requests CALL_PHONE or reads private call logs.
 */
class PhoneHubActivity : Activity() {
  private val digits = StringBuilder()
  private lateinit var number: TextView
  private fun dp(value: Int) = UiKit.dp(this, value)
  private fun padButton(label: String, sub: String = "", action: () -> Unit): LinearLayout {
    val cell = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(dp(4), dp(8), dp(4), dp(8))
      background = UiKit.surface(this@PhoneHubActivity, Color.WHITE, 22)
      elevation = dp(1).toFloat()
      isClickable = true
      isFocusable = true
      contentDescription = if (sub.isBlank()) label else "$label $sub"
      setOnClickListener { action() }
    }
    cell.addView(UiKit.text(this, label, 30f, UiKit.ink, true).apply {
      gravity = Gravity.CENTER
    })
    if (sub.isNotBlank()) {
      cell.addView(UiKit.text(this, sub, 11f, UiKit.muted).apply {
        gravity = Gravity.CENTER
      })
    }
    return cell
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = UiKit.bg
    window.navigationBarColor = UiKit.bg
    window.decorView.systemUiVisibility =
      View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    val root = UiKit.vertical(this, 18).apply {
      setBackgroundColor(UiKit.bg)
      fitsSystemWindows = true
    }
    setContentView(root)
    root.addView(UiKit.clickable(this, "‹  Home") { finish() })
    root.addView(UiKit.gap(this, 12))
    root.addView(UiKit.text(this, "Phone", 32f, UiKit.ink, true))
    root.addView(UiKit.text(this, "Dial using your regular Android phone service",
      13f, UiKit.muted))
    root.addView(View(this), LinearLayout.LayoutParams(1, 0, .6f))
    number = UiKit.text(this, "", 30f, UiKit.ink, true).apply {
      gravity = Gravity.CENTER
      minHeight = dp(60)
      setTextIsSelectable(true)
    }
    root.addView(number)
    root.addView(UiKit.gap(this, 14))
    val keys = listOf(
      "1" to "", "2" to "ABC", "3" to "DEF",
      "4" to "GHI", "5" to "JKL", "6" to "MNO",
      "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
      "*" to "", "0" to "+", "#" to ""
    )
    val grid = GridLayout(this).apply { columnCount = 3 }
    val width = (resources.displayMetrics.widthPixels - dp(36)) / 3
    keys.forEachIndexed { index, item ->
      val button = padButton(item.first, item.second) {
        if (digits.length < 40) digits.append(item.first)
        refresh()
      }
      if (item.first == "0") {
        button.setOnLongClickListener {
          if (digits.length < 40) digits.append("+")
          refresh()
          true
        }
      }
      grid.addView(button,
        GridLayout.LayoutParams(GridLayout.spec(index / 3), GridLayout.spec(index % 3)).apply {
          this.width = width
          height = dp(76)
          setMargins(dp(3), dp(3), dp(3), dp(3))
        })
    }
    root.addView(grid)
    root.addView(UiKit.gap(this, 14))
    val actions = UiKit.horizontal(this)
    actions.addView(UiKit.clickable(this, "Contacts") {
      startActivity(Intent(this, UnifiedContactsActivity::class.java))
    }, LinearLayout.LayoutParams(0, -2, 1f))
    actions.addView(UiKit.clickable(this, "⌫") {
      if (digits.isNotEmpty()) digits.deleteCharAt(digits.length - 1)
      refresh()
    }.apply {
      setOnLongClickListener { digits.clear(); refresh(); true }
    }, LinearLayout.LayoutParams(0, -2, .5f))
    root.addView(actions)
    root.addView(UiKit.gap(this, 10))
    root.addView(UiKit.clickable(this, "Call with Phone   ↗") { dial() }.apply {
      gravity = Gravity.CENTER
      setTextColor(Color.WHITE)
      background = UiKit.surface(this@PhoneHubActivity, Color.rgb(24, 148, 120), 28)
      contentDescription = "Open system Phone app to dial this number"
    })
    root.addView(View(this), LinearLayout.LayoutParams(1, 0, .4f))
    root.addView(UiKit.text(this,
      "The system Phone app handles real calls, SIM selection, history, voicemail and emergencies.",
      12f, UiKit.muted))
    refresh()
  }

  private fun refresh() {
    number.text = if (digits.isEmpty()) "Enter a number" else digits.toString()
  }
  private fun dial() {
    if (digits.isEmpty()) {
      // Gives access to system recents, voicemail and speed dial too.
      try { startActivity(Intent(Intent.ACTION_DIAL)) }
      catch (_: Exception) {
        Toast.makeText(this, "No phone app available", Toast.LENGTH_SHORT).show()
      }
      return
    }
    val input = digits.toString()
    if (!input.matches(Regex("^[+*#0-9]{1,40}$"))) return
    try {
      startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", input, null)))
    } catch (_: Exception) {
      Toast.makeText(this, "No phone app available", Toast.LENGTH_SHORT).show()
    }
  }
}
