package icu.dbeidachazi.eir.mobile

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class HealthDataListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != WearHealthProtocol.PATH) return@forEach
            runCatching {
                HealthDataStore.saveFromDataMap(this, DataMapItem.fromDataItem(event.dataItem).dataMap)
            }.onFailure { error ->
                Log.e(TAG, "Failed to receive health snapshot", error)
            }
        }
    }

    companion object {
        private const val TAG = "EirHealthReceiver"
    }
}

internal object WearHealthProtocol {
    const val PATH = "/eir/health"
}
