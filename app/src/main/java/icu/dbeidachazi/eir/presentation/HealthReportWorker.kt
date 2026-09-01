package icu.dbeidachazi.eir.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityState
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Re-registers the sleep-only passive listener after boot. */
class HealthPassiveRegistrationWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACTIVITY_RECOGNITION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return@withContext Result.success()
        }
        runCatching {
            val client = HealthServices.getClient(applicationContext).passiveMonitoringClient
            val capabilities = client.getCapabilitiesAsync().get()
            WatchLogStore.append(applicationContext, "boot_passive_capabilities", mapOf(
                "userActivityStates" to capabilities.supportedUserActivityStates.joinToString(",")
            ))
            if (UserActivityState.USER_ACTIVITY_ASLEEP in capabilities.supportedUserActivityStates) {
                client.setPassiveListenerServiceAsync(
                    SleepPassiveListenerService::class.java,
                    PassiveListenerConfig.builder()
                        .setDataTypes(emptySet())
                        .setShouldUserActivityInfoBeRequested(true)
                        .build()
                ).get()
                WatchLogStore.append(applicationContext, "boot_sleep_listener_registered")
            }
            Result.success()
        }.getOrElse { error ->
            WatchLogStore.append(applicationContext, "boot_registration_error", mapOf("error" to (error.message ?: error.javaClass.simpleName)))
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "eir-health-passive-registration"
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<HealthPassiveRegistrationWorker>().build()
            )
        }
    }
}

class HealthBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) HealthPassiveRegistrationWorker.enqueue(context)
    }
}
