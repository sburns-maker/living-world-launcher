package com.livingworld.launcher

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log

/**
 * Read-only MMS history from Android's system provider.
 *
 * This does NOT download carrier MMS or mutate the provider. Carrier WAP push,
 * PDU decoding, MMSC transport, and attachment persistence require separate
 * qualification before this app can take the default SMS role.
 */
data class MmsHistoryRow(
  val id: Long,
  val threadId: Long,
  val timestampMillis: Long,
  val outgoing: Boolean,
  val text: String,
  val mediaParts: List<MmsMediaPart>
)

data class MmsMediaPart(
  val partId: Long,
  val mimeType: String,
  val filename: String?,
  val contentUri: Uri
)

object MmsHistory {
  private const val TAG = "MmsHistory"
  private const val MAX_ROWS = 120
  private const val MAX_PARTS = 30

  fun messages(context: Context, threadId: Long, limit: Int = MAX_ROWS): List<MmsHistoryRow> {
    if (!SmsRole.isDefault(context) || threadId < 0) return emptyList()
    val rows = mutableListOf<MmsHistoryRow>()
    val projection = arrayOf(
      Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE,
      Telephony.Mms.MESSAGE_BOX
    )
    try {
      context.contentResolver.query(
        Telephony.Mms.CONTENT_URI, projection,
        "${Telephony.Mms.THREAD_ID} = ?", arrayOf(threadId.toString()),
        "${Telephony.Mms.DATE} DESC"
      )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
        val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
        val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
        val boxIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
        val cap = limit.coerceIn(1, MAX_ROWS)
        while (rows.size < cap && cursor.moveToNext()) {
          val id = cursor.getLong(idIndex)
          val (text, media) = parts(context, id)
          rows.add(MmsHistoryRow(
            id, cursor.getLong(threadIndex), cursor.getLong(dateIndex) * 1000L,
            cursor.getInt(boxIndex) != Telephony.Mms.MESSAGE_BOX_INBOX,
            text, media
          ))
        }
      }
    } catch (error: Exception) {
      Log.w(TAG, "Unable to read MMS history", error)
      return emptyList()
    }
    return rows.asReversed()
  }

  private fun parts(context: Context, messageId: Long): Pair<String, List<MmsMediaPart>> {
    val texts = mutableListOf<String>()
    val media = mutableListOf<MmsMediaPart>()
    val uri = Uri.parse("content://mms/$messageId/part")
    context.contentResolver.query(
      uri, arrayOf("_id", "ct", "text", "name", "fn"),
      null, null, null
    )?.use { cursor ->
      val idIndex = cursor.getColumnIndexOrThrow("_id")
      val typeIndex = cursor.getColumnIndexOrThrow("ct")
      val textIndex = cursor.getColumnIndexOrThrow("text")
      val nameIndex = cursor.getColumnIndexOrThrow("name")
      val fileIndex = cursor.getColumnIndexOrThrow("fn")
      var count = 0
      while (count++ < MAX_PARTS && cursor.moveToNext()) {
        val mime = cursor.getString(typeIndex) ?: continue
        if (mime.equals("text/plain", ignoreCase = true)) {
          val inlineText = cursor.getString(textIndex)
          val decoded = inlineText?.takeIf { it.isNotBlank() }
            ?: readTextPart(context, cursor.getLong(idIndex))
          decoded?.takeIf { it.isNotBlank() }?.let(texts::add)
        } else if (mime.startsWith("image/", ignoreCase = true) ||
                   mime.startsWith("video/", ignoreCase = true) ||
                   mime.startsWith("audio/", ignoreCase = true)) {
          val partId = cursor.getLong(idIndex)
          media.add(MmsMediaPart(
            partId, mime, cursor.getString(fileIndex) ?: cursor.getString(nameIndex),
            Uri.parse("content://mms/part/$partId")
          ))
        }
      }
    }
    return texts.joinToString("\n") to media
  }

  /** Some providers store text in the part stream rather than the text column. */
  private fun readTextPart(context: Context, partId: Long): String? {
    val uri = Uri.parse("content://mms/part/$partId")
    return try {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        val buffer = ByteArray(64 * 1024 + 1)
        var offset = 0
        while (offset < buffer.size) {
          val count = stream.read(buffer, offset, buffer.size - offset)
          if (count <= 0) break
          offset += count
        }
        if (offset > 64 * 1024) null
        else String(buffer, 0, offset, Charsets.UTF_8)
      }
    } catch (error: Exception) {
      Log.w(TAG, "Unable to read MMS text part", error)
      null
    }
  }
}
