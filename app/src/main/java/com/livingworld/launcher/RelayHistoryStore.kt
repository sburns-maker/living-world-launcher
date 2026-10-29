package com.livingworld.launcher

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/**
 * Private, bounded cache of *notification-derived* Google Messages snippets.
 * This is NOT Google Messages history, does not access RCS keys, and stores no
 * credentials, attachment URIs or PendingIntents.
 * AndroidManifest disables app backups; messages stay only on this device.
 */
data class RelayEvent(
  val sender: String,
  val body: String,
  val at: Long,
  val outgoing: Boolean
)
data class RelayMessage(
  val id: String,
  val sender: String,
  val body: String,
  val at: Long,
  val outgoing: Boolean
)
data class RelayThread(
  val id: String,
  val title: String,
  val preview: String,
  val lastAt: Long,
  val unread: Int
)

object RelayHistoryStore {
  private const val DB_NAME = "google_messages_notification_cache.db"
  private const val MAX_AGE = 14L * 24L * 60L * 60L * 1000L
  private const val MAX_MESSAGES = 1200
  private const val MAX_THREADS = 80

  private class Store(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
      db.execSQL("""CREATE TABLE threads (
        id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL,
        preview TEXT NOT NULL, last_at INTEGER NOT NULL, unread INTEGER NOT NULL DEFAULT 0
      )""")
      db.execSQL("""CREATE TABLE messages (
        id TEXT PRIMARY KEY NOT NULL, thread_id TEXT NOT NULL,
        sender TEXT NOT NULL, body TEXT NOT NULL,
        at INTEGER NOT NULL, outgoing INTEGER NOT NULL
      )""")
      db.execSQL("CREATE INDEX messages_by_thread ON messages(thread_id, at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
  }

  private fun id(thread: String, e: RelayEvent): String {
    val source = "$thread|${e.at}|${e.sender}|${e.outgoing}|${e.body}"
    return MessageDigest.getInstance("SHA-256")
      .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }

  fun record(context: Context, threadId: String, label: String, events: List<RelayEvent>) {
    if (threadId.isBlank() || events.isEmpty()) return
    val now = System.currentTimeMillis()
    val db = Store(context).writableDatabase
    db.beginTransaction()
    try {
      val valid = events.takeLast(30).mapNotNull { event ->
        val body = event.body.trim().take(4000)
        val time = event.at.coerceIn(now - MAX_AGE, now + 60_000)
        if (body.isBlank()) null
        else event.copy(body = body, sender = event.sender.take(120), at = time)
      }
      if (valid.isNotEmpty()) {
        var incomingAdded = 0
        for (event in valid) {
          val values = ContentValues().apply {
            put("id", id(threadId, event))
            put("thread_id", threadId)
            put("sender", event.sender)
            put("body", event.body)
            put("at", event.at)
            put("outgoing", if (event.outgoing) 1 else 0)
          }
          val inserted = db.insertWithOnConflict(
            "messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
          if (inserted != -1L && !event.outgoing) incomingAdded++
        }
        val last = valid.maxByOrNull { it.at }!!
        val prior = db.rawQuery(
          "SELECT last_at, unread FROM threads WHERE id = ?", arrayOf(threadId)
        ).use { cursor ->
          if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getInt(1)
          else null
        }
        val latest = prior == null || last.at >= prior.first
        val values = ContentValues().apply {
          put("id", threadId)
          put("title", label.trim().take(120).ifBlank { "Message" })
          put("preview", if (latest) last.body.take(220) else "")
          put("last_at", if (latest) last.at else prior!!.first)
          put("unread", ((prior?.second ?: 0) + incomingAdded).coerceAtMost(999))
        }
        if (!latest) values.remove("preview")
        if (prior == null) db.insert("threads", null, values)
        else db.update("threads", values, "id = ?", arrayOf(threadId))
      }
      db.delete("messages", "at < ?", arrayOf((now - MAX_AGE).toString()))
      db.execSQL("""DELETE FROM messages WHERE id NOT IN (
        SELECT id FROM messages ORDER BY at DESC LIMIT $MAX_MESSAGES
      )""")
      db.execSQL("""DELETE FROM threads WHERE id NOT IN (
        SELECT id FROM threads ORDER BY last_at DESC LIMIT $MAX_THREADS
      )""")
      db.execSQL("DELETE FROM threads WHERE NOT EXISTS (SELECT 1 FROM messages WHERE thread_id = threads.id)")
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
      db.close()
    }
  }

  fun threads(context: Context): List<RelayThread> = try {
    Store(context).readableDatabase.use { db ->
      val out = mutableListOf<RelayThread>()
      db.rawQuery(
        "SELECT id, title, preview, last_at, unread FROM threads ORDER BY last_at DESC LIMIT $MAX_THREADS",
        null).use { c ->
        while (c.moveToNext()) out.add(RelayThread(
          c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4)
        ))
      }
      out
    }
  } catch (_: Exception) { emptyList() }

  fun messages(context: Context, threadId: String): List<RelayMessage> = try {
    Store(context).readableDatabase.use { db ->
      val out = mutableListOf<RelayMessage>()
      db.rawQuery(
        """SELECT id, sender, body, at, outgoing FROM messages
           WHERE thread_id = ? ORDER BY at DESC LIMIT 120""", arrayOf(threadId)
      ).use { c ->
        while (c.moveToNext()) out.add(RelayMessage(
          c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) == 1
        ))
      }
      out.asReversed()
    }
  } catch (_: Exception) { emptyList() }

  fun markRead(context: Context, threadId: String) {
    runCatching {
      Store(context).writableDatabase.use { db ->
        db.execSQL("UPDATE threads SET unread = 0 WHERE id = ?", arrayOf(threadId))
      }
    }
  }

  fun clear(context: Context) {
    runCatching {
      Store(context).writableDatabase.use { db ->
        db.delete("messages", null, null)
        db.delete("threads", null, null)
      }
    }
  }
}
