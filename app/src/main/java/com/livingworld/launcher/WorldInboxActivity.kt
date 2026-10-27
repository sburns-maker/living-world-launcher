package com.livingworld.launcher

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout

/** Authenticated fictional conversation inbox without duplicating backend message data.
 * WebView uses its own isolated cookie store, so a first-time login may be needed.
 */
class WorldInboxActivity : Activity() {
  private var web: WebView? = null
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = UiKit.bg
    window.navigationBarColor = UiKit.bg
    window.decorView.systemUiVisibility =
      View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    val root = UiKit.vertical(this, 0)
    setContentView(root)
    val back = UiKit.clickable(this, "‹  Messages") { finish() }
    root.addView(back)
    val view = WebView(this).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
          val url = request?.url?.toString() ?: return false
          if (WorldBackend.isAllowed(url)) return false
          return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
          } catch (_: Exception) { true }
        }
      }
      webChromeClient = WebChromeClient()
      loadUrl(WorldBackend.INBOX_URL)
    }
    web = view
    root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
  }
  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    val view = web
    if (view != null && view.canGoBack()) view.goBack()
    else super.onBackPressed()
  }
  override fun onDestroy() {
    web?.apply { stopLoading(); destroy() }
    web = null
    super.onDestroy()
  }
}
