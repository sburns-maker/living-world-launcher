package com.livingworld.launcher

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Native Android launcher, using installed app icons and Android's official launcher API. */
class LauncherActivity : Activity() {
  private val launcher by lazy { getSystemService(LauncherApps::class.java) }
  private val apps by lazy {
    try {
      launcher.getActivityList(null, Process.myUserHandle())
        .filter { it.componentName.packageName != packageName }
        .sortedBy { it.label.toString().lowercase(Locale.getDefault()) }
    } catch (_: Exception) { emptyList() }
  }
  override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); home() }
  override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); home() }
  override fun onResume() { super.onResume() }

  private fun world() = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://screen-to-same-studio.lovable.app")))
  private fun messages() = startActivity(Intent(this, UnifiedMessagesActivity::class.java))
  private fun contacts() = startActivity(Intent(this, UnifiedContactsActivity::class.java))
  private fun launch(app: LauncherActivityInfo) {
    try { launcher.startMainActivity(app.componentName, Process.myUserHandle(), null, null) }
    catch (_: Exception) { Toast.makeText(this, "Unable to launch app", Toast.LENGTH_SHORT).show() }
  }
  private fun background(root: View) {
    // Android may not expose the wallpaper drawable to every launcher/device; use a safe fallback.
    try {
      @Suppress("DEPRECATION")
      val wallpaper = WallpaperManager.getInstance(this).peekDrawable()
      if (wallpaper != null) { root.background = wallpaper; return }
    } catch (_: Exception) { }
    root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
      intArrayOf(Color.rgb(27, 49, 92), Color.rgb(82, 75, 131), Color.rgb(25, 41, 70)))
  }
  private fun label(text: String, size: Float, bold: Boolean = false): TextView =
    UiKit.text(this, text, size, Color.WHITE, bold)
  private fun icon(name: String, drawable: android.graphics.drawable.Drawable?, action: () -> Unit): LinearLayout {
    val tile = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
      setPadding(UiKit.dp(this@LauncherActivity, 2), UiKit.dp(this@LauncherActivity, 9),
        UiKit.dp(this@LauncherActivity, 2), UiKit.dp(this@LauncherActivity, 9))
      isClickable = true; isFocusable = true
      setOnClickListener { action() }
    }
    val face = FrameLayout(this)
    if (drawable != null) {
      face.addView(ImageView(this).apply { setImageDrawable(drawable); scaleType = ImageView.ScaleType.FIT_CENTER },
        FrameLayout.LayoutParams(UiKit.dp(this, 52), UiKit.dp(this, 52), Gravity.CENTER))
    } else {
      val circle = label(name.take(1), 25f, true).apply {
        gravity = Gravity.CENTER
        background = UiKit.surface(this@LauncherActivity, Color.rgb(112, 84, 193), 18)
      }
      face.addView(circle, FrameLayout.LayoutParams(UiKit.dp(this, 52), UiKit.dp(this, 52), Gravity.CENTER))
    }
    tile.addView(face, LinearLayout.LayoutParams(UiKit.dp(this, 58), UiKit.dp(this, 58)))
    tile.addView(label(name, 11f).apply {
      gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; maxLines = 2
      ellipsize = android.text.TextUtils.TruncateAt.END
      setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 35)))
    return tile
  }
  private fun screen(): LinearLayout {
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(UiKit.dp(this@LauncherActivity, 13), UiKit.dp(this@LauncherActivity, 16),
        UiKit.dp(this@LauncherActivity, 13), UiKit.dp(this@LauncherActivity, 12))
    }
    background(root)
    setContentView(root)
    window.statusBarColor = Color.rgb(28, 45, 78)
    window.navigationBarColor = Color.rgb(28, 45, 78)
    return root
  }
  private fun grid(items: List<Pair<String, LauncherActivityInfo?>>, customAction: (String) -> (() -> Unit)?): GridLayout {
    val grid = GridLayout(this).apply { columnCount = 4; alignmentMode = GridLayout.ALIGN_BOUNDS }
    val columns = 4
    val width = (resources.displayMetrics.widthPixels - UiKit.dp(this, 26)) / columns
    items.forEachIndexed { index, item ->
      val special = customAction(item.first)
      val action = special ?: { item.second?.let { launch(it) }; Unit }
      val tile = icon(item.first, item.second?.getIcon(0), action)
      grid.addView(tile, GridLayout.LayoutParams(GridLayout.spec(index / columns), GridLayout.spec(index % columns)).apply {
        this.width = width; height = UiKit.dp(this@LauncherActivity, 107)
      })
    }
    return grid
  }
  private fun findApp(vararg terms: String): LauncherActivityInfo? =
    apps.firstOrNull { item -> terms.any { t -> item.label.toString().equals(t, true) } }

  private fun home() {
    val root = screen()
    root.addView(label(SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date()), 16f))
    root.addView(label(SimpleDateFormat("h:mm", Locale.getDefault()).format(Date()), 51f, true))
    root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
    val favorites = listOf(
      "Living World" to null,
      "Chrome" to findApp("Chrome"),
      "Camera" to findApp("Camera"),
      "Settings" to findApp("Settings")
    )
    root.addView(grid(favorites) { key ->
      when (key) {
        "Living World" -> ({ world() })
        "Settings" -> ({ startActivity(Intent(Settings.ACTION_SETTINGS)) })
        else -> null
      }
    })
    root.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.15f))
    val dock = listOf(
      "Phone" to findApp("Phone"),
      "Messages" to null,
      "All apps" to null,
      "Contacts" to null
    )
    root.addView(grid(dock) { key ->
      when (key) {
        "Messages" -> ({ messages() })
        "All apps" -> ({ drawer() })
        "Contacts" -> ({ contacts() })
        else -> null
      }
    })
    root.setOnTouchListener(object : View.OnTouchListener {
      private var down = 0f
      override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> { down = event.y; return true }
          MotionEvent.ACTION_UP -> { if (down - event.y > UiKit.dp(this@LauncherActivity, 65)) { drawer(); return true } }
        }
        return true
      }
    })
  }
  private fun drawer() {
    val root = screen()
    root.addView(label("‹  Home", 16f).apply {
      setPadding(8, 4, 8, 12); setOnClickListener { home() }
    })
    val search = EditText(this).apply {
      hint = "Search apps"; isSingleLine = true; setSingleLine(true)
      setPadding(UiKit.dp(this@LauncherActivity, 16), UiKit.dp(this@LauncherActivity, 12),
        UiKit.dp(this@LauncherActivity, 16), UiKit.dp(this@LauncherActivity, 12))
      background = UiKit.surface(this@LauncherActivity, Color.WHITE)
    }
    root.addView(search)
    val scroll = ScrollView(this).apply { isFillViewport = true }
    root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    scroll.addView(holder)
    fun render(query: String) {
      holder.removeAllViews()
      val filtered = apps.filter { it.label.toString().contains(query, true) }
      val all = listOf("Living World" to null, "Messages" to null, "Contacts" to null) +
        filtered.map { it.label.toString() to it }
      holder.addView(grid(all) { key ->
        when (key) {
          "Living World" -> ({ world() })
          "Messages" -> ({ messages() })
          "Contacts" -> ({ contacts() })
          else -> null
        }
      })
    }
    render("")
    search.addTextChangedListener(object : android.text.TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString() ?: "") }
      override fun afterTextChanged(s: android.text.Editable?) {}
    })
  }
}
