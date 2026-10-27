package com.livingworld.launcher

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.provider.Settings
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Native character inbox fed from the game's already-authenticated web session.
 * WebView keeps the game's login; the bridge transmits display metadata, not credentials.
 */
class WorldInboxActivity : Activity() {
  private data class Thread(val id: String, val name: String, val preview: String, val photo: String, val unread: Int)
  private var web: WebView? = null
  private lateinit var rows: LinearLayout
  private lateinit var listPage: LinearLayout
  private var showingConversation = false
  private var worldThreads = emptyList<Thread>()
  private val pictures = Executors.newFixedThreadPool(2)
  private val webBridge = object {
    @JavascriptInterface fun receiveInbox(json: String) {
      if (json.length > 75000) return
      val parsed = try {
        val array = JSONArray(json)
        val items = mutableListOf<Thread>()
        for (i in 0 until minOf(array.length(), 40)) {
          val o = array.optJSONObject(i) ?: continue
          val id = o.optString("id").take(100)
          if (!Regex("^[a-fA-F0-9-]{36}$").matches(id)) continue
          items.add(Thread(id, o.optString("name").take(60),
            o.optString("preview").take(160), o.optString("photo").take(1400),
            o.optInt("unread").coerceIn(0, 999)))
        }
        items
      } catch (_: Exception) { return }
      runOnUiThread { render(parsed) }
    }
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = UiKit.bg
    window.navigationBarColor = UiKit.bg
    window.decorView.systemUiVisibility =
      View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    val root = UiKit.vertical(this)
    setContentView(root)
    root.addView(UiKit.clickable(this, "‹  Messages") {
      if (showingConversation) showNative() else finish()
    })
    listPage = UiKit.vertical(this, 16).apply { setBackgroundColor(UiKit.bg) }
    val header = UiKit.horizontal(this)
    header.addView(UiKit.text(this, "Messages", 28f, UiKit.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
    header.addView(UiKit.clickable(this, "✎") { startActivity(Intent(this, SmsComposeActivity::class.java)) })
    listPage.addView(header)
    rows = UiKit.vertical(this)
    listPage.addView(rows)
    root.addView(listPage, LinearLayout.LayoutParams(-1, 0, 1f))
    rows.addView(UiKit.text(this, "Connecting to conversations…", 14f, UiKit.muted))
    renderRealNotifications()
    val browser = WebView(this).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.allowFileAccess = false
      settings.allowContentAccess = false
      addJavascriptInterface(webBridge, "LivingWorldAndroid")
      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
          val uri = request?.url ?: return false
          if (WorldBackend.isAllowed(uri.toString())) return false
          if (uri.scheme != "https") return true
          return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri)); true
          } catch (_: Exception) { true }
        }
      }
      webChromeClient = WebChromeClient()
      loadUrl(WorldBackend.INBOX_URL)
    }
    web = browser
    // Visible until the authenticated inbox publishes the native snapshot.
    root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
  }
  private fun showNative() {
    showingConversation = false
    listPage.visibility = View.VISIBLE
    web?.visibility = View.GONE
    web?.loadUrl(WorldBackend.INBOX_URL)
  }
  private fun render(threads: List<Thread>) {
    worldThreads = threads
    rows.removeAllViews()
    if (threads.isEmpty()) {
      rows.addView(UiKit.text(this, "Sign in below to load conversations.", 14f, UiKit.muted))
    }
    for (t in threads) {
      val line = UiKit.horizontal(this)
      val picture = ImageView(this)
      picture.setBackgroundColor(android.graphics.Color.rgb(237,232,250))
      line.addView(picture, LinearLayout.LayoutParams(UiKit.dp(this, 52), UiKit.dp(this, 52)))
      loadPhoto(picture, t.photo)
      val detail = UiKit.vertical(this, 10)
      detail.addView(UiKit.text(this, t.name + if(t.unread > 0) "  • ${t.unread}" else "", 17f, UiKit.ink, t.unread > 0))
      detail.addView(UiKit.text(this, t.preview, 13f, UiKit.muted).apply { maxLines = 2 })
      line.addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
      line.setOnClickListener {
        showingConversation = true
        listPage.visibility = View.GONE
        web?.visibility = View.VISIBLE
        web?.loadUrl(WorldBackend.ORIGIN + "/chat/" + t.id)
      }
      rows.addView(line)
      rows.addView(UiKit.gap(this, 14))
    }
    renderRealNotifications()
    if (!showingConversation) {
      listPage.visibility = View.VISIBLE
      web?.visibility = View.GONE
    }
  }
  override fun onResume() {
    super.onResume()
    if (::rows.isInitialized && !showingConversation) render(worldThreads)
  }
  private fun renderRealNotifications() {
    val allowed = GoogleMessagesBridge.hasAccess(this)
    val current = if (allowed) BridgeInbox.snapshot() else emptyList()
    if (current.isNotEmpty()) {
      rows.addView(UiKit.gap(this, 10))
      current.forEach { item ->
        val line = UiKit.horizontal(this)
        val avatar = UiKit.text(this, item.sender.take(1), 20f, UiKit.ink, true).apply {
          gravity = Gravity.CENTER
          background = UiKit.surface(this@WorldInboxActivity, android.graphics.Color.rgb(228,238,249), 30)
        }
        line.addView(avatar, LinearLayout.LayoutParams(UiKit.dp(this, 52), UiKit.dp(this, 52)))
        val detail = UiKit.vertical(this, 10)
        detail.addView(UiKit.text(this, item.sender, 17f, UiKit.ink, true))
        detail.addView(UiKit.text(this, item.preview, 13f, UiKit.muted).apply { maxLines = 2 })
        line.addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
        line.isClickable = true
        line.setOnClickListener {
          try {
            val destination = item.openIntent
            if (destination != null) destination.send()
            else openRealMessages()
          } catch (_: PendingIntent.CanceledException) { openRealMessages() }
        }
        rows.addView(line)
        rows.addView(UiKit.gap(this, 14))
      }
    }
    if (SmsRole.isDefault(this)) {
      SmsHistory.recent(this).forEach { sms ->
        val row = UiKit.horizontal(this)
        val content = UiKit.vertical(this, 8)
        content.addView(UiKit.text(this, sms.address.ifBlank { "Unknown number" }, 17f, UiKit.ink, sms.unread))
        content.addView(UiKit.text(this, sms.body, 13f, UiKit.muted).apply { maxLines = 2 })
        row.addView(content, LinearLayout.LayoutParams(-1, -2))
        row.isClickable = true
        row.setOnClickListener {
          startActivity(Intent(this, SmsThreadActivity::class.java)
            .putExtra("threadId", sms.threadId).putExtra("address", sms.address))
        }
        rows.addView(row)
        rows.addView(UiKit.gap(this, 12))
      }
    }
    rows.addView(UiKit.gap(this, 12))
    if (!allowed) {
      rows.addView(UiKit.clickable(this, "Allow message previews") {
        startActivity(GoogleMessagesBridge.settingsIntent())
      })
    }
    rows.addView(UiKit.clickable(this, "Open Google Messages") { openRealMessages() })
  }
  private fun openRealMessages() {
    val intent = packageManager.getLaunchIntentForPackage(GoogleMessagesBridge.GOOGLE_MESSAGES)
      ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
    try { startActivity(intent) }
    catch (_: Exception) { Toast.makeText(this, "Google Messages unavailable", Toast.LENGTH_SHORT).show() }
  }
  private fun loadPhoto(view: ImageView, url: String) {
    val resolved = if (url.startsWith("/")) WorldBackend.ORIGIN + url else url
    val uri = Uri.parse(resolved)
    if (uri.scheme != "https" || uri.host.isNullOrBlank()) return
    pictures.execute {
      try {
        val connection = URL(resolved).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 6000
        connection.instanceFollowRedirects = false
        if (connection.responseCode != 200 || connection.contentLengthLong > 350000) {
          connection.disconnect(); return@execute
        }
        val bytes = connection.inputStream.use { stream ->
          val output = java.io.ByteArrayOutputStream()
          val buffer = ByteArray(8192)
          while (output.size() <= 350000) {
            val count = stream.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
          }
          output.toByteArray()
        }
        connection.disconnect()
        if (bytes.size > 350000) return@execute
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@execute
        runOnUiThread { view.setImageBitmap(image); view.scaleType = ImageView.ScaleType.CENTER_CROP }
      } catch (_: Exception) { }
    }
  }
  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (showingConversation) showNative() else super.onBackPressed()
  }
  override fun onDestroy() {
    web?.apply { removeJavascriptInterface("LivingWorldAndroid"); stopLoading(); destroy() }
    web = null
    pictures.shutdownNow()
    super.onDestroy()
  }
}
