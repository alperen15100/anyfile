package com.arjun.gander

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recently opened files, limited to URIs whose read grant was persisted
 * (picker selections). Stored as JSON in SharedPreferences.
 */
object Recents {

    data class Entry(val uri: String, val name: String, val time: Long)

    private const val PREFS = "recents"
    private const val KEY = "items"
    private const val MAX = 25

    fun all(context: Context): List<Entry> {
        val granted = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        return load(context).filter { it.uri in granted }
    }

    fun add(context: Context, uri: Uri, name: String) {
        val key = uri.toString()
        val items = load(context).filter { it.uri != key }.toMutableList()
        items.add(0, Entry(key, name, System.currentTimeMillis()))
        save(context, items.take(MAX))
    }

    fun remove(context: Context, uri: String) {
        save(context, load(context).filter { it.uri != uri })
    }

    private fun load(context: Context): List<Entry> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]")!!
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("uri"), o.getString("name"), o.getLong("time"))
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, items: List<Entry>) {
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(
                JSONObject()
                    .put("uri", e.uri)
                    .put("name", e.name)
                    .put("time", e.time)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
