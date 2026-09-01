package icu.dbeidachazi.eir.mobile

import android.content.Context
import android.content.Intent
import com.google.android.gms.wearable.DataMap
import org.json.JSONObject

data class PhoneHealthSnapshot(
    val capturedAt: Long,
    val heartRateBpm: Double?,
    val steps: Long?,
    val caloriesKcal: Double?,
    val distanceMeters: Double?,
    val sleepState: String,
    val sleepStateChangedAt: Long,
    val skinTemperatureCelsius: Double?,
    val oxygenSaturation: Double?,
    val bodyTemperatureCelsius: Double?,
    val samsungHeartRateBpm: Double?,
    val samsungSteps: Long?,
    val samsungCaloriesKcal: Double?,
    val samsungSleepState: String?,
    val samsungCapturedAt: Long
)

internal object HealthDataStore {
    const val ACTION_UPDATED = "icu.dbeidachazi.eir.mobile.HEALTH_UPDATED"
    private const val PREFS = "phone_health"
    private const val SNAPSHOT = "snapshot"
    private const val STATUS = "upload_status"
    private const val SENT_AT = "upload_sent_at"

    fun saveFromDataMap(context: Context, dataMap: DataMap) {
        val snapshot = readJson(context)
        snapshot.put("capturedAt", dataMap.getLong("capturedAt", System.currentTimeMillis()))
        // The watch no longer owns these metrics; discard legacy values when a new watch snapshot arrives.
        snapshot.remove("heartRateBpm")
        snapshot.remove("steps")
        snapshot.remove("caloriesKcal")
        snapshot.remove("distanceMeters")
        dataMap.doubleOrNull("heartRateBpm")?.let { snapshot.put("heartRateBpm", it) }
        dataMap.longOrNull("steps")?.let { snapshot.put("steps", it) }
        dataMap.doubleOrNull("caloriesKcal")?.let { snapshot.put("caloriesKcal", it) }
        dataMap.doubleOrNull("distanceMeters")?.let { snapshot.put("distanceMeters", it) }
        if (dataMap.containsKey("sleepState")) snapshot.put("sleepState", dataMap.getString("sleepState", "未知"))
        if (dataMap.containsKey("sleepStateChangedAt")) snapshot.put("sleepStateChangedAt", dataMap.getLong("sleepStateChangedAt", 0L))
        dataMap.doubleOrNull("skinTemperatureCelsius")?.let { snapshot.put("skinTemperatureCelsius", it) }
        prefs(context).edit().putString(SNAPSHOT, snapshot.toString()).apply()
        broadcast(context)
    }

    fun saveSamsungRead(context: Context, oxygenSaturation: Double?, bodyTemperatureCelsius: Double?) {
        saveSamsungRead(context, null, null, null, null, null, null, null)
    }

    fun saveSamsungRead(
        context: Context,
        heartRateBpm: Double?,
        steps: Long?,
        caloriesKcal: Double?,
        sleepState: String?,
        oxygenSaturation: Double?,
        bodyTemperatureCelsius: Double?,
        capturedAt: Long?
    ) {
        if (listOf(heartRateBpm, steps, caloriesKcal, sleepState, oxygenSaturation, bodyTemperatureCelsius).all { it == null }) return
        val snapshot = readJson(context)
        heartRateBpm?.let { snapshot.put("samsungHeartRateBpm", it) }
        steps?.let { snapshot.put("samsungSteps", it) }
        caloriesKcal?.let { snapshot.put("samsungCaloriesKcal", it) }
        sleepState?.let { snapshot.put("samsungSleepState", it) }
        oxygenSaturation?.let { snapshot.put("oxygenSaturation", it) }
        bodyTemperatureCelsius?.let { snapshot.put("bodyTemperatureCelsius", it) }
        snapshot.put("samsungCapturedAt", capturedAt ?: System.currentTimeMillis())
        prefs(context).edit().putString(SNAPSHOT, snapshot.toString()).apply()
        broadcast(context)
    }

    fun read(context: Context): PhoneHealthSnapshot? {
        val raw = prefs(context).getString(SNAPSHOT, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            PhoneHealthSnapshot(
                capturedAt = json.optLong("capturedAt"),
                heartRateBpm = json.doubleOrNull("heartRateBpm"),
                steps = json.longOrNull("steps"),
                caloriesKcal = json.doubleOrNull("caloriesKcal"),
                distanceMeters = json.doubleOrNull("distanceMeters"),
                sleepState = json.optString("sleepState", "未知"),
                sleepStateChangedAt = json.optLong("sleepStateChangedAt", 0L),
                skinTemperatureCelsius = json.doubleOrNull("skinTemperatureCelsius"),
                oxygenSaturation = json.doubleOrNull("oxygenSaturation"),
                bodyTemperatureCelsius = json.doubleOrNull("bodyTemperatureCelsius"),
                samsungHeartRateBpm = json.doubleOrNull("samsungHeartRateBpm"),
                samsungSteps = json.longOrNull("samsungSteps"),
                samsungCaloriesKcal = json.doubleOrNull("samsungCaloriesKcal"),
                samsungSleepState = if (json.has("samsungSleepState") && !json.isNull("samsungSleepState")) {
                    json.optString("samsungSleepState")
                } else null,
                samsungCapturedAt = json.optLong("samsungCapturedAt", 0L)
            )
        }.getOrNull()
    }

    fun recordUpload(context: Context, message: String) {
        prefs(context).edit()
            .putString(STATUS, message)
            .putLong(SENT_AT, System.currentTimeMillis())
            .apply()
        broadcast(context)
    }

    fun uploadStatus(context: Context): String =
        prefs(context).getString(STATUS, "尚未上报") ?: "尚未上报"

    fun uploadSentAt(context: Context): Long = prefs(context).getLong(SENT_AT, 0L)

    private fun broadcast(context: Context) {
        context.sendBroadcast(Intent(ACTION_UPDATED).setPackage(context.packageName))
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readJson(context: Context): JSONObject {
        val raw = prefs(context).getString(SNAPSHOT, null)
        return runCatching { if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw) }
            .getOrElse { JSONObject() }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key)

    private fun JSONObject.longOrNull(key: String): Long? =
        if (isNull(key)) null else optLong(key)

    private fun DataMap.doubleOrNull(key: String): Double? =
        if (containsKey(key)) getDouble(key) else null

    private fun DataMap.longOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null
}
