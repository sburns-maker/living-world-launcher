package com.livingworld.launcher

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Native AI photo viewer. Downloads the original available bytes for saving,
 * decodes a bounded high-resolution bitmap for pinch zoom, and never uploads.
 */
class WorldPhotoViewerActivity : Activity() {
  private val worker = Executors.newSingleThreadExecutor()
  private var original: WorldImageLoader.Source? = null
  private lateinit var image: ZoomPhoto
  private lateinit var save: TextView
  private lateinit var status: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = Color.BLACK
    window.navigationBarColor = Color.BLACK
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(Color.BLACK)
      fitsSystemWindows = true
    }
    setContentView(root)
    val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    fun button(label: String, action: () -> Unit) = TextView(this).apply {
      text = label
      textSize = 16f
      setTextColor(Color.WHITE)
      gravity = Gravity.CENTER
      minWidth = UiKit.dp(this@WorldPhotoViewerActivity, 56)
      minHeight = UiKit.dp(this@WorldPhotoViewerActivity, 52)
      setOnClickListener { action() }
    }
    bar.addView(button("‹") { finish() })
    bar.addView(TextView(this).apply {
      text = "Photo"
      textSize = 18f
      gravity = Gravity.CENTER_VERTICAL
      setTextColor(Color.WHITE)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    save = button("Save") { saveOriginal() }
    save.isEnabled = false
    save.alpha = .4f
    bar.addView(save)
    root.addView(bar)
    val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    image = ZoomPhoto(this)
    stage.addView(image, FrameLayout.LayoutParams(-1, -1))
    status = TextView(this).apply {
      text = "Loading photo…"
      gravity = Gravity.CENTER
      setTextColor(Color.LTGRAY)
      textSize = 15f
    }
    stage.addView(status, FrameLayout.LayoutParams(-1, -1))
    root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
    root.addView(TextView(this).apply {
      text = "Pinch to zoom  ·  Double tap to reset"
      gravity = Gravity.CENTER
      textSize = 12f
      setTextColor(Color.LTGRAY)
      setPadding(0, 8, 0, 24)
    })

    val link = intent.getStringExtra(EXTRA_URL).orEmpty()
    if (WorldImageLoader.resolve(link) == null) {
      status.text = "This photo link is unavailable."
      return
    }
    worker.execute {
      val fetched = runCatching { WorldImageLoader.fetch(link) }.getOrNull()
      val decoded = fetched?.bitmap(3500)
      runOnUiThread {
        if (isFinishing || isDestroyed) { decoded?.recycle(); return@runOnUiThread }
        if (fetched == null || decoded == null) {
          status.text = "Unable to load photo. Try opening it from the full Living World chat."
          return@runOnUiThread
        }
        original = fetched
        image.setImageBitmap(decoded)
        status.visibility = View.GONE
        save.isEnabled = true
        save.alpha = 1f
      }
    }
  }

  private fun extension(mime: String) = when (mime) {
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/heic" -> "heic"
    "image/avif" -> "avif"
    else -> "jpg"
  }

  private fun saveOriginal() {
    val photo = original ?: return
    val name = "LivingWorld_" + System.currentTimeMillis() + "." + extension(photo.mime)
    if (Build.VERSION.SDK_INT < 29) {
      startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = photo.mime
        putExtra(Intent.EXTRA_TITLE, name)
      }, REQUEST_EXPORT)
      return
    }
    save.isEnabled = false
    worker.execute {
      val ok = runCatching {
        val values = ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, name)
          put(MediaStore.Images.Media.MIME_TYPE, photo.mime)
          put(MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/Living World")
          put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
          ?: error("Unable to create picture")
        try {
          contentResolver.openOutputStream(uri)?.use { it.write(photo.bytes) }
            ?: error("Unable to write picture")
          contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
          }, null, null)
          true
        } catch (e: Exception) {
          contentResolver.delete(uri, null, null)
          throw e
        }
      }.getOrDefault(false)
      runOnUiThread {
        if (!isFinishing && !isDestroyed) {
          save.isEnabled = true
          Toast.makeText(this, if (ok) "Saved to Pictures / Living World"
            else "Could not save photo", Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  @Deprecated("Deprecated in Java")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
    val uri = data?.data ?: return
    val bytes = original?.bytes ?: return
    worker.execute {
      val ok = runCatching {
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
          ?: error("Unable to write picture")
        true
      }.getOrDefault(false)
      runOnUiThread {
        if (!isFinishing && !isDestroyed)
          Toast.makeText(this, if (ok) "Photo saved" else "Could not save photo",
            Toast.LENGTH_LONG).show()
      }
    }
  }

  override fun onDestroy() {
    worker.shutdownNow()
    original = null
    super.onDestroy()
  }

  companion object {
    const val EXTRA_URL = "photo_url"
    private const val REQUEST_EXPORT = 711
  }
}

/** Pinch zoom with a 5x limit and constrained panning. */
private class ZoomPhoto(context: android.content.Context) : android.widget.ImageView(context) {
  private val display = Matrix()
  private var zoom = 1f
  private var downX = 0f
  private var downY = 0f
  private val pinch = ScaleGestureDetector(context,
    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScale(d: ScaleGestureDetector): Boolean {
        val value = (zoom * d.scaleFactor).coerceIn(1f, 5f)
        display.postScale(value / zoom, value / zoom, d.focusX, d.focusY)
        zoom = value
        clamp()
        imageMatrix = display
        return true
      }
    })
  private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(e: MotionEvent) = true
    override fun onDoubleTap(e: MotionEvent): Boolean {
      if (zoom > 1.05f) reset()
      else {
        zoom = 2f
        display.postScale(2f, 2f, e.x, e.y)
        clamp()
        imageMatrix = display
      }
      return true
    }
  })

  init {
    scaleType = ScaleType.MATRIX
    setBackgroundColor(Color.BLACK)
  }
  override fun setImageBitmap(bm: Bitmap?) {
    super.setImageBitmap(bm)
    post { reset() }
  }
  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    post { reset() }
  }
  private fun reset() {
    val d = drawable ?: return
    if (width < 1 || height < 1 || d.intrinsicWidth < 1 || d.intrinsicHeight < 1) return
    val src = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
    val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
    display.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER)
    zoom = 1f
    imageMatrix = display
  }
  private fun clamp() {
    val d = drawable ?: return
    val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
    display.mapRect(rect)
    val dx = if (rect.width() <= width) width / 2f - rect.centerX()
      else if (rect.left > 0f) -rect.left else if (rect.right < width) width - rect.right else 0f
    val dy = if (rect.height() <= height) height / 2f - rect.centerY()
      else if (rect.top > 0f) -rect.top else if (rect.bottom < height) height - rect.bottom else 0f
    display.postTranslate(dx, dy)
  }
  override fun onTouchEvent(event: MotionEvent): Boolean {
    taps.onTouchEvent(event)
    pinch.onTouchEvent(event)
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
        downX = event.x
        downY = event.y
      }
      MotionEvent.ACTION_MOVE -> {
        if (!pinch.isInProgress && event.pointerCount == 1 && zoom > 1f) {
          display.postTranslate(event.x - downX, event.y - downY)
          clamp()
          imageMatrix = display
        }
        downX = event.x
        downY = event.y
      }
    }
    return true
  }
}
