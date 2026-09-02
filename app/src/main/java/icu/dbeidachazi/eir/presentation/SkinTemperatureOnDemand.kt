package icu.dbeidachazi.eir.presentation

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import java.util.concurrent.atomic.AtomicBoolean

/** One-shot Samsung Sensor SDK skin-temperature measurement. */
internal object SkinTemperatureOnDemand {
    const val ADDITIONAL_HEALTH_PERMISSION =
        "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"

    private const val TIMEOUT_MS = 30_000L
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    fun requiredPermissions(): Array<String> = arrayOf(
        android.Manifest.permission.BODY_SENSORS,
        ADDITIONAL_HEALTH_PERMISSION
    )

    fun request(context: Context, onFinished: () -> Unit = {}) {
        if (!running.compareAndSet(false, true)) {
            WatchLogStore.append(context, "skin_temperature_ignored", mapOf("reason" to "already_running"))
            return
        }

        val appContext = context.applicationContext
        var service: HealthTrackingService? = null
        var tracker: HealthTracker? = null
        val timeout = Runnable {
            finish(appContext, service, tracker, "timeout", mapOf("timeoutMs" to TIMEOUT_MS.toString()), onFinished)
        }

        fun fail(reason: String, details: Map<String, String> = emptyMap()) {
            finish(appContext, service, tracker, reason, details, onFinished)
        }

        val connectionListener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                val connected = service ?: return fail("connection_missing_service")
                runCatching {
                    val capability = connected.getTrackingCapability()
                    val supported = capability.supportHealthTrackerTypes
                    WatchLogStore.append(appContext, "skin_temperature_capabilities", mapOf(
                        "version" to capability.version,
                        "supported" to supported.joinToString(",")
                    ))
                    if (HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND !in supported) {
                        fail("unsupported", mapOf("type" to "SKIN_TEMPERATURE_ON_DEMAND"))
                        return
                    }
                    tracker = connected.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)
                    tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                        override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                            var saved = false
                            dataPoints.forEach { point ->
                                val objectTemp = point.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                                val ambientTemp = point.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
                                val status = point.getValue(ValueKey.SkinTemperatureSet.STATUS)
                                WatchLogStore.append(appContext, "skin_temperature_data", mapOf(
                                    "timestamp" to point.timestamp.toString(),
                                    "objectCelsius" to (objectTemp?.toString() ?: "null"),
                                    "ambientCelsius" to (ambientTemp?.toString() ?: "null"),
                                    "status" to (status?.toString() ?: "null")
                                ))
                                if (!saved && objectTemp != null && objectTemp.isFinite()) {
                                    HealthReportStore.saveSkinTemperature(
                                        appContext,
                                        objectTemp.toDouble(),
                                        point.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
                                    )
                                    saved = true
                                }
                            }
                            if (saved) finish(appContext, service, tracker, "success", emptyMap(), onFinished)
                        }

                        override fun onFlushCompleted() {
                            WatchLogStore.append(appContext, "skin_temperature_flush_completed")
                        }

                        override fun onError(error: HealthTracker.TrackerError?) {
                            fail("tracker_error", mapOf("error" to (error?.name ?: "unknown")))
                        }
                    })
                    WatchLogStore.append(appContext, "skin_temperature_listener_registered", mapOf(
                        "type" to "SKIN_TEMPERATURE_ON_DEMAND"
                    ))
                    handler.postDelayed(timeout, TIMEOUT_MS)
                }.onFailure { error ->
                    fail("tracker_setup_failed", mapOf("error" to (error.message ?: error.javaClass.simpleName)))
                }
            }

            override fun onConnectionEnded() {
                WatchLogStore.append(appContext, "skin_temperature_connection_ended")
            }

            override fun onConnectionFailed(error: HealthTrackerException?) {
                fail("connection_failed", mapOf("error" to (error?.message ?: "unknown")))
            }
        }

        WatchLogStore.append(appContext, "skin_temperature_requested", mapOf(
            "type" to "SKIN_TEMPERATURE_ON_DEMAND",
            "sdk" to "1.4.1"
        ))
        runCatching {
            service = HealthTrackingService(connectionListener, appContext)
            service?.connectService()
        }.onFailure { error ->
            fail("connection_start_failed", mapOf("error" to (error.message ?: error.javaClass.simpleName)))
        }
    }

    private fun finish(
        context: Context,
        service: HealthTrackingService?,
        tracker: HealthTracker?,
        reason: String,
        details: Map<String, String>,
        onFinished: () -> Unit
    ) {
        if (!running.compareAndSet(true, false)) return
        handler.removeCallbacksAndMessages(null)
        runCatching { tracker?.unsetEventListener() }
        runCatching { service?.disconnectService() }
        WatchLogStore.append(context, "skin_temperature_finished", details + ("reason" to reason))
        onFinished()
    }
}
