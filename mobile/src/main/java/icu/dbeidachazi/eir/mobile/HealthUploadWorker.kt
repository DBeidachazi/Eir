package icu.dbeidachazi.eir.mobile

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

internal object PhoneUploadConfig {
    const val publicEndpoint = "https://eir.dbeidachazi.icu/api/health"
    const val lanEndpoint = "http://192.168.1.111:3000/api/health"
    const val deviceId = "eir-watch"
    const val secret = "your-secret-key-here"

    fun endpoints(context: Context): List<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val linkProperties = manager?.getLinkProperties(manager.activeNetwork)
        val onHomeLan = linkProperties?.linkAddresses?.any {
            it.address.hostAddress?.startsWith("192.168.1.") == true
        } == true
        return if (onHomeLan) listOf(lanEndpoint, publicEndpoint) else listOf(publicEndpoint, lanEndpoint)
    }
}

class HealthUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Refresh Samsung Health in the worker so uploads do not depend on the
        // dashboard being opened. This uses already granted permissions.
        runCatching { SamsungHealthReader.readBackground(applicationContext) }
            .onSuccess { result ->
                HealthDataStore.saveSamsungRead(
                    applicationContext,
                    result.heartRateBpm,
                    result.steps,
                    result.caloriesKcal,
                    result.sleepState,
                    result.oxygenSaturation,
                    result.bodyTemperatureCelsius,
                    result.capturedAt,
                    result.workoutCount,
                    result.latestWorkout
                )
            }
            .onFailure {
                // Do not leave an old synchronized value looking current after
                // a provider/permission failure.
                HealthDataStore.saveSamsungRead(
                    applicationContext, null, null, null, null, null, null,
                    System.currentTimeMillis(), 0, null
                )
            }
        val snapshot = HealthDataStore.read(applicationContext)
            ?: return@withContext Result.success()
        val payload = JSONObject().apply {
            put("secret", PhoneUploadConfig.secret)
            put("device", PhoneUploadConfig.deviceId)
            put("capturedAt", snapshot.capturedAt)
            put("metrics", JSONObject().apply {
                // Samsung Health on the phone is authoritative for historical metrics.
                putNullable("heartRateBpm", snapshot.samsungHeartRateBpm ?: snapshot.heartRateBpm)
                putNullable("steps", snapshot.samsungSteps ?: snapshot.steps)
                putNullable("caloriesKcal", snapshot.samsungCaloriesKcal ?: snapshot.caloriesKcal)
                putNullable("distanceMeters", snapshot.distanceMeters)
                // Sleep and skin temperature are watch realtime signals.
                put("sleepState", snapshot.sleepState)
                put("watchReceivedAt", snapshot.watchReceivedAt)
                putNullable("skinTemperatureCelsius", snapshot.skinTemperatureCelsius)
                putNullable("samsungSleepState", snapshot.samsungSleepState)
                putNullable("oxygenSaturation", snapshot.oxygenSaturation)
                putNullable("samsungBodyTemperatureCelsius", snapshot.bodyTemperatureCelsius)
                // The reader combines Samsung skin/body temperature streams;
                // keep a stable skin-temperature field for downstream panels.
                putNullable("samsungSkinTemperatureCelsius", snapshot.bodyTemperatureCelsius)
            })
        }.toString()

        var lastFailure: Throwable? = null
        for (endpoint in PhoneUploadConfig.endpoints(applicationContext)) {
            try {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Eir-Device", PhoneUploadConfig.deviceId)
                    setRequestProperty("X-Eir-Device-Token", PhoneUploadConfig.secret)
                }
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                connection.disconnect()
                if (code in 200..299) {
                    HealthDataStore.recordUpload(applicationContext, "已上报 ${endpoint} HTTP $code")
                    return@withContext Result.success()
                }
                lastFailure = IllegalStateException("HTTP $code from $endpoint")
            } catch (error: Throwable) {
                lastFailure = error
            }
        }
        HealthDataStore.recordUpload(applicationContext, "上报失败：${lastFailure?.message ?: "无可用端点"}")
        Result.retry()
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }
}

internal object PhoneUploadScheduler {
    private const val PERIODIC = "eir-phone-health-periodic"
    private const val IMMEDIATE = "eir-phone-health-immediate"

    fun ensureScheduled(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HealthUploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        )
    }

    fun runNow(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HealthUploadWorker>().setConstraints(constraints).build()
        )
    }
}

class HealthBootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (intent.action == android.content.Intent.ACTION_BOOT_COMPLETED ||
            intent.action == android.content.Intent.ACTION_MY_PACKAGE_REPLACED) {
            PhoneUploadScheduler.ensureScheduled(context)
        }
    }
}

/** Schedules uploads as soon as the phone process is created, without a UI launch. */
class EirMobileApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        PhoneUploadScheduler.ensureScheduled(this)
    }
}
