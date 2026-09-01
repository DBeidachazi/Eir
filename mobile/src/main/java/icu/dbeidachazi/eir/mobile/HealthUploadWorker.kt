package icu.dbeidachazi.eir.mobile

import android.content.Context
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
    const val endpoint = "https://eir.dbeidachazi.icu/api/health"
    const val deviceId = "eir-watch"
    const val secret = "your-secret-key-here"
}

class HealthUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
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
                put("sleepState", snapshot.samsungSleepState ?: snapshot.sleepState)
                putNullable("oxygenSaturation", snapshot.oxygenSaturation)
                putNullable("bodyTemperatureCelsius", snapshot.bodyTemperatureCelsius ?: snapshot.skinTemperatureCelsius)
            })
        }.toString()

        runCatching {
            val connection = (URL(PhoneUploadConfig.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            connection.disconnect()
            if (code in 200..299) {
                HealthDataStore.recordUpload(applicationContext, "已上报 HTTP " + code)
                Result.success()
            } else {
                HealthDataStore.recordUpload(applicationContext, "上报失败 HTTP " + code)
                Result.retry()
            }
        }.getOrElse { error ->
            HealthDataStore.recordUpload(applicationContext, "上报失败：" + (error.message ?: error.javaClass.simpleName))
            Result.retry()
        }
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
        if (intent.action == android.content.Intent.ACTION_BOOT_COMPLETED) {
            PhoneUploadScheduler.ensureScheduled(context)
        }
    }
}
