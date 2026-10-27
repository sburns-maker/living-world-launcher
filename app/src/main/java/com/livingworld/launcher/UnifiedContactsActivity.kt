package com.livingworld.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.widget.*

/** Read-only unified contacts. The Android contacts permission is explicitly optional. */
class UnifiedContactsActivity : Activity() {
  private data class Person(val name: String, val phone: String?, val id: Long?, val fictional: Boolean)
  private val fictional = listOf("Ben", "Claire", "Jordan", "Maya", "Riley", "Tess")
    .map { Person(it, null, null, true) }
  private lateinit var list: LinearLayout
  private lateinit var search: EditText
  private lateinit var status: TextView
  private var permissionGranted = false
  private var people: List<Person> = fictional

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = UiKit.vertical(this, 20).apply { setBackgroundColor(UiKit.bg) }
    setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    root.addView(UiKit.clickable(this, "‹  Home") { finish() })
    root.addView(UiKit.gap(this, 20))
    root.addView(UiKit.text(this, "Contacts", 32f, UiKit.ink, true))
    root.addView(UiKit.text(this, "Real people and your world, together", 14f, UiKit.muted))
    root.addView(UiKit.gap(this, 16))
    search = EditText(this).apply {
      hint = "Search contacts"; isSingleLine = true
      setPadding(UiKit.dp(this@UnifiedContactsActivity, 15), 12, 12, 12)
      background = UiKit.surface(this@UnifiedContactsActivity)
    }
    root.addView(search)
    status = UiKit.text(this, "", 13f, UiKit.muted)
    root.addView(status)
    root.addView(UiKit.clickable(this, "Allow real contacts (optional)") {
      if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 101)
      else load()
    })
    root.addView(UiKit.gap(this, 15))
    list = UiKit.vertical(this)
    root.addView(list)
    search.addTextChangedListener(object : android.text.TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
      override fun afterTextChanged(s: android.text.Editable?) {}
    })
    load()
  }

  private fun load() {
    permissionGranted = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    val real = mutableListOf<Person>()
    if (permissionGranted) {
      try {
        contentResolver.query(
          ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
          arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER),
          null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE ASC"
        )?.use { cursor ->
          val seen = mutableSetOf<Long>()
          while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            if (!seen.add(id)) continue
            val name = cursor.getString(1) ?: continue
            val phone = cursor.getString(2)
            real.add(Person(name, phone, id, false))
          }
        }
      } catch (_: SecurityException) { permissionGranted = false; real.clear() }
    }
    people = (fictional + real).sortedBy { it.name.lowercase() }
    status.text = if (permissionGranted) "Android contacts shown locally · read-only" else "Real contacts hidden until you grant permission"
    render()
  }

  private fun render() {
    if (!::list.isInitialized || !::search.isInitialized) return
    list.removeAllViews()
    val filtered = people.filter { it.name.contains(search.text.toString(), ignoreCase = true) }
    for (person in filtered) {
      val row = UiKit.horizontal(this)
      val color = if (person.fictional) UiKit.lavender else Color.rgb(72, 116, 174)
      row.addView(UiKit.text(this, person.name.take(1), 23f, color, true).apply {
        gravity = Gravity.CENTER
        background = UiKit.surface(this@UnifiedContactsActivity,
          if (person.fictional) Color.rgb(237,232,250) else Color.rgb(232,241,251), 42)
      }, LinearLayout.LayoutParams(UiKit.dp(this, 50), UiKit.dp(this, 50)))
      val details = UiKit.vertical(this, 9)
      details.addView(UiKit.text(this, person.name, 17f, UiKit.ink, true))
      details.addView(UiKit.text(this,
        if (person.fictional) "Living World contact" else (person.phone ?: "Android contact"),
        13f, UiKit.muted))
      row.addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
      row.setOnClickListener { open(person) }
      row.isClickable = true; row.isFocusable = true
      list.addView(row)
      list.addView(UiKit.gap(this, 12))
    }
    if (filtered.isEmpty()) list.addView(UiKit.text(this, "No matching contacts", 15f, UiKit.muted))
  }

  private fun open(person: Person) {
    if (person.fictional) {
      // No fabricated phone numbers or external calls for fictional people.
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://screen-to-same-studio.lovable.app")))
    } else if (person.id != null) {
      val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, person.id.toString())
      startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, results)
    if (requestCode == 101) load()
  }
}
