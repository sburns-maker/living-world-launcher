package com.livingworld.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads only public HTTPS image URLs surfaced by the authenticated Living
 * World message bridge. Does not log signed links or store them persistently.
 */
object WorldImageLoader {
  const val MAX_BYTES = 18 * 1024 * 1024

  data class Source(val bytes: ByteArray, val mime: String) {
    fun bitmap(maxDimension: Int = 1600): Bitmap? {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
      if (maxSide <= 0 || maxSide > 16000 || bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L)
        return null
      var sample = 1
      while (maxSide / sample > maxDimension * 2 && sample < 64) sample *= 2
      return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply {
          inSampleSize = sample
          inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }
  }

  fun resolve(link: String): String? {
    if (link.isBlank() || link.length > 4000) return null
    val resolved = if (link.startsWith("/")) WorldBackend.ORIGIN + link else link
    val uri = Uri.parse(resolved)
    if (uri.scheme != "https" || uri.host.isNullOrBlank() ||
        uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return null
    return resolved
  }

  fun fetch(link: String): Source? {
    val resolved = resolve(link) ?: return null
    var target = URL(resolved)
    for (step in 0..3) {
      if (target.protocol != "https" || target.toURI().userInfo != null ||
          (target.port != -1 && target.port != 443)) return null
      val connection = (target.openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 12000
        instanceFollowRedirects = false
        setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*")
      }
      try {
        val response = connection.responseCode
        if (response in listOf(301, 302, 303, 307, 308) && step < 3) {
          val location = connection.getHeaderField("Location") ?: return null
          target = URL(target, location)
          continue
        }
        if (response != 200 || connection.contentLengthLong > MAX_BYTES) return null
        val contentType = connection.contentType?.substringBefore(';')?.lowercase().orEmpty()
        if (contentType.isNotBlank() && !contentType.startsWith("image/") &&
            contentType != "application/octet-stream") return null
        val stream = ByteArrayOutputStream()
        connection.inputStream.use { input ->
          val buffer = ByteArray(16 * 1024)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (stream.size() + count > MAX_BYTES) return null
            stream.write(buffer, 0, count)
          }
        }
        val bytes = stream.toByteArray()
        if (bytes.isEmpty()) return null
        val mime = when {
          contentType == "image/png" -> "image/png"
          contentType == "image/webp" -> "image/webp"
          contentType == "image/gif" -> "image/gif"
          contentType == "image/heic" -> "image/heic"
          contentType == "image/avif" -> "image/avif"
          else -> "image/jpeg"
        }
        return Source(bytes, mime)
      } finally {
        connection.disconnect()
      }
    }
    return null
  }
}
