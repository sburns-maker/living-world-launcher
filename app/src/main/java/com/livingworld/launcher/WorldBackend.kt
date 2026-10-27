package com.livingworld.launcher

/** Lovable currently hosts the authenticated UI/database gateway. */
object WorldBackend {
  const val ORIGIN = "https://screen-to-same-studio.lovable.app"
  const val INBOX_URL = "$ORIGIN/inbox"
  fun isAllowed(url: String): Boolean {
    val uri = android.net.Uri.parse(url)
    return uri.scheme == "https" && uri.host == android.net.Uri.parse(ORIGIN).host
  }
}
