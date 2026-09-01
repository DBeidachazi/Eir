package icu.dbeidachazi.eir.presentation

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

internal object WearHealthDataBridge {
    private const val PATH = "/eir/health"
    private const val TAG = "EirHealthBridge"
    private const val PREFS = "wear_bridge"
    private const val STATUS = "status"
    private const val LAST_PAYLOAD = "last_payload"

    fun send(context: Context, snapshot: HealthSnapshot) {
        val payloadKey = listOf(
            snapshot.capturedAt,
            snapshot.sleepState,
            snapshot.sleepStateChangedAt,
            snapshot.skinTemperatureCelsius
        ).joinToString("|")
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getString(LAST_PAYLOAD, null) == payloadKey) return
        preferences.edit().putString(LAST_PAYLOAD, payloadKey).apply()
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putLong("capturedAt", snapshot.capturedAt)
            dataMap.putString("sleepState", snapshot.sleepState)
            dataMap.putLong("sleepStateChangedAt", snapshot.sleepStateChangedAt)
            snapshot.skinTemperatureCelsius?.let { dataMap.putDouble("skinTemperatureCelsius", it) }
            dataMap.putLong("updatedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(STATUS, "已传给手机")
                    .apply()
                context.sendBroadcast(android.content.Intent(HealthReportStore.ACTION_UPDATED).setPackage(context.packageName))
            }
            .addOnFailureListener { error ->
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(STATUS, "手机未连接")
                    .apply()
                Log.w(TAG, "Phone is not reachable", error)
                context.sendBroadcast(android.content.Intent(HealthReportStore.ACTION_UPDATED).setPackage(context.packageName))
            }
    }

    fun status(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(STATUS, "等待手机") ?: "等待手机"
}
