package com.livingworld.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.content.ComponentName
import android.provider.Settings
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Bitmap
import android.util.LruCache
import android.widget.EditText
import android.widget.FrameLayout
import org.json.JSONObject
import org.json.JSONTokener
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors

/** Native character inbox fed from the game's already-authenticated web session.
 * WebView keeps the game's login; the bridge transmits display metadata, not credentials.
 */
class WorldInboxActivity : Activity() {
  private data class Thread(val id: String, val name: String, val preview: String, val photo: String, val unread: Int, val lastAt: Long = 0L)
  private data class WorldMessage(val id: String, val text: String, val outgoing: Boolean, val at: Long, val photo: String)
  private data class InboxTile(val at: Long, val view: View)
  private fun tile(view: View, at: Long, gap: Int = 14): InboxTile {
    val wrapper = UiKit.vertical(this)
    wrapper.addView(view)
    wrapper.addView(UiKit.gap(this, gap))
    return InboxTile(at, wrapper)
  }
  private var web: WebView? = null
  private lateinit var rows: LinearLayout
  private lateinit var inboxScroll: ScrollView
  private lateinit var listPage: LinearLayout
  private lateinit var globalBack: View
  private lateinit var chatPage: LinearLayout
  private lateinit var chatMessages: LinearLayout
  private lateinit var chatScroll: ScrollView
  private lateinit var aiComposer: EditText
  private lateinit var aiStatus: android.widget.TextView
  private var selectedWorldThread: Thread? = null
  private var signingIn = false
  private var worldAuthenticated = false
  private var pendingGoogleSession: WorldOAuthHandoff.Session? = null
  private var googleSessionApplying = false
  private var bridgeDiagnostic = "Tap Connect with Google to link your existing Living World account."
  private var lastAuthError: String? = null
  private var webBridgeLoaded = false
  private var chatBridgeLoaded = false
  private var pendingAiRequestId: String? = null
  private var pendingAiBody: String? = null
  private var showingConversation = false
  private var lastChatSignature: Int? = null
  private var lastInboxSignature: Int? = null
  private var worldThreads = emptyList<Thread>()
  private val recentChatPreviews = mutableMapOf<String, Pair<Long, String>>()
  private val portraitCache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
  }
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
            o.optInt("unread").coerceIn(0, 999),
            runCatching { Instant.parse(o.optString("lastAt")).toEpochMilli() }
              .getOrDefault(0L)))
        }
        items
      } catch (_: Exception) { return }
      runOnUiThread {
        worldAuthenticated = true
        pendingGoogleSession = null
        webBridgeLoaded = true
        bridgeDiagnostic = "Connected — ${parsed.size} AI conversations synchronized."
        if (signingIn) showNative()
        render(parsed)
      }
    }
    @JavascriptInterface fun receiveSendStatus(json: String) {
      if (json.length > 2000) return
      val parsed = try {
        val data = JSONObject(json)
        Triple(data.optString("conversationId"), data.optString("requestId"),
          data.optString("status"))
      } catch (_: Exception) { return }
      runOnUiThread {
        if (isFinishing || isDestroyed ||
            selectedWorldThread?.id != parsed.first ||
            pendingAiRequestId != parsed.second) return@runOnUiThread
        val status = parsed.third
        if (status !in listOf("sent", "queued", "failed", "ignored")) return@runOnUiThread
        if (status == "sent" || status == "queued") {
          if (aiComposer.text.toString().trim() == pendingAiBody) aiComposer.setText("")
        }
        aiStatus.text = when (status) {
          "sent" -> "Sent to Living World"
          "queued" -> "Saved in Living World outbox — confirmation pending"
          else -> "Sending was not confirmed. Your draft is preserved."
        }
        pendingAiBody = null
        pendingAiRequestId = null
      }
    }
    @JavascriptInterface fun receiveMessages(json: String) {
      if (json.length > 650000) return
      val payload = try {
        val data = JSONObject(json)
        val id = data.optString("conversationId")
        if (!Regex("^[a-fA-F0-9-]{36}$").matches(id)) return
        val messages = data.optJSONArray("messages") ?: return
        val parsed = (0 until minOf(messages.length(), 120)).mapNotNull { i ->
          val row = messages.optJSONObject(i) ?: return@mapNotNull null
          val messageId = row.optString("id")
          if (!Regex("^[a-fA-F0-9-]{36}$").matches(messageId)) return@mapNotNull null
          WorldMessage(messageId, row.optString("text").take(4000),
            row.optBoolean("outgoing"), row.optLong("timestamp"),
            row.optString("photo").take(2000))
        }
        Pair(id, parsed)
      } catch (_: Exception) { return }
      runOnUiThread {
        if (!isFinishing && !isDestroyed && selectedWorldThread?.id == payload.first) {
          chatBridgeLoaded = true
          val newest = payload.second.maxByOrNull { it.at }
          if (newest != null && newest.at > 0L) {
            val snippet = newest.text.trim().ifBlank {
              if (newest.photo.isNotBlank()) "Photo" else "Message"
            }.take(160)
            val preview = if (newest.outgoing) "You: $snippet" else snippet
            val prior = recentChatPreviews[payload.first]
            if (prior == null || newest.at >= prior.first)
              recentChatPreviews[payload.first] = newest.at to preview
          }
          renderAiMessages(payload.second)
        }
      }
    }
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
      WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    pendingGoogleSession = WorldOAuthHandoff.take()
    if (pendingGoogleSession != null) lastAuthError = null
    if (pendingGoogleSession == null) {
      WorldOAuthHandoff.takeFailure()?.let { lastAuthError = it; bridgeDiagnostic = it }
    }
    window.statusBarColor = UiKit.bg
    window.navigationBarColor = UiKit.bg
    window.decorView.systemUiVisibility =
      View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    val root = UiKit.vertical(this).apply { fitsSystemWindows = true }
    setContentView(root)
    globalBack = UiKit.clickable(this, "‹  Messages") {
      if (showingConversation) showNative() else finish()
    }
    root.addView(globalBack)
    listPage = UiKit.vertical(this, 16).apply { setBackgroundColor(UiKit.bg) }
    val header = UiKit.horizontal(this)
    header.addView(UiKit.text(this, "Messages", 28f, UiKit.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
    header.addView(UiKit.clickable(this, "✎") {
      if (SmsRole.isDefault(this)) startActivity(Intent(this, SmsComposeActivity::class.java))
      else startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
    })
    // Carrier diagnostics and notification permissions are available without
    // cluttering the normal Messages interface.
    header.addView(UiKit.clickable(this, "⋮") {
      AlertDialog.Builder(this).setItems(arrayOf(
        "Notification access", "Carrier SMS/MMS testing", "Open Google Messages"
      )) { _, which ->
        when (which) {
          0 -> startActivity(GoogleMessagesBridge.settingsIntent())
          1 -> startActivity(Intent(this, CarrierTestActivity::class.java))
          else -> openRealMessages()
        }
      }.show()
    })
    listPage.addView(header)
    rows = UiKit.vertical(this)
    inboxScroll = ScrollView(this).apply {
      isFillViewport = true
      isVerticalScrollBarEnabled = true
      overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }
    inboxScroll.addView(rows)
    listPage.addView(inboxScroll, LinearLayout.LayoutParams(-1, 0, 1f))
    root.addView(listPage, LinearLayout.LayoutParams(-1, 0, 1f))
    chatPage = UiKit.vertical(this, 8).apply {
      visibility = View.GONE
      isFocusableInTouchMode = true
    }
    val chatHeader = UiKit.horizontal(this)
    chatHeader.addView(UiKit.clickable(this, "‹") { showNative() })
    val title = UiKit.text(this, "Conversation", 19f, UiKit.ink, true)
    title.tag = "chat-title"
    chatHeader.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
    chatHeader.addView(UiKit.clickable(this, "⋯") {
      selectedWorldThread?.let { thread ->
        showBrowser(WorldBackend.ORIGIN + "/chat/" + thread.id)
      }
    })
    chatPage.addView(chatHeader)
    aiStatus = UiKit.text(this, "Loading AI conversation…", 12f, UiKit.muted)
    chatPage.addView(aiStatus)
    chatScroll = ScrollView(this).apply {
      isFillViewport = true
      isVerticalScrollBarEnabled = true
      overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }
    chatMessages = UiKit.vertical(this, 10)
    chatScroll.addView(chatMessages)
    chatPage.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))
    val composerBar = UiKit.horizontal(this)
    aiComposer = EditText(this).apply {
      hint = "Message…"
      minLines = 1
      maxLines = 5
      setSingleLine(false)
      setPadding(UiKit.dp(this@WorldInboxActivity, 14), UiKit.dp(this@WorldInboxActivity, 9),
        UiKit.dp(this@WorldInboxActivity, 14), UiKit.dp(this@WorldInboxActivity, 9))
      background = UiKit.surface(this@WorldInboxActivity)
    }
    composerBar.addView(aiComposer, LinearLayout.LayoutParams(0, -2, 1f))
    composerBar.addView(UiKit.clickable(this, "Send") { sendAiText() })
    chatPage.addView(composerBar)
    root.addView(chatPage, LinearLayout.LayoutParams(-1, 0, 1f))
    rows.addView(UiKit.text(this, "Connecting to conversations…", 14f, UiKit.muted))
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
          if (uri.host == "studio.lovable.app") {
            runOnUiThread {
              Toast.makeText(this@WorldInboxActivity,
                "Lovable preview cannot sync with the installed app. Publish the website changes first.",
                Toast.LENGTH_LONG).show()
            }
            return true
          }
          if (uri.scheme != "https") return true
          return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri)); true
          } catch (_: Exception) { true }
        }
        override fun onReceivedError(
          view: WebView?, request: WebResourceRequest?, error: WebResourceError?
        ) {
          super.onReceivedError(view, request, error)
          if (request?.isForMainFrame != true) return
          if (!WorldBackend.isAllowed(request.url.toString())) return
          bridgeDiagnostic = "Cannot load the published Living World website (error ${error?.errorCode ?: -1}). Check its publish status and connection."
          if (!showingConversation && !worldAuthenticated) render(worldThreads)
        }
        override fun onPageFinished(view: WebView?, url: String?) {
          super.onPageFinished(view, url)
          // Probe after every allowed inbox load, including Retry (not only sign-in).
          // This also works against the older published inbox without its JS bridge.
          if (WorldBackend.isAllowed(url.orEmpty())) {
            if (pendingGoogleSession != null) scheduleGoogleSessionApply()
            val path = Uri.parse(url).path.orEmpty()
            if (path == "/inbox") scheduleInboxProbe()
            else if (path.startsWith("/chat/")) selectedWorldThread?.let {
              if (path == "/chat/" + it.id) {
                scheduleNativeChatProbe(it.id)
                scheduleChatProbe(it.id)
              }
            }
            if (signingIn && selectedWorldThread == null &&
                path != "/auth" && path != "/login" && path != "/inbox" &&
                !path.startsWith("/chat/")) {
              view?.post { if (signingIn) view.loadUrl(WorldBackend.INBOX_URL) }
            }
          }
        }
      }
      webChromeClient = WebChromeClient()
      loadUrl(WorldBackend.INBOX_URL)
    }
    web = browser
    // The game session stays loaded, without taking space from the scrollable inbox.
    root.addView(browser, LinearLayout.LayoutParams(-1, UiKit.dp(this, 2)))
    setWebMode(false)
    scheduleInboxProbe()
    maybeRequestContacts()
  }

  /**
   * Old published Living World builds still render /chat/{uuid} anchor rows.
   * Read just their display metadata from our allowlisted authenticated WebView.
   * This fallback is read-only; sending still requires the real authenticated bridge.
   */
  private fun probeAuthenticatedInbox() {
    val browser = web ?: return
    val url = browser.url.orEmpty()
    if (!WorldBackend.isAllowed(url) || selectedWorldThread != null) return
    val path = Uri.parse(url).path.orEmpty()
    if (path != "/inbox") {
      if (!worldAuthenticated) {
        bridgeDiagnostic = if (path == "/auth" || path == "/login")
          "Use Connect with Google to securely return your existing Chrome login to this app."
        else "Website opened $path; waiting for its inbox."
        if (!showingConversation) render(worldThreads)
      }
      return
    }
    val js = """(function() {
      try {
        const links = Array.from(document.querySelectorAll('a[href]'));
        const items = [];
        const known = new Set();
        for (const link of links) {
          const url = new URL(link.getAttribute('href'), location.href);
          const match = /^\/chat\/([0-9a-fA-F-]{36})\/?$/.exec(url.pathname);
          if (!match || known.has(match[1])) continue;
          known.add(match[1]);
          const name = link.querySelector('span.font-semibold,span.font-medium')?.textContent?.trim()
            || link.querySelector('span')?.textContent?.trim() || '';
          const preview = link.querySelector('p')?.textContent?.trim() || '';
          const img = link.querySelector('img');
          if (!name) continue;
          items.push({id:match[1],name:name.slice(0,60),
            preview:preview.slice(0,160),photo:img?.src||'',unread:0});
          if (items.length >= 40) break;
        }
        return JSON.stringify({state:'inbox',rows:items,
          bridge:Boolean(window.__livingWorldInboxBridgeReady),
          loaded:document.readyState});
      } catch (e) { return JSON.stringify({state:'probe-error'}); }
    })()"""
    browser.evaluateJavascript(js) { raw ->
      if (isFinishing || isDestroyed || worldAuthenticated) return@evaluateJavascript
      try {
        val payload = JSONObject(JSONTokener(raw).nextValue() as String)
        val matches = payload.optJSONArray("rows")
        if (matches != null && matches.length() > 0) {
          bridgeDiagnostic = "Using authenticated inbox preview; native message bridge not yet published."
          webBridge.receiveInbox(matches.toString())
        } else {
          bridgeDiagnostic = if (payload.optBoolean("bridge"))
            "Website bridge loaded; awaiting its conversation data. Check the app login."
          else "The published website has not provided the native bridge yet. Publish the GitHub changes in Lovable."
          if (!showingConversation) render(worldThreads)
        }
      } catch (_: Exception) {
        bridgeDiagnostic = "Could not read the website's inbox. Check your sign-in and published URL."
        if (!showingConversation) render(worldThreads)
      }
    }
  }

  private fun scheduleInboxProbe() {
    for (delay in longArrayOf(1000L, 3500L, 9000L)) {
      Handler(Looper.getMainLooper()).postDelayed({
        if (!isFinishing && !isDestroyed && !worldAuthenticated)
          probeAuthenticatedInbox()
      }, delay)
    }
  }

  private fun scheduleChatProbe(conversationId: String) {
    for (delay in longArrayOf(1500L, 4500L, 10000L)) {
      Handler(Looper.getMainLooper()).postDelayed({
        if (!isFinishing && !isDestroyed && selectedWorldThread?.id == conversationId &&
            !chatBridgeLoaded) probeChatPreview(conversationId)
      }, delay)
    }
  }

  /**
   * Pull the authenticated React chat snapshot when WebView's Java interface
   * misses the initial push. This is the real message bridge, not the DOM
   * scraping fallback. No credentials are transferred to native code.
   */
  private fun scheduleNativeChatProbe(conversationId: String) {
    for (delay in longArrayOf(500L, 1600L, 3200L, 6000L, 11000L, 18000L, 26000L)) {
      Handler(Looper.getMainLooper()).postDelayed({
        if (!isFinishing && !isDestroyed && selectedWorldThread?.id == conversationId &&
            !chatBridgeLoaded) probeNativeChatState(conversationId)
      }, delay)
    }
  }

  private fun probeNativeChatState(conversationId: String) {
    val browser = web ?: return
    val url = browser.url.orEmpty()
    if (!WorldBackend.isAllowed(url) ||
        Uri.parse(url).path != "/chat/$conversationId") return
    browser.evaluateJavascript(
      "(function(){return window.__livingWorldNativeChatSnapshot || ''})()"
    ) { raw ->
      if (isFinishing || isDestroyed || selectedWorldThread?.id != conversationId ||
          chatBridgeLoaded) return@evaluateJavascript
      val snapshot = try { JSONTokener(raw).nextValue() as? String }
        catch (_: Exception) { null }
      if (!snapshot.isNullOrBlank() && snapshot.length <= 650000)
        webBridge.receiveMessages(snapshot)
    }
  }

  private fun scheduleSendResultProbe(requestId: String) {
    for (delay in longArrayOf(1000L, 3000L, 6000L, 12000L, 22000L)) {
      Handler(Looper.getMainLooper()).postDelayed({
        if (isFinishing || isDestroyed || pendingAiRequestId != requestId)
          return@postDelayed
        val browser = web ?: return@postDelayed
        if (!WorldBackend.isAllowed(browser.url.orEmpty())) return@postDelayed
        browser.evaluateJavascript(
          "(function(){return window.__livingWorldNativeSendStatus || ''})()"
        ) { raw ->
          if (pendingAiRequestId != requestId || isFinishing || isDestroyed)
            return@evaluateJavascript
          val response = try { JSONTokener(raw).nextValue() as? String }
            catch (_: Exception) { null }
          if (!response.isNullOrEmpty() && response.length <= 2000)
            webBridge.receiveSendStatus(response)
        }
      }, delay)
    }
    Handler(Looper.getMainLooper()).postDelayed({
      if (!isFinishing && !isDestroyed && pendingAiRequestId == requestId) {
        pendingAiRequestId = null
        pendingAiBody = null
        aiStatus.text = "No send confirmation. Check the full chat before retrying."
      }
    }, 30000)
  }

  private fun probeChatPreview(conversationId: String) {
    val browser = web ?: return
    val url = browser.url.orEmpty()
    if (!WorldBackend.isAllowed(url) || Uri.parse(url).path != "/chat/$conversationId")
      return
    val js = """(function(){
      try {
        const region = document.querySelector('[role="region"][aria-label^="Messages with"]');
        if (!region) return JSON.stringify({state:'loading'});
        const buttons = Array.from(region.querySelectorAll('button.bubble-in')).slice(-120);
        return JSON.stringify({state:'ready',messages:buttons.map((b,i)=>({
          text:(b.textContent||'').slice(0,4000),
          outgoing:b.classList.contains('bg-bubble-me'),
          at:0,id:'00000000-0000-4000-8000-'+String(i).padStart(12,'0')
        }))});
      } catch (e) {return JSON.stringify({state:'error'});}
    })()"""
    browser.evaluateJavascript(js) { raw ->
      if (isFinishing || isDestroyed || chatBridgeLoaded ||
          selectedWorldThread?.id != conversationId) return@evaluateJavascript
      try {
        val payload = JSONObject(JSONTokener(raw).nextValue() as String)
        val messages = payload.optJSONArray("messages") ?: return@evaluateJavascript
        if (messages.length() == 0) return@evaluateJavascript
        val parsed = (0 until messages.length()).mapNotNull { i ->
          val item = messages.optJSONObject(i) ?: return@mapNotNull null
          WorldMessage(item.optString("id"), item.optString("text"),
            item.optBoolean("outgoing"), 0L, "")
        }
        renderAiMessages(parsed)
        aiStatus.text = "Preview only · Full chat available under ⋯"
      } catch (_: Exception) { /* Wait for the authenticated page to load. */ }
    }
  }

  /** Keep an authenticated page live for synchronization without taking inbox space. */
  private fun setWebMode(visible: Boolean) {
    val browser = web ?: return
    browser.visibility = View.VISIBLE
    browser.alpha = if (visible) 1f else 0.01f
    browser.isClickable = visible
    browser.layoutParams = if (visible)
      LinearLayout.LayoutParams(-1, 0, 1f)
    else LinearLayout.LayoutParams(-1, UiKit.dp(this, 2))
  }

  private fun beginGoogleSignin() {
    lastAuthError = null
    bridgeDiagnostic = "Opening Google sign-in in Chrome…"
    render(worldThreads)
    pictures.execute {
      val link = runCatching { WorldOAuthHandoff.begin(applicationContext) }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        link.onSuccess { url ->
          try {
            startActivity(Intent(Intent.ACTION_VIEW, url)
              .addCategory(Intent.CATEGORY_BROWSABLE))
          } catch (_: Exception) {
            bridgeDiagnostic = "Could not open your browser for Google sign-in."
            render(worldThreads)
          }
        }.onFailure { error ->
          bridgeDiagnostic = error.message ?: "Could not prepare secure Google sign-in."
          render(worldThreads)
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    pendingGoogleSession = WorldOAuthHandoff.take() ?: pendingGoogleSession
    if (pendingGoogleSession != null) lastAuthError = null
    if (pendingGoogleSession == null) {
      WorldOAuthHandoff.takeFailure()?.let {
        bridgeDiagnostic = it
        if (::rows.isInitialized) render(worldThreads)
      }
    }
    if (pendingGoogleSession != null) {
      googleSessionApplying = false
      bridgeDiagnostic = "Google sign-in returned! Connecting your AI messages…"
      if (::rows.isInitialized) render(worldThreads)
      web?.loadUrl(WorldBackend.INBOX_URL)
      scheduleGoogleSessionApply()
    }
  }

  private fun scheduleGoogleSessionApply() {
    for (delay in longArrayOf(150L, 1000L, 2500L, 5000L, 8500L)) {
      Handler(Looper.getMainLooper()).postDelayed({
        if (!isFinishing && !isDestroyed && pendingGoogleSession != null)
          applyGoogleSession()
      }, delay)
    }
  }

  private fun applyGoogleSession() {
    if (googleSessionApplying) return
    val browser = web ?: return
    if (!WorldBackend.isAllowed(browser.url.orEmpty())) return
    val session = pendingGoogleSession ?: return
    googleSessionApplying = true
    val script = "(async function(){if(typeof window.__livingWorldAndroidAcceptSession" +
      "!=='function')return 'not-ready';try{await window.__livingWorldAndroidAcceptSession(" +
      JSONObject.quote(session.access) + "," + JSONObject.quote(session.refresh) +
      ");return 'success';}catch(e){return 'failed';}})()"
    browser.evaluateJavascript(script) { reply ->
      googleSessionApplying = false
      if (isFinishing || isDestroyed) return@evaluateJavascript
      when (reply) {
        "\"success\"" -> {
          pendingGoogleSession = null
          bridgeDiagnostic = "Google connected. Loading AI conversations…"
          if (!showingConversation) render(worldThreads)
        }
        "\"failed\"" -> {
          pendingGoogleSession = null
          bridgeDiagnostic = "Website rejected the returned session. Check that the site changes are published."
          if (!showingConversation) render(worldThreads)
        }
        else -> {
          bridgeDiagnostic = "Waiting for the published website's Android sign-in receiver…"
          if (!showingConversation) render(worldThreads)
        }
      }
    }
  }

  private fun hideComposerKeyboard() {
    if (!::aiComposer.isInitialized) return
    try {
      getSystemService(InputMethodManager::class.java)
        ?.hideSoftInputFromWindow(aiComposer.windowToken, 0)
    } catch (_: Exception) { /* Closing navigation must work without IME. */ }
    aiComposer.clearFocus()
    if (::chatPage.isInitialized) chatPage.requestFocus()
  }

  private fun showNative() {
    hideComposerKeyboard()
    showingConversation = false
    signingIn = false
    selectedWorldThread = null
    chatPage.visibility = View.GONE
    globalBack.visibility = View.VISIBLE
    listPage.visibility = View.VISIBLE
    setWebMode(false)
    web?.loadUrl(WorldBackend.INBOX_URL)
    scheduleInboxProbe()
    render(worldThreads)
  }
  private fun showBrowser(url: String) {
    hideComposerKeyboard()
    showingConversation = true
    signingIn = true
    bridgeDiagnostic = "Waiting for the published Living World website to sync…"
    listPage.visibility = View.GONE
    globalBack.visibility = View.GONE
    chatPage.visibility = View.GONE
    setWebMode(true)
    web?.loadUrl(url)
  }
  private fun openNativeWorldChat(thread: Thread) {
    selectedWorldThread = thread
    chatBridgeLoaded = false
    pendingAiRequestId = null
    pendingAiBody = null
    showingConversation = true
    signingIn = false
    lastChatSignature = null
    listPage.visibility = View.GONE
    globalBack.visibility = View.GONE
    chatPage.visibility = View.VISIBLE
    setWebMode(false)
    hideComposerKeyboard()
    aiComposer.setText("")
    chatPage.requestFocus()
    chatScroll.scrollTo(0, 0)
    aiStatus.text = "Loading messages…"
    chatMessages.removeAllViews()
    val header = chatPage.getChildAt(0) as? LinearLayout
    (header?.getChildAt(1) as? android.widget.TextView)?.text = thread.name
    web?.loadUrl(WorldBackend.ORIGIN + "/chat/" + thread.id)
    scheduleChatProbe(thread.id)
    scheduleNativeChatProbe(thread.id)
  }
  private fun sendAiText() {
    val thread = selectedWorldThread ?: return
    val body = aiComposer.text.toString().trim()
    if (body.isBlank()) return
    if (body.length > 4000) {
      Toast.makeText(this, "Message exceeds 4000 characters", Toast.LENGTH_LONG).show()
      return
    }
    if (!chatBridgeLoaded) {
      Toast.makeText(this, "AI reply connection isn't ready. Tap ⋯ to use the full website chat.", Toast.LENGTH_LONG).show()
      return
    }
    if (pendingAiRequestId != null) {
      Toast.makeText(this, "Waiting for your previous message result. Don't send twice.", Toast.LENGTH_LONG).show()
      return
    }
    if (android.net.Uri.parse(web?.url).path != "/chat/" + thread.id ||
      !WorldBackend.isAllowed(web?.url.orEmpty())) {
      Toast.makeText(this, "AI chat is still loading. Your text has not sent.",
        Toast.LENGTH_LONG).show()
      return
    }
    val id = JSONObject.quote(thread.id)
    val safeText = JSONObject.quote(body)
    val requestId = java.util.UUID.randomUUID().toString()
    pendingAiRequestId = requestId
    pendingAiBody = body
    aiStatus.text = "Submitting to Living World…"
    val js = "(function(){if(window.__livingWorldNativeChatReady!==" + id +
      ")return 'not-ready';window.dispatchEvent(new CustomEvent(" +
      "'living-world-native-send',{detail:{conversationId:" + id +
      ",text:" + safeText + ",requestId:" + JSONObject.quote(requestId) +
      "}}));return 'accepted';})()"
    web?.evaluateJavascript(js) { result ->
      if (isFinishing || isDestroyed || pendingAiRequestId != requestId) return@evaluateJavascript
      if (result != "\"accepted\"") {
        pendingAiRequestId = null
        pendingAiBody = null
        aiStatus.text = "Message was not submitted. Keep your draft and try again."
      } else {
        aiStatus.text = "Waiting for Living World to confirm…"
        scheduleSendResultProbe(requestId)
      }
    }
  }
  private fun renderAiMessages(messages: List<WorldMessage>) {
    // The web bridge publishes text immediately and photos later, and can
    // repeat the same snapshot. Never delete/rebuild bubbles for identical data.
    val fingerprint = messages.fold(17) { hash, message ->
      (((hash * 31 + message.id.hashCode()) * 31 + message.text.hashCode()) *
        31 + message.photo.hashCode()) * 31 + (if (message.outgoing) 1 else 0)
    }
    val status = if (chatBridgeLoaded) "Living World • synchronized"
      else "Preview only · Full chat available under ⋯"
    if (lastChatSignature == fingerprint) {
      aiStatus.text = status
      return
    }

    val firstLoad = lastChatSignature == null
    val previousPosition = chatScroll.scrollY
    val remainingDistance = chatMessages.height - previousPosition - chatScroll.height
    // If someone is reading an earlier part of the chat, do not yank them back
    // to the bottom when the world tick, query refresh, or photo sync arrives.
    val followLatest = firstLoad || remainingDistance <= UiKit.dp(this, 72)
    lastChatSignature = fingerprint

    chatMessages.removeAllViews()
    for (message in messages) {
      if (message.text.isBlank() && message.photo.isBlank()) continue
      val row = UiKit.horizontal(this).apply {
        gravity = if (message.outgoing) Gravity.END else Gravity.START
      }
      val bubble = UiKit.text(this, message.text, 16f, UiKit.ink).apply {
        setPadding(UiKit.dp(this@WorldInboxActivity, 12), UiKit.dp(this@WorldInboxActivity, 9),
          UiKit.dp(this@WorldInboxActivity, 12), UiKit.dp(this@WorldInboxActivity, 9))
        background = UiKit.surface(this@WorldInboxActivity,
          if (message.outgoing) Color.rgb(220, 233, 254) else Color.WHITE)
        maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
      }
      if (message.photo.isNotBlank()) {
        val media = ImageView(this).apply {
          adjustViewBounds = false
          scaleType = ImageView.ScaleType.FIT_CENTER
          background = UiKit.surface(this@WorldInboxActivity,
            Color.rgb(239, 236, 245), 14)
        }
        val container = UiKit.vertical(this, 5).apply {
          gravity = if (message.outgoing) Gravity.END else Gravity.START
        }
        // Reserve space *before* an asynchronous image loads. Otherwise the
        // image forces the whole scroll view to jump while someone is reading.
        container.addView(media, LinearLayout.LayoutParams(
          UiKit.dp(this, 235), UiKit.dp(this, 235)))
        loadAiPhoto(media, message.photo)
        if (message.text.isNotBlank()) container.addView(bubble)
        row.addView(container)
      } else row.addView(bubble)
      chatMessages.addView(row)
      chatMessages.addView(UiKit.gap(this, 6))
    }
    aiStatus.text = status
    val expectedConversation = selectedWorldThread?.id
    chatScroll.post {
      if (isFinishing || isDestroyed || !showingConversation ||
          selectedWorldThread?.id != expectedConversation) return@post
      if (followLatest) chatScroll.fullScroll(View.FOCUS_DOWN)
      else chatScroll.scrollTo(0, previousPosition)
    }
  }
  private fun render(threads: List<Thread>) {
    val previousInboxPosition = if (::inboxScroll.isInitialized) inboxScroll.scrollY else 0
    // Keep the latest confirmed native conversation snippet until the web inbox
    // catches up after leaving a chat. This avoids showing an older preview.
    val updated = threads.map { thread ->
      val known = recentChatPreviews[thread.id]
      if (known != null && known.first >= thread.lastAt)
        thread.copy(preview = known.second, lastAt = known.first)
      else thread
    }
    worldThreads = updated
    rows.removeAllViews()
    val tiles = mutableListOf<InboxTile>()
    if (!worldAuthenticated) {
      rows.addView(UiKit.text(this,
        "AI conversations aren't connected to this installation yet.", 14f, UiKit.muted))
      rows.addView(UiKit.text(this, lastAuthError ?: bridgeDiagnostic, 12f, UiKit.muted))
      rows.addView(UiKit.clickable(this, "Connect with Google") { beginGoogleSignin() })
      rows.addView(UiKit.clickable(this, "Retry AI conversation sync") {
        bridgeDiagnostic = "Checking the published Living World inbox…"
        web?.loadUrl(WorldBackend.INBOX_URL)
        scheduleInboxProbe()
        render(worldThreads)
      })
    } else if (threads.isEmpty()) {
      rows.addView(UiKit.text(this, "Connected — no AI conversations found.", 14f, UiKit.muted))
    }
    for (t in updated) {
      val line = UiKit.horizontal(this)
      val avatar = FrameLayout(this).apply {
        background = UiKit.surface(this@WorldInboxActivity,
          Color.rgb(237,232,250), 50)
        clipToOutline = true
      }
      val initial = UiKit.text(this, t.name.trim().take(1).uppercase().ifBlank { "?" },
        22f, UiKit.lavender, true).apply { gravity = Gravity.CENTER }
      avatar.addView(initial, FrameLayout.LayoutParams(-1, -1))
      val picture = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
      avatar.addView(picture, FrameLayout.LayoutParams(-1, -1))
      line.addView(avatar, LinearLayout.LayoutParams(UiKit.dp(this, 52), UiKit.dp(this, 52)))
      loadPhoto(picture, t.photo)
      val detail = UiKit.vertical(this, 10)
      detail.addView(UiKit.text(this, t.name + if(t.unread > 0) "  • ${t.unread}" else "", 17f, UiKit.ink, t.unread > 0))
      detail.addView(UiKit.text(this, t.preview, 13f, UiKit.muted).apply { maxLines = 2 })
      line.addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
      line.setOnClickListener { openNativeWorldChat(t) }
      tiles.add(tile(line, t.lastAt))
    }
    renderRealNotifications(tiles)
    // All native AI and real threads share one chronological inbox.
    tiles.sortedByDescending { it.at }.forEach { rows.addView(it.view) }
    if (!showingConversation) {
      listPage.visibility = View.VISIBLE
      setWebMode(false)
    }
    inboxScroll.post {
      if (!isFinishing && !isDestroyed && !showingConversation)
        inboxScroll.scrollTo(0, previousInboxPosition)
    }
  }
  override fun onResume() {
    super.onResume()
    if (::rows.isInitialized && !showingConversation) render(worldThreads)
  }
  private fun renderRealNotifications(tiles: MutableList<InboxTile>) {
    val isDefault = SmsRole.isDefault(this)
    val allowed = GoogleMessagesBridge.hasAccess(this)
    // Once Living World becomes the SMS default, prefer provider-backed threads
    // and never duplicate old Google Messages notification previews.
    val current = if (allowed && !isDefault) BridgeInbox.snapshot() else emptyList()
    if (current.isNotEmpty()) {
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
          startActivity(Intent(this, BridgeConversationActivity::class.java)
            .putExtra(BridgeConversationActivity.EXTRA_NOTIFICATION_KEY, item.notificationKey))
        }
        tiles.add(tile(line, item.postedAt))
      }
    }
    if (isDefault) {
      val profiles = ContactNameResolver.snapshotProfiles(this)
      val contacts = profiles.mapValues { it.value.name }
      if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        rows.addView(UiKit.clickable(this, "Show saved contact names") {
          requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 210)
        })
        rows.addView(UiKit.gap(this, 8))
      }
      SmsHistory.recent(this).forEach { sms ->
        val row = UiKit.horizontal(this)
        val displayName = ContactNameResolver.display(sms.address, contacts)
        val avatar = localContactAvatar(displayName,
          ContactNameResolver.photo(sms.address, profiles))
        row.addView(avatar, LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)))
        val content = UiKit.vertical(this, 8).apply {
          setPadding(UiKit.dp(this@WorldInboxActivity, 12), 0, 0, 0)
        }
        content.addView(UiKit.text(this, ContactNameResolver.display(sms.address, contacts), 17f, UiKit.ink, sms.unread))
        content.addView(UiKit.text(this, sms.body, 13f, UiKit.muted).apply { maxLines = 2 })
        row.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
        row.isClickable = true
        row.setOnClickListener {
          startActivity(Intent(this, SmsThreadActivity::class.java)
            .putExtra("threadId", sms.threadId).putExtra("address", sms.address))
        }
        tiles.add(tile(row, sms.timestamp, 12))
      }
    }
    if (!allowed && !isDefault) {
      rows.addView(UiKit.text(this,
        "Enable notification previews from ⋮ to see recent real messages here.",
        13f, UiKit.muted))
    }
  }
  private fun maybeRequestContacts() {
    if (!SmsRole.isDefault(this)) return
    if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) return
    val preferences = getSharedPreferences("inbox_contact_permission", MODE_PRIVATE)
    if (preferences.getBoolean("requested", false)) return
    preferences.edit().putBoolean("requested", true).apply()
    requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 210)
  }

  override fun onRequestPermissionsResult(
    requestCode: Int, permissions: Array<out String>, grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == 210 && ::rows.isInitialized && !showingConversation) render(worldThreads)
  }

  private fun openRealMessages() {
    val intent = packageManager.getLaunchIntentForPackage(GoogleMessagesBridge.GOOGLE_MESSAGES)
      ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
    try { startActivity(intent) }
    catch (_: Exception) { Toast.makeText(this, "Google Messages unavailable", Toast.LENGTH_SHORT).show() }
  }
  private fun localContactAvatar(name: String, uri: Uri?): FrameLayout {
    val avatar = FrameLayout(this).apply {
      background = UiKit.surface(this@WorldInboxActivity,
        Color.rgb(230,240,251), 48)
      clipToOutline = true
    }
    val initial = UiKit.text(this, name.trim().take(1).uppercase().ifBlank { "?" },
      21f, UiKit.ink, true).apply { gravity = Gravity.CENTER }
    avatar.addView(initial, FrameLayout.LayoutParams(-1,-1))
    if (uri?.scheme == "content") {
      pictures.execute {
        val bitmap = try {
          contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null,
              BitmapFactory.Options().apply { inSampleSize = 2 })
          }
        } catch (_: Exception) { null }
        if (bitmap != null) runOnUiThread {
          if (!isFinishing && !isDestroyed && avatar.childCount == 1) {
            val image = ImageView(this).apply {
              scaleType = ImageView.ScaleType.CENTER_CROP
              setImageBitmap(bitmap)
            }
            avatar.addView(image, FrameLayout.LayoutParams(-1, -1))
          }
        }
      }
    }
    return avatar
  }

  private fun loadAiPhoto(view: ImageView, url: String) {
    val resolved = if (url.startsWith("/")) WorldBackend.ORIGIN + url else url
    val uri = Uri.parse(resolved)
    if (uri.scheme != "https" || uri.host.isNullOrBlank()) return
    pictures.execute {
      try {
        val connection = URL(resolved).openConnection() as HttpURLConnection
        connection.connectTimeout = 6500
        connection.readTimeout = 6500
        connection.instanceFollowRedirects = false
        if (connection.responseCode != 200 || connection.contentLengthLong > 4000000) {
          connection.disconnect()
          return@execute
        }
        val bytes = connection.inputStream.use { input ->
          val output = java.io.ByteArrayOutputStream()
          val buffer = ByteArray(8192)
          while (output.size() <= 4000000) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
          }
          output.toByteArray()
        }
        connection.disconnect()
        if (bytes.size > 4000000) return@execute
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
          BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return@execute
        runOnUiThread {
          if (!isFinishing && !isDestroyed) view.setImageBitmap(bitmap)
        }
      } catch (_: Exception) { }
    }
  }

  private fun loadPhoto(view: ImageView, url: String) {
    if (url.isBlank()) return
    val resolved = if (url.startsWith("/")) WorldBackend.ORIGIN + url else url
    val uri = Uri.parse(resolved)
    if (uri.scheme != "https" || uri.host.isNullOrBlank()) return
    portraitCache.get(resolved)?.let { cached ->
      view.setImageBitmap(cached)
      view.scaleType = ImageView.ScaleType.CENTER_CROP
      return
    }
    pictures.execute {
      try {
        // Canonical Riley and Sienna portraits are 2-3 MB PNGs. The prior
        // 350 KB transfer cap rejected them before decoding. Download safely
        // with a 4 MB limit, then decode at thumbnail resolution.
        val limit = 4_000_000
        var target = URL(resolved)
        var connection: HttpURLConnection? = null
        for (redirect in 0..2) {
          if (target.protocol != "https") return@execute
          val current = (target.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            instanceFollowRedirects = false
          }
          val status = current.responseCode
          if (status in listOf(301, 302, 303, 307, 308) && redirect < 2) {
            val location = current.getHeaderField("Location") ?: run {
              current.disconnect()
              return@execute
            }
            val next = URL(target, location)
            current.disconnect()
            if (next.protocol != "https") return@execute
            target = next
          } else {
            connection = current
            break
          }
        }
        val current = connection ?: return@execute
        if (current.responseCode != 200 || current.contentLengthLong > limit) {
          current.disconnect()
          return@execute
        }
        val bytes = current.inputStream.use { stream ->
          val output = java.io.ByteArrayOutputStream()
          val buffer = ByteArray(8192)
          while (output.size() <= limit) {
            val count = stream.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
          }
          output.toByteArray()
        }
        current.disconnect()
        if (bytes.size > limit) return@execute
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
          BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return@execute
        portraitCache.put(resolved, image)
        runOnUiThread {
          if (!isFinishing && !isDestroyed && view.isAttachedToWindow) {
            view.setImageBitmap(image)
            view.scaleType = ImageView.ScaleType.CENTER_CROP
          }
        }
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
