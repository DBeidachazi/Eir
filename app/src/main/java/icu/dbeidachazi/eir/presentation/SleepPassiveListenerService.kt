package icu.dbeidachazi.eir.presentation

import android.content.Context
import android.content.Intent
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState

internal object SleepStateStore {
    const val ACTION_UPDATED = "icu.dbeidachazi.eir.SLEEP_STATE_UPDATED"
    private const val PREFS = "sleep_state"
    private const val VALUE = "value"
    private const val CHANGED_AT = "changed_at"
    private const val DEFAULT = "等待读取手表当前状态"

    fun read(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(VALUE, DEFAULT) ?: DEFAULT

    fun from(info: UserActivityInfo): String = when (info.userActivityState) {
        UserActivityState.USER_ACTIVITY_ASLEEP -> "正在睡眠"
        UserActivityState.USER_ACTIVITY_PASSIVE -> "静息/非运动"
        UserActivityState.USER_ACTIVITY_EXERCISE -> "运动中"
        else -> "暂时无法判断"
    }

    fun write(context: Context, value: String, changedAt: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(VALUE, value).putLong(CHANGED_AT, changedAt).apply()
        context.sendBroadcast(Intent(ACTION_UPDATED).setPackage(context.packageName))
    }
}

class SleepPassiveListenerService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        // No metric collection on the watch. Samsung Health on the phone owns those data.
        WatchLogStore.append(this, "unexpected_metric_callback", mapOf("ignored" to "true"))
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        val state = SleepStateStore.from(info)
        val changedAt = info.stateChangeTime?.toEpochMilli() ?: System.currentTimeMillis()
        SleepStateStore.write(this, state, changedAt)
        HealthReportStore.saveSleepState(this, state, changedAt)
        WatchLogStore.append(this, "user_activity", mapOf(
            "state" to state,
            "stateChangeTime" to changedAt.toString(),
            "callbackTime" to System.currentTimeMillis().toString()
        ))
    }

    override fun onPermissionLost() {
        SleepStateStore.write(this, "活动识别权限已丢失", System.currentTimeMillis())
        WatchLogStore.append(this, "permission_lost")
    }
}
