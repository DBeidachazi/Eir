package icu.dbeidachazi.eir.presentation

import android.content.Context
import android.content.Intent
import org.json.JSONObject

data class HealthSnapshot(
    val capturedAt: Long,
    val sleepState: String,
    val sleepStateChangedAt: Long,
    val skinTemperatureCelsius: Double?
)

internal object HealthReportStore {
    const val ACTION_UPDATED = "icu.dbeidachazi.eir.HEALTH_REPORT_UPDATED"
    private const val PREFS = "health_report"
    private const val SNAPSHOT = "latest_snapshot"

    fun saveSleepState(context: Context, sleepState: String, changedAt: Long) {
        val previous = readSnapshot(context)
        val json = JSONObject().apply {
            put("capturedAt", System.currentTimeMillis())
            put("sleepState", sleepState)
            put("sleepStateChangedAt", changedAt)
            putNullable("skinTemperatureCelsius", previous?.skinTemperatureCelsius)
        }
        prefs(context).edit().putString(SNAPSHOT, json.toString()).apply()
        readSnapshot(context)?.let { WearHealthDataBridge.send(context, it) }
        context.sendBroadcast(Intent(ACTION_UPDATED).setPackage(context.packageName))
    }

    fun saveSkinTemperature(context: Context, celsius: Double, measuredAt: Long = System.currentTimeMillis()) {
        val previous = readSnapshot(context)
        val json = JSONObject().apply {
            put("capturedAt", measuredAt)
            put("sleepState", previous?.sleepState ?: SleepStateStore.read(context))
            put("sleepStateChangedAt", previous?.sleepStateChangedAt ?: 0L)
            put("skinTemperatureCelsius", celsius)
        }
        prefs(context).edit().putString(SNAPSHOT, json.toString()).apply()
        readSnapshot(context)?.let { WearHealthDataBridge.send(context, it) }
        context.sendBroadcast(Intent(ACTION_UPDATED).setPackage(context.packageName))
    }

    fun readSnapshot(context: Context): HealthSnapshot? {
        val raw = prefs(context).getString(SNAPSHOT, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            HealthSnapshot(
                capturedAt = json.optLong("capturedAt"),
                sleepState = json.optString("sleepState", "未知"),
                sleepStateChangedAt = json.optLong("sleepStateChangedAt"),
                skinTemperatureCelsius = if (json.isNull("skinTemperatureCelsius")) null else json.optDouble("skinTemperatureCelsius")
            )
        }.getOrNull()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }
}
