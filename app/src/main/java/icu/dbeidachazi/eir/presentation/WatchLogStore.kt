package icu.dbeidachazi.eir.presentation

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class WatchLogEntry(val time: Long, val event: String, val details: String)

internal object WatchLogStore {
    private const val PREFS = "watch_logs"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 100
    private const val TAG = "EirWatchHealth"

    fun append(context: Context, event: String, details: Map<String, String> = emptyMap()) {
        val detailText = details.entries.joinToString(" ") { "${it.key}=${it.value}" }
        Log.i(TAG, "$event${if (detailText.isBlank()) "" else " $detailText"}")
        val entries = read(context).toMutableList()
        entries.add(0, WatchLogEntry(System.currentTimeMillis(), event, detailText))
        val json = JSONArray()
        entries.take(MAX_ENTRIES).forEach {
            json.put(JSONObject().apply {
                put("time", it.time)
                put("event", it.event)
                put("details", it.details)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
        context.sendBroadcast(android.content.Intent(HealthReportStore.ACTION_UPDATED).setPackage(context.packageName))
    }

    fun read(context: Context): List<WatchLogEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                WatchLogEntry(item.optLong("time"), item.optString("event"), item.optString("details"))
            }.getOrNull()
        }
    }
}
