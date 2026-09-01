package icu.dbeidachazi.eir.presentation

import android.content.Context

/** Adapter boundary for Samsung Health Sensor SDK's SKIN_TEMPERATURE_ON_DEMAND. */
internal object SkinTemperatureOnDemand {
    fun request(context: Context, onFinished: () -> Unit = {}) {
        WatchLogStore.append(context, "skin_temperature_requested", mapOf(
            "type" to "SKIN_TEMPERATURE_ON_DEMAND",
            "result" to "sdk_not_installed"
        ))
        onFinished()
    }
}
