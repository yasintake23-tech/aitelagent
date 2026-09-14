package com.example.data.security

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lightweight persistent diagnostic log for agent/runtime troubleshooting. */
object AgentLogStore {
    data class Entry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    )

    private const val PREFS = "agent_diagnostic_logs"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 500

    fun record(context: Context, level: String, tag: String, message: String) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrElse { JSONArray() }
            val obj = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("level", level)
                put("tag", tag)
                put("message", sanitize(message))
            }
            current.put(obj)
            val start = (current.length() - MAX_ENTRIES).coerceAtLeast(0)
            val trimmed = JSONArray()
            for (i in start until current.length()) trimmed.put(current.getJSONObject(i))
            prefs.edit().putString(KEY, trimmed.toString()).apply()
        }
    }

    fun read(context: Context): List<Entry> = runCatching {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        buildList {
            for (i in array.length() - 1 downTo 0) {
                val o = array.getJSONObject(i)
                add(Entry(o.optLong("timestamp"), o.optString("level"), o.optString("tag"), o.optString("message")))
            }
        }
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr", "TR")).format(Date(timestamp))

    private fun sanitize(message: String): String = Regex(
        "(?i)(api[_ -]?key|token|authorization|bearer)\\s*[:=]\\s*\\S+"
    ).replace(message) { match ->
        "${match.groupValues[1]}=[REDACTED]"
    }.take(1200)
}
