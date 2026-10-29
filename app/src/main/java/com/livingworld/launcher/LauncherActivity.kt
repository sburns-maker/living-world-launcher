package com.livingworld.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.TextClock
import android.view.ViewGroup
import android.widget.Button
import java.io.File
import java.io.FileOutputStream
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native Android HOME shell. Installed applications are launched using LauncherApps,
 * without QUERY_ALL_PACKAGES, SMS default role, or hidden Samsung APIs.
 */
class LauncherActivity : Activity() {
  private enum class Page { HOME, APPS, MORE }
  private data class Shortcut(val id: String, val label: String, val icon: Drawable?, val color: Int)
  private val launcher by lazy { getSystemService(LauncherApps::class.java) }
  private val prefs by lazy { getSharedPreferences("living_world_home_v1", MODE_PRIVATE) }
  private val handler = Handler(Looper.getMainLooper())
  private val widgets by lazy { HomeWidgets(this, prefs) }
  private var homePage = 0
  private var drawerIndex = 0
  private var drawerScroller: HorizontalScrollView? = null
  private var drawerPages = 0
  private var drawerDots: TextView? = null
  private var page = Page.HOME
  private var clock: TextView? = null
  private var date: TextView? = null
  private var filter = ""
  private val REQUEST_WALLPAPER = 7401
  private val refreshClock = object : Runnable {
    override fun run() {
      clock?.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
      date?.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
      handler.postDelayed(this, 30_000L)
    }
  }

  private fun pageCount() = prefs.getInt("home_page_count", 1).coerceIn(1, 6)
  private fun pinKey(page: Int, slot: Int) =
    if (page == 0) "pinned_" + slot else "pinned_page_" + page + "_" + slot
  private fun pageSlots(page: Int) = if (page == 0) 4 else 8
  private fun rememberPage() {
    prefs.edit().putInt("current_home_page", homePage).apply()
  }

