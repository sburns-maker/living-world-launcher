package com.livingworld.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object UiKit {
  val ink = Color.rgb(33, 35, 46)
  val muted = Color.rgb(112, 116, 132)
  val lavender = Color.rgb(116, 87, 195)
  val bg = Color.rgb(248, 247, 252)
  fun dp(context: Context, number: Int) = (number * context.resources.displayMetrics.density + .5f).toInt()
  fun surface(context: Context, color: Int = Color.WHITE, radius: Int = 22): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = dp(context, radius).toFloat() }
  fun text(context: Context, value: String, size: Float, color: Int = ink, bold: Boolean = false): TextView =
    TextView(context).apply {
      text = value; textSize = size; setTextColor(color)
      if (bold) setTypeface(null, Typeface.BOLD)
      gravity = Gravity.CENTER_VERTICAL
    }
  fun vertical(context: Context, padding: Int = 0): LinearLayout =
    LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(context, padding), dp(context, padding), dp(context, padding), dp(context, padding))
    }
  fun horizontal(context: Context): LinearLayout =
    LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
  fun gap(context: Context, height: Int): View = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(context,height)) }
  fun clickable(context: Context, title: String, onClick: () -> Unit): TextView =
    text(context, title, 15f, ink, true).apply {
      setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14))
      background = surface(context)
      setOnClickListener { onClick() }
      isClickable = true; isFocusable = true
    }
}
