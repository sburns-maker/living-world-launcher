package com.livingworld.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.ImageView

/** Lazy, sampled inline preview of system-provider MMS picture parts. */
object MmsThumbnail {
  fun view(context: Context, part: MmsMediaPart): ImageView? {
    if (!part.mimeType.startsWith("image/")) return null
    val bitmap = try {
      context.contentResolver.openInputStream(part.contentUri)?.use { input ->
        BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
          inSampleSize = 4
        })
      }
    } catch (_: Exception) { null } ?: return null
    return ImageView(context).apply {
      setImageBitmap(bitmap)
      adjustViewBounds = true
      maxWidth = (context.resources.displayMetrics.density * 200).toInt()
      maxHeight = (context.resources.displayMetrics.density * 200).toInt()
    }
  }
}
