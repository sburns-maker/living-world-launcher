package com.livingworld.launcher

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.content.ComponentName
import android.provider.Settings
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.widget.EditText
import android.widget.FrameLayout
import org.json.JSONObject
import org.json.JSONTokener
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
import java.util.concurrent.Executors

/** Native character inbox fed from the game's already-authenticated web session.
 * WebView keeps the game's login; the bridge transmits display metadata, not credentials.
 */
class WorldInboxActivity : Activity() {
  private data class Thread(val id: String, val name: String, val preview: String, val photo: String, val unread: Int)
  private data class WorldMessage(val id: String, val text: String, val outgoing: Boolean, val at: Long, val photo: String)
  private var web: WebView? = null
  private lateinit var rows: LinearLayout
  private lateinit var listPage: LinearLayout
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
      if (json.length > 250000) return
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
          renderAiMessages(payload.second)
        }
      }
    }
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
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
    root.addView(UiKit.clickable(this, "‹  Messages") {
      if (showingConversation) showNative() else finish()
    })
    listPage = UiKit.vertical(this, 16).apply { setBackgroundColor(UiKit.bg) }
    val header = UiKit.horizontal(this)
    header.addView(UiKit.text(this, "Messages", 28f, UiKit.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
    header.addView(UiKit.clickable(this, "Test") {
      startActivity(Intent(this, CarrierTestActivity::class.java))
    })
    header.addView(UiKit.clickable(this, "✎") { startActivity(Intent(this, SmsComposeActivity::class.java)) })
    listPage.addView(header)
    rows = UiKit.vertical(this)
    val conversationScroll = ScrollView(this).apply {
      isFillViewport = true
      isVerticalScrollBarEnabled = true
      overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }
    conversationScroll.addView(rows)
    listPage.addView(conversationScroll, LinearLayout.LayoutParams(-1, 0, 1f))
    root.addView(listPage, LinearLayout.LayoutParams(-1, 0, 1f))
    chatPage = UiKit.vertical(this, 8).apply { visibility = View.GONE }
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
    chatScroll = ScrollView(this).apply { isFillViewport = true }
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
              if (path == "/chat/" + it.id) scheduleChatProbe(it.id)
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
        aiStatus.text = "Chat preview — publish the website bridge for native replies and photos."
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

  private fun showNative() {
    showingConversation = false
    signingIn = false
    selectedWorldThread = null
    chatPage.visibility = View.GONE
    listPage.visibility = View.VISIBLE
    setWebMode(false)
    web?.loadUrl(WorldBackend.INBOX_URL)
    scheduleInboxProbe()
    render(worldThreads)
  }
  private fun showBrowser(url: String) {
    showingConversation = true
    signingIn = true
    bridgeDiagnostic = "Waiting for the published Living World website to sync…"
    listPage.visibility = View.GONE
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
    listPage.visibility = View.GONE
    chatPage.visibility = View.VISIBLE
    setWebMode(false)
    aiComposer.setText("")
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
    chatMessages.removeAllViews()
    messages.forEach { message ->
      if (message.text.isBlank() && message.photo.isBlank()) return@forEach
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
          adjustViewBounds = true
          maxWidth = UiKit.dp(this@WorldInboxActivity, 245)
          maxHeight = UiKit.dp(this@WorldInboxActivity, 320)
          scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val container = UiKit.vertical(this, 5).apply {
          gravity = if (message.outgoing) Gravity.END else Gravity.START
        }
        loadAiPhoto(media, message.photo)
        container.addView(media)
        if (message.text.isNotBlank()) container.addView(bubble)
        row.addView(container)
      } else row.addView(bubble)
      chatMessages.addView(row)
      chatMessages.addView(UiKit.gap(this, 6))
    }
    aiStatus.text = "Living World • synchronized"
    chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
  }
  private fun render(threads: List<Thread>) {
    worldThreads = threads
    rows.removeAllViews()
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
    for (t in threads) {
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
      rows.addView(line)
      rows.addView(UiKit.gap(this, 14))
    }
    renderRealNotifications()
    if (!showingConversation) {
      listPage.visibility = View.VISIBLE
      setWebMode(false)
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
    rows.addView(UiKit.clickable(this, "Carrier SMS/MMS test setup") {
      startActivity(Intent(this, CarrierTestActivity::class.java))
    })
    rows.addView(UiKit.clickable(this, "Open Google Messages") { openRealMessages() })
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
