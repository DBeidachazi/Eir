package icu.dbeidachazi.eir.mobile

import androidx.activity.ComponentActivity
import android.util.Log
import com.openwearables.health.sdk.OpenWearablesHealthSDK
import com.openwearables.health.sdk.ProviderReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

internal data class SamsungReadResult(
    val heartRateBpm: Double?,
    val steps: Long?,
    val caloriesKcal: Double?,
    val sleepState: String?,
    val oxygenSaturation: Double?,
    val bodyTemperatureCelsius: Double?,
    val capturedAt: Long,
    val message: String
)

/** Reads the newest records already synchronized into Samsung Health. */
internal object SamsungHealthReader {
    private const val TAG = "EirSamsung"
    private const val AUTH_PREFS = "samsung_health"
    private const val AUTH_REQUESTED = "read_permissions_requested_v2"
    private val types = listOf(
        "heartRate", "steps", "activeEnergy", "sleep", "oxygenSaturation",
        "bodyTemperature", "skinTemperature", "bloodGlucose", "bloodPressure",
        "flightsClimbed", "bodyMass", "bodyFatPercentage", "leanBodyMass",
        "height", "bmi", "water", "workout"
    )

    suspend fun readOnce(activity: ComponentActivity): SamsungReadResult = withContext(Dispatchers.IO) {
        val sdk = OpenWearablesHealthSDK.initialize(activity.applicationContext)
        sdk.setActivity(activity)
        sdk.logListener = { Log.i(TAG, it) }
        if (!sdk.setProvider("samsung")) {
            return@withContext SamsungReadResult(null, null, null, null, null, null, 0L, "手机未检测到可用的 Samsung Health")
        }

        val prefs = activity.getSharedPreferences(AUTH_PREFS, 0)
        val authorized = if (prefs.getBoolean(AUTH_REQUESTED, false)) {
            true
        } else {
            val result = runCatching { sdk.requestAuthorization(types) }.getOrDefault(false)
            // The Samsung dialog is a one-time user decision. Do not reopen it on every read.
            prefs.edit().putBoolean(AUTH_REQUESTED, true).apply()
            result
        }
        val logContext = activity.applicationContext
        emit(logContext, "--- Samsung Health read ${System.currentTimeMillis()} ---")
        val heartRateRecords = readAndLog(logContext, sdk, "heartRate")
        val stepRecords = readAndLog(logContext, sdk, "steps")
        val calorieRecords = readAndLog(logContext, sdk, "activeEnergy")
        val sleepResult = readSleepAndLog(logContext, sdk)
        val oxygenRecords = readAndLog(logContext, sdk, "oxygenSaturation")
        val temperatureRecords = readAndLog(logContext, sdk, "bodyTemperature")
        val skinTemperatureRecords = readAndLog(logContext, sdk, "skinTemperature")
        types.filter {
            it !in setOf("heartRate", "steps", "activeEnergy", "sleep", "oxygenSaturation", "bodyTemperature", "skinTemperature", "workout")
        }.forEach { readAndLog(logContext, sdk, it) }
        readWorkoutAndLog(logContext, sdk)
        val heartRate = heartRateRecords.data.records.maxByOrNull { it.endDate }?.value
        val steps = sumToday(stepRecords)?.toLong()
        val calories = sumToday(calorieRecords)
        val sleep = sleepResult.data.sleep.maxByOrNull { it.endDate }?.stage
        val oxygen = oxygenRecords.data.records.maxByOrNull { it.endDate }?.value
        val temperature = (skinTemperatureRecords.data.records + temperatureRecords.data.records)
            .maxByOrNull { it.endDate }?.value
        val values = listOfNotNull(heartRate, steps, calories, sleep, oxygen, temperature)
        val newest = System.currentTimeMillis()
        val permissionHint = if (authorized) "" else "（权限未全部授权）"
        val message = if (values.isEmpty()) {
            "Samsung Health 未返回已授权数据$permissionHint"
        } else {
            "已读取 ${values.size} 项 Samsung Health 数据$permissionHint"
        }
        SamsungReadResult(heartRate, steps, calories, sleep, oxygen, temperature, newest, message)
    }

    private suspend fun readAndLog(context: android.content.Context, sdk: OpenWearablesHealthSDK, type: String): ProviderReadResult {
        val result = runCatching { sdk.readData(type, limit = 1000) }
            .getOrElse {
                emit(context, "$type read failed: ${it.javaClass.simpleName}: ${it.message}")
                return ProviderReadResult(com.openwearables.health.sdk.UnifiedHealthData(), null)
            }
        emit(context, "$type: records=${result.data.records.size}, maxTimestamp=${result.maxTimestamp}")
        result.data.records.forEach { record ->
            emit(context, "$type record type=${record.type} value=${record.value} ${record.unit} start=${record.startDate} end=${record.endDate} device=${record.source.deviceName}/${record.source.deviceModel}")
        }
        return result
    }

    private suspend fun readSleepAndLog(context: android.content.Context, sdk: OpenWearablesHealthSDK): ProviderReadResult {
        val result = runCatching { sdk.readData("sleep", limit = 1000) }
            .getOrElse {
                emit(context, "sleep read failed: ${it.javaClass.simpleName}: ${it.message}")
                return ProviderReadResult(com.openwearables.health.sdk.UnifiedHealthData(), null)
            }
        emit(context, "sleep: entries=${result.data.sleep.size}, maxTimestamp=${result.maxTimestamp}")
        result.data.sleep.forEach { entry ->
            emit(context, "sleep entry stage=${entry.stage} start=${entry.startDate} end=${entry.endDate} device=${entry.source.deviceName}/${entry.source.deviceModel}")
        }
        return result
    }

    private suspend fun readWorkoutAndLog(context: android.content.Context, sdk: OpenWearablesHealthSDK) {
        val result = runCatching { sdk.readData("workout", limit = 1000) }
            .getOrElse {
                emit(context, "workout read failed: ${it.javaClass.simpleName}: ${it.message}")
                return
            }
        emit(context, "workout: entries=${result.data.workouts.size}, maxTimestamp=${result.maxTimestamp}")
        result.data.workouts.forEach { workout ->
            emit(context, "workout type=${workout.type} title=${workout.title} start=${workout.startDate} end=${workout.endDate} device=${workout.source.deviceName}/${workout.source.deviceModel}")
        }
    }

    private fun sumToday(result: ProviderReadResult): Double? {
        if (result.data.records.isEmpty()) return null
        val startOfDay = java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val total = result.data.records
            .filter { runCatching { Instant.parse(it.startDate).isAfter(startOfDay) || Instant.parse(it.startDate) == startOfDay }.getOrDefault(false) }
            .sumOf { it.value }
        return if (total == 0.0) null else total
    }

    private fun emit(context: android.content.Context, message: String) {
        Log.i(TAG, message)
        runCatching {
            context.openFileOutput("samsung-health.log", android.content.Context.MODE_APPEND).use {
                it.write((message + "\n").toByteArray(Charsets.UTF_8))
            }
        }
    }
}