  private fun dp(n: Int) = UiKit.dp(this, n)
  private fun tint(r: Int, g: Int, b: Int, a: Int = 255) = Color.argb(a, r, g, b)
  private fun text(s: String, size: Float, bold: Boolean = false,
    color: Int = Color.WHITE): TextView = TextView(this).apply {
    this.text = s
    textSize = size
    setTextColor(color)
    if (bold) setTypeface(null, Typeface.BOLD)
    gravity = Gravity.CENTER_VERTICAL
  }
  private fun surface(color: Int = tint(24, 32, 57, 225), radius: Int = 23) =
    GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
  private fun cols() = when {
    resources.configuration.screenWidthDp >= 600 -> 6
    resources.configuration.screenWidthDp >= 440 -> 5
    else -> 4
  }
  private fun width() = dp(resources.configuration.screenWidthDp.coerceAtLeast(300) - 32)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
      WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    filter = savedInstanceState?.getString("drawerFilter").orEmpty()
    homePage = savedInstanceState?.getInt("homePage")
      ?: prefs.getInt("current_home_page", 0)
    homePage = homePage.coerceIn(0, pageCount() - 1)
    drawerIndex = savedInstanceState?.getInt("drawerIndex") ?: 0
    when (savedInstanceState?.getString("page")) {
      "APPS" -> showApps(false)
      "MORE" -> showMore()
      else -> showHome()
    }
  }
  override fun onSaveInstanceState(outState: Bundle) {
    outState.putString("page", page.name)
    outState.putString("drawerFilter", filter)
    outState.putInt("homePage", homePage)
    outState.putInt("drawerIndex", drawerIndex)
    super.onSaveInstanceState(outState)
  }
  override fun onStart() {
    super.onStart()
    widgets.start()
  }
  override fun onStop() {
    widgets.stop()
    super.onStop()
  }
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == HomeWidgets.REQUEST_BIND ||
        requestCode == HomeWidgets.REQUEST_CONFIGURE) {
      widgets.onResult(requestCode, resultCode, data)
      showHome()
    } else if (requestCode == REQUEST_WALLPAPER && resultCode == RESULT_OK) {
      val uri = data?.data ?: return
      importWallpaper(uri)
    }
  }
  override fun onResume() {
    super.onResume()
    handler.removeCallbacks(refreshClock)
    handler.post(refreshClock)
  }
  override fun onPause() {
    handler.removeCallbacks(refreshClock)
    super.onPause()
  }
  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    hideKeyboard()
    homePage = 0
    showHome()
  }
  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (page != Page.HOME) showHome() else super.onBackPressed()
  }

  private fun installed() = try {
    launcher.getActivityList(null, Process.myUserHandle())
      .filter { it.componentName.packageName != packageName }
      .sortedBy { it.label.toString().lowercase(Locale.getDefault()) }
  } catch (_: Exception) { emptyList<LauncherActivityInfo>() }

  private fun systemApp(app: LauncherActivityInfo) =
    Shortcut("app:" + app.componentName.flattenToString(), app.label.toString(),
      runCatching { app.getIcon(0) }.getOrNull(), tint(61, 73, 108))
  private fun builtIn(name: String): Shortcut = when (name) {
    "world" -> Shortcut(name, "Living World", getDrawable(R.drawable.ic_world), tint(112, 82, 190))
    "phone" -> Shortcut(name, "Phone", getDrawable(R.drawable.ic_phone), tint(24, 150, 126))
    "messages" -> Shortcut(name, "Messages", getDrawable(R.drawable.ic_messages), tint(41, 121, 218))
    "contacts" -> Shortcut(name, "Contacts", getDrawable(R.drawable.ic_contacts), tint(67, 122, 202))
    "apps" -> Shortcut(name, "Apps", getDrawable(R.drawable.ic_apps), tint(102, 88, 151))
    "more" -> Shortcut(name, "More", getDrawable(R.drawable.ic_more), tint(96, 101, 120))
    "empty" -> Shortcut(name, "+ Add", null, tint(57, 63, 92, 180))
    else -> Shortcut("settings", "Settings", getDrawable(R.drawable.ic_settings), tint(88, 99, 126))
  }
  private fun resolve(id: String, list: List<LauncherActivityInfo>): Shortcut {
    if (!id.startsWith("app:")) return builtIn(id)
    return list.firstOrNull {
      it.componentName.flattenToString() == id.removePrefix("app:")
    }?.let(::systemApp) ?: builtIn("apps")
  }
  private fun open(item: Shortcut) {
    when (item.id) {
      "world" -> try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://screen-to-same-studio.lovable.app")))
      } catch (_: Exception) { toast("No browser available") }
      "messages" -> startActivity(Intent(this, WorldInboxActivity::class.java))
      "phone" -> startActivity(Intent(this, PhoneHubActivity::class.java))
      "contacts" -> startActivity(Intent(this, UnifiedContactsActivity::class.java))
      "apps" -> showApps(false)
      "more" -> showMore()
      "empty" -> pickPinned(0)
      "settings" -> startActivity(Intent(Settings.ACTION_SETTINGS))
      else -> {
        val app = installed().firstOrNull {
          it.componentName.flattenToString() == item.id.removePrefix("app:")
        }
        if (app == null) { toast("App is no longer installed"); return }
        try {
          launcher.startMainActivity(app.componentName, Process.myUserHandle(), null, null)
        } catch (_: Exception) { toast("Couldn't open " + app.label) }
      }
    }
  }
  private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
  private fun keyboard(): InputMethodManager? = getSystemService(InputMethodManager::class.java)
  private fun hideKeyboard() {
    currentFocus?.let { keyboard()?.hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
  }

  private inner class SwipeHomeLayout : FrameLayout(this@LauncherActivity) {
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
      if (page != Page.HOME) return super.onInterceptTouchEvent(event)
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          startX = event.x
          startY = event.y
          tracking = true
        }
        MotionEvent.ACTION_MOVE -> if (tracking) {
          val horizontal = kotlin.math.abs(event.x - startX)
          val vertical = kotlin.math.abs(event.y - startY)
          if (pageCount() > 1 && horizontal > dp(50) &&
              horizontal > vertical * 1.5f) return true
          val rise = startY - event.y
          // Do not steal vertical scrolling from native widgets.
          if (rise > dp(70) && horizontal < dp(25) && homePage == 0 &&
              widgets.count(homePage) == 0) return true
        }
        MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> tracking = false
      }
      return super.onInterceptTouchEvent(event)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
      if (page != Page.HOME) return super.onTouchEvent(event)
      if (event.actionMasked == MotionEvent.ACTION_UP) {
        val dx = event.x - startX
        val dy = event.y - startY
        tracking = false
        if (kotlin.math.abs(dx) > dp(55) &&
            kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.4f) {
          jumpPage(homePage + if (dx < 0f) 1 else -1)
          return true
        }
        if (-dy > dp(70) && homePage == 0 && widgets.count(homePage) == 0) {
          showApps(false)
          return true
        }
      }
      return true
    }
  }

  private fun wallpaperFile() = File(filesDir, "home_wallpaper.jpg")
  private fun changeWallpaper() {
    val choices = arrayOf("Choose photo for Living World Home", "Use phone's wallpaper",
      "Blue and purple gradient", "Midnight gradient", "Ocean gradient")
    AlertDialog.Builder(this).setTitle("Home wallpaper")
      .setItems(choices) { _, index ->
        when (index) {
          0 -> try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
              type = "image/*"
              addCategory(Intent.CATEGORY_OPENABLE)
              addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, REQUEST_WALLPAPER)
          } catch (_: Exception) { toast("Photo picker unavailable") }
          1 -> {
            wallpaperFile().delete()
            prefs.edit().putInt("wallpaper_mode", 0).apply()
            showHome()
          }
          else -> {
            wallpaperFile().delete()
            prefs.edit().putInt("wallpaper_mode", index).apply()
            showHome()
          }
        }
      }.setNegativeButton("Cancel", null).show()
  }
  private fun importWallpaper(uri: Uri) {
    try {
      // Decode to a bounded size, rather than holding a camera-size bitmap.
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw Exception("Invalid image")
      var sample = 1
      while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 2400) sample *= 2
      val options = BitmapFactory.Options().apply { inSampleSize = sample }
      val image = contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
      } ?: throw Exception("Could not read image")
      FileOutputStream(wallpaperFile()).use { image.compress(Bitmap.CompressFormat.JPEG, 86, it) }
      image.recycle()
      prefs.edit().putInt("wallpaper_mode", 5).apply()
      if (page == Page.HOME) showHome()
    } catch (_: Exception) { toast("Couldn't load the wallpaper image") }
  }
  private fun paintWallpaper(view: View) {
    val custom = wallpaperFile()
    if (custom.isFile) {
      try {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val img = BitmapFactory.decodeFile(custom.absolutePath, opts)
        if (img != null) {
          view.background = BitmapDrawable(resources, img)
          return
        }
      } catch (_: Exception) { }
    }
    val mode = prefs.getInt("wallpaper_mode", 0)
    if (mode == 0) {
      try {
        @Suppress("DEPRECATION")
        val drawable = WallpaperManager.getInstance(this).peekDrawable()
        if (drawable != null) { view.background = drawable; return }
      } catch (_: Exception) { }
    }
    val colors = when (mode) {
      2 -> intArrayOf(tint(33, 47, 94), tint(88, 57, 140), tint(20, 29, 59))
      3 -> intArrayOf(tint(12, 20, 38), tint(42, 48, 68), tint(7, 13, 24))
      4 -> intArrayOf(tint(8, 66, 83), tint(21, 99, 111), tint(14, 30, 72))
      else -> intArrayOf(tint(28, 49, 91), tint(86, 69, 129), tint(29, 42, 74))
    }
    view.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
  }

  private fun stage(): LinearLayout {
    clock = null
    date = null
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    window.statusBarColor = tint(21, 33, 61)
    window.navigationBarColor = tint(17, 25, 45)
    window.decorView.systemUiVisibility = 0
    val background = SwipeHomeLayout()
    paintWallpaper(background)
    background.addView(View(this).apply {
      this.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(tint(9, 18, 36, 119), tint(21, 28, 49, 170), tint(12, 18, 38, 230)))
    }, FrameLayout.LayoutParams(-1, -1))
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(22))
      // Android 15+ draws behind the Fold6 status/navigation bars. Apply
      // actual system-bar insets instead of guessing margins from screenshots.
      setOnApplyWindowInsetsListener { view, windowInsets ->
        val top: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
          val bars = windowInsets.getInsets(WindowInsets.Type.systemBars())
          top = bars.top
          bottom = bars.bottom
        } else {
          @Suppress("DEPRECATION")
          top = windowInsets.systemWindowInsetTop
          @Suppress("DEPRECATION")
          bottom = windowInsets.systemWindowInsetBottom
        }
        view.setPadding(dp(16), dp(16) + top, dp(16), dp(20) + bottom)
        windowInsets
      }
    }
    background.addView(root, FrameLayout.LayoutParams(-1, -1))
    setContentView(background)
    return root
  }
  private fun fallback(view: View) {
    view.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
      intArrayOf(tint(28, 49, 91), tint(86, 69, 129), tint(29, 42, 74)))
  }
  private fun face(item: Shortcut, size: Int): FrameLayout {
    val shell = FrameLayout(this).apply {
      background = surface(item.color, 17)
      elevation = dp(2).toFloat()
    }
    if (item.icon != null) {
      shell.addView(ImageView(this).apply {
        setImageDrawable(item.icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(9), dp(9), dp(9), dp(9))
      }, FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
    } else {
      shell.addView(text(item.label.take(1), 24f, true).apply {
        gravity = Gravity.CENTER
      }, FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
    }
    return shell
  }
  private fun tile(item: Shortcut, size: Int = 52,
    longClick: (() -> Unit)? = null): LinearLayout {
    val cell = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
      setPadding(dp(2), dp(4), dp(2), dp(4))
      isClickable = true
      isFocusable = true
      contentDescription = "Open " + item.label
      setOnClickListener { open(item) }
      if (longClick != null) setOnLongClickListener { longClick(); true }
    }
    cell.addView(face(item, size), LinearLayout.LayoutParams(dp(size), dp(size)))
    cell.addView(text(item.label, 11f).apply {
      gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
      textAlignment = View.TEXT_ALIGNMENT_CENTER
      maxLines = 2
      ellipsize = TextUtils.TruncateAt.END
      setShadowLayer(2f, 0f, 1f, Color.BLACK)
    }, LinearLayout.LayoutParams(-1, dp(35)))
    return cell
  }
  private fun grid(all: List<Shortcut>, longClick: ((Int) -> Unit)? = null): GridLayout {
    val count = cols()
    val cellWidth = width() / count
    val result = GridLayout(this).apply { columnCount = count }
    all.forEachIndexed { index, item ->
      val row = tile(item, if (count > 5) 48 else 52,
        if (longClick == null) null else ({ longClick(index) }))
      result.addView(row,
        GridLayout.LayoutParams(GridLayout.spec(index / count), GridLayout.spec(index % count)).apply {
          this.width = cellWidth
          height = dp(97)
        })
    }
    return result
  }
  private fun menuRow(title: String, detail: String? = null, action: () -> Unit): LinearLayout {
    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(17), dp(13), dp(17), dp(13))
      background = surface()
      isFocusable = true
      isClickable = true
      setOnClickListener { action() }
    }
    panel.addView(text(title, 16f, true))
    if (detail != null) panel.addView(text(detail, 12f, false, tint(225, 230, 239)))
    return panel
  }
  private fun titleBar(name: String, action: String, callback: () -> Unit): LinearLayout {
    val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    row.addView(text(name, 24f, true), LinearLayout.LayoutParams(0, dp(52), 1f))
    row.addView(text(action, 14f, true).apply {
      gravity = Gravity.CENTER
      background = surface()
      setPadding(dp(17), dp(8), dp(17), dp(8))
      isClickable = true
      setOnClickListener { callback() }
    }, LinearLayout.LayoutParams(-2, dp(44)))
    return row
  }
  private fun dock(root: LinearLayout) {
    val bar = LinearLayout(this).apply {
      gravity = Gravity.CENTER
      background = surface(tint(18, 28, 52, 214), 27)
      setPadding(dp(4), dp(5), dp(4), 0)
    }
    for (name in listOf("phone", "messages", "contacts", "apps", "more")) {
      bar.addView(tile(builtIn(name), if (cols() > 5) 46 else 43),
        LinearLayout.LayoutParams(0, dp(86), 1f))
    }
    root.addView(bar, LinearLayout.LayoutParams(-1, dp(91)))
  }
  private fun pinnedDefault(index: Int, installed: List<LauncherActivityInfo>) = when (index) {
    0 -> "world"
    1 -> installed.firstOrNull { it.label.toString().equals("Chrome", true) }
      ?.componentName?.flattenToString()?.let { "app:" + it } ?: "apps"
    2 -> installed.firstOrNull { it.label.toString().equals("Camera", true) }
      ?.componentName?.flattenToString()?.let { "app:" + it } ?: "contacts"
    else -> "settings"
  }
  private fun pickPinned(index: Int) {
    val choices = listOf("world", "phone", "messages", "contacts", "settings", "apps")
      .map(::builtIn) + installed().map(::systemApp) + builtIn("empty")
    AlertDialog.Builder(this).setTitle("Home page " + (homePage + 1) + " · Slot " + (index + 1))
      .setItems(choices.map { it.label }.toTypedArray()) { _, selected ->
        prefs.edit().putString(pinKey(homePage, index), choices[selected].id).apply()
        showHome()
      }.setNegativeButton("Cancel", null).show()
  }
  private fun pinApp(item: Shortcut) {
    val choices = (0 until pageCount()).flatMap { index ->
      (0 until pageSlots(index)).map { slot -> index to slot }
    }
    AlertDialog.Builder(this).setTitle("Pin " + item.label + " to Home")
      .setItems(choices.map { (page, slot) ->
        "Page " + (page + 1) + " · Shortcut " + (slot + 1)
      }.toTypedArray()) { _, selected ->
        val (target, slot) = choices[selected]
        prefs.edit().putString(pinKey(target, slot), item.id).apply()
        homePage = target
        rememberPage()
        showHome()
        toast(item.label + " added to Home")
      }.setNegativeButton("Cancel", null).show()
  }
  private fun addPage() {
    if (pageCount() >= 6) { toast("Six Home pages is the current limit"); return }
    homePage = pageCount()
    prefs.edit().putInt("home_page_count", homePage + 1).apply()
    rememberPage()
    showHome()
  }

  private fun removePage() {
    if (homePage == 0 || pageCount() == 1) {
      toast("Keep your main Home page")
      return
    }
    val removing = homePage
    AlertDialog.Builder(this).setTitle("Remove Home page " + (removing + 1) + "?")
      .setMessage("This removes its shortcuts and widgets. Installed apps are not deleted.")
      .setNegativeButton("Cancel", null)
      .setPositiveButton("Remove") { _, _ ->
        widgets.clearPage(removing)
        val priorCount = pageCount()
        for (index in removing until priorCount - 1) {
          widgets.movePage(index + 1, index)
          for (slot in 0 until 8) {
            val next = prefs.getString(pinKey(index + 1, slot), "empty")
            prefs.edit().putString(pinKey(index, slot), next).apply()
          }
        }
        for (slot in 0 until 8)
          prefs.edit().remove(pinKey(priorCount - 1, slot)).apply()
        prefs.edit().putInt("home_page_count", priorCount - 1).apply()
        homePage = (removing - 1).coerceAtLeast(0)
        rememberPage()
        showHome()
      }.show()
  }
  private fun jumpPage(index: Int) {
    val next = index.coerceIn(0, pageCount() - 1)
    if (next == homePage) return
    homePage = next
    rememberPage()
    showHome()
  }
  private fun smallAction(label: String, action: () -> Unit): TextView =
    text(label, 12f, true).apply {
      gravity = Gravity.CENTER
      setPadding(dp(8), dp(10), dp(8), dp(10))
      background = surface(tint(23, 33, 59, 226), 18)
      isClickable = true
      isFocusable = true
      setOnClickListener { action() }
    }

  private fun showHome() {
    page = Page.HOME
    val root = stage()
    val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    top.addView(text("Living World", 17f, true),
      LinearLayout.LayoutParams(0, dp(46), 1f))
    top.addView(smallAction("⌕ Search") { showApps(true) })
    top.addView(View(this), LinearLayout.LayoutParams(dp(7), 1))
    top.addView(smallAction("▧") { changeWallpaper() }.apply {
      contentDescription = "Change Home wallpaper"
    })
    root.addView(top)

    val scroll = ScrollView(this).apply {
      isFillViewport = true
      isVerticalScrollBarEnabled = false
      overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, dp(11), 0, dp(8))
    }
    scroll.addView(content)
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

    if (homePage == 0) {
      content.addView(View(this), LinearLayout.LayoutParams(1, dp(30)))
      clock = text("", if (cols() >= 6) 63f else 54f, true)
      date = text("", 18f)
      content.addView(clock)
      content.addView(date)
      clock?.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
      date?.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
      content.addView(View(this), LinearLayout.LayoutParams(1, 0, .65f))
    } else {
      content.addView(text("Home page " + (homePage + 1), 28f, true))
      content.addView(UiKit.gap(this, 14))
    }

    val installed = installed()
    val favorites = (0 until pageSlots(homePage)).map { slot ->
      val key = pinKey(homePage, slot)
      val default = if (homePage == 0) pinnedDefault(slot, installed) else "empty"
      resolve(prefs.getString(key, default) ?: default, installed)
    }
    content.addView(text("YOUR SHORTCUTS", 12f, true, tint(224, 231, 241)))
    content.addView(UiKit.gap(this, 9))
    val favoritesGrid = grid(favorites) { index -> pickPinned(index) }
    favorites.forEachIndexed { index, shortcut ->
      if (shortcut.id == "empty")
        favoritesGrid.getChildAt(index).setOnClickListener { pickPinned(index) }
    }
    content.addView(favoritesGrid)
    if (homePage == 0) content.addView(text("Hold to customize · Swipe left for more pages",
      11f, false, tint(220, 228, 240)).apply { gravity = Gravity.CENTER })
    content.addView(UiKit.gap(this, 12))

    if (widgets.count(homePage) > 0) {
      content.addView(text("WIDGETS", 12f, true, tint(224, 231, 241)))
      content.addView(UiKit.gap(this, 7))
      widgets.views(homePage) { showHome() }.forEach { view ->
        content.addView(view, LinearLayout.LayoutParams(-1, dp(155)))
        content.addView(UiKit.gap(this, 9))
      }
    }
    if (homePage == 0) {
      content.addView(View(this), LinearLayout.LayoutParams(1, 0, .35f))
    }

    val pager = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    pager.addView(smallAction("‹") { jumpPage(homePage - 1) }.apply {
      isEnabled = homePage > 0
      alpha = if (homePage > 0) 1f else .35f
      contentDescription = "Previous Home page"
    })
    pager.addView(text((1..pageCount()).joinToString(" ") {
      if (it - 1 == homePage) "●" else "○"
    }, 20f, true).apply { gravity = Gravity.CENTER },
      LinearLayout.LayoutParams(0, dp(40), 1f))
    pager.addView(smallAction("›") { jumpPage(homePage + 1) }.apply {
      isEnabled = homePage < pageCount() - 1
      alpha = if (isEnabled) 1f else .35f
      contentDescription = "Next Home page"
    })
    root.addView(pager)
    val tools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    val addWidget = smallAction("+ Widget") { widgets.add(homePage) { showHome() } }
    val addPage = smallAction("+ Page") { addPage() }
    val customize = smallAction("⋯ Pages") {
      val choices = if (homePage == 0)
        arrayOf("Add Home page", "Change wallpaper", "Add widget")
      else arrayOf("Add Home page", "Remove this page", "Change wallpaper", "Add widget")
      AlertDialog.Builder(this).setTitle("Home screens")
        .setItems(choices) { _, which ->
          when (choices[which]) {
            "Add Home page" -> addPage()
            "Remove this page" -> removePage()
            "Change wallpaper" -> changeWallpaper()
            else -> widgets.add(homePage) { showHome() }
          }
        }.show()
    }
    for (button in listOf(addWidget, addPage, customize)) {
      tools.addView(button, LinearLayout.LayoutParams(0, dp(43), 1f).apply {
        setMargins(dp(3), 0, dp(3), 0)
      })
    }
    root.addView(tools)
    root.addView(UiKit.gap(this, 9))
    dock(root)
  }
  private fun showApps(focus: Boolean) {
    page = Page.APPS
    val root = stage()
    root.addView(titleBar("All apps", "Done") { hideKeyboard(); showHome() })
    val search = EditText(this).apply {
      setSingleLine(true)
      hint = "Search installed apps"
      textSize = 16f
      setTextColor(UiKit.ink)
      setHintTextColor(UiKit.muted)
      background = UiKit.surface(this@LauncherActivity)
      setPadding(dp(16), dp(9), dp(16), dp(9))
      setText(filter)
    }
    root.addView(search, LinearLayout.LayoutParams(-1, dp(53)))
    root.addView(UiKit.gap(this, 12))
    val scroll = ScrollView(this).apply {
      isFillViewport = true
      isVerticalScrollBarEnabled = true
    }
    val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    scroll.addView(holder)
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    val all = listOf("world", "phone", "messages", "contacts", "settings").map(::builtIn) +
      installed().map(::systemApp)
    fun render(query: String) {
      holder.removeAllViews()
      val filtered = all.filter { it.label.contains(query.trim(), true) }
      if (filtered.isEmpty()) holder.addView(text("No matching apps", 16f))
      else holder.addView(grid(filtered) { index -> pinApp(filtered[index]) })
    }
    render(filter)
    search.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        filter = s?.toString().orEmpty()
        render(filter)
      }
      override fun afterTextChanged(s: Editable?) {}
    })
    if (focus) {
      search.requestFocus()
      search.post { keyboard()?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }
    } else {
      root.isFocusableInTouchMode = true
      root.requestFocus()
    }
    dock(root)
  }
  private fun showMore() {
    hideKeyboard()
    page = Page.MORE
    val root = stage()
    root.addView(titleBar("More", "Home") { showHome() })
    val scroll = ScrollView(this)
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    scroll.addView(content)
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    fun section(label: String) {
      content.addView(UiKit.gap(this, 15))
      content.addView(text(label.uppercase(), 12f, true, tint(222, 226, 238)))
      content.addView(UiKit.gap(this, 9))
    }
    fun item(label: String, detail: String? = null, callback: () -> Unit) {
      content.addView(menuRow(label, detail, callback))
      content.addView(UiKit.gap(this, 9))
    }
    section("Personalize")
    item("Change wallpaper", "Android wallpaper chooser") {
      try { startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) }
      catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    item("Customize home shortcuts", "Long-press a shortcut on Home") {
      showHome()
      toast("Hold any home shortcut to choose a new app")
    }
    item("Default Home app", "Switch between Living World and One UI Home") {
      try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
      catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    section("Your phone")
    item("Living World", "Open the full simulation") { open(builtIn("world")) }
    item("Phone", "Dial numbers with the system phone app") { open(builtIn("phone")) }
    item("Android settings") { open(builtIn("settings")) }
    section("Advanced · test tools")
    item("Carrier SMS/MMS test", "Experimental; do not change defaults for launcher testing") {
      startActivity(Intent(this, CarrierTestActivity::class.java))
    }
    item("Message preview access", "Android notification-listener permission") {
      startActivity(GoogleMessagesBridge.settingsIntent())
    }
    item("Open Google Messages", "Keep your reliable real SMS/RCS app") {
      val i = packageManager.getLaunchIntentForPackage(GoogleMessagesBridge.GOOGLE_MESSAGES)
        ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
      try { startActivity(i) } catch (_: Exception) { toast("Google Messages unavailable") }
    }
    item("About", "Living World Home · launcher development build") {
      AlertDialog.Builder(this).setTitle("Living World Home")
        .setMessage("An optional Android HOME app. Your installed apps remain available. Google Messages can stay the default for SMS/RCS while carrier MMS testing continues.")
        .setPositiveButton("OK", null).show()
    }
    dock(root)
  }
}
