package icu.dbeidachazi.eir.presentation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityState
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Text
import icu.dbeidachazi.eir.presentation.theme.EirTheme

class MainActivity : ComponentActivity() {
    private lateinit var passiveClient: PassiveMonitoringClient
    private var sleepState by mutableStateOf("等待读取手表当前状态")
    private var status by mutableStateOf("请先授予活动识别权限")
    private var monitoringRegistered = false
    private var monitoringStarting = false
    private var receiverRegistered = false
    private var refreshVersion by mutableIntStateOf(0)

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            status = "权限已授予，正在监听睡眠状态"
            startSleepMonitoring()
        } else status = "需要活动识别权限才能判断睡眠状态"
    }

    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                SleepStateStore.ACTION_UPDATED -> sleepState = SleepStateStore.read(this@MainActivity)
                HealthReportStore.ACTION_UPDATED -> refreshVersion++
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        passiveClient = HealthServices.getClient(this).passiveMonitoringClient
        sleepState = SleepStateStore.read(this)
        ContextCompat.registerReceiver(this, updates, IntentFilter().apply {
            addAction(SleepStateStore.ACTION_UPDATED)
            addAction(HealthReportStore.ACTION_UPDATED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        if (hasActivityPermission()) startSleepMonitoring()
        setContent { EirTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        if (::passiveClient.isInitialized && hasActivityPermission()) startSleepMonitoring()
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(updates)
        receiverRegistered = false
        super.onDestroy()
    }

    private fun hasActivityPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACTIVITY_RECOGNITION
    ) == PackageManager.PERMISSION_GRANTED

    private fun startSleepMonitoring() {
        if (monitoringRegistered || monitoringStarting) return
        monitoringStarting = true
        sleepState = "正在检查手表是否支持睡眠状态"
        val future = passiveClient.getCapabilitiesAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { capabilities ->
                val states = capabilities.supportedUserActivityStates
                WatchLogStore.append(this, "passive_capabilities", mapOf("userActivityStates" to states.joinToString(",")))
                if (UserActivityState.USER_ACTIVITY_ASLEEP !in states) {
                    monitoringStarting = false
                    sleepState = "此手表未提供睡眠状态事件"
                    WatchLogStore.append(this, "sleep_unsupported")
                    return@onSuccess
                }
                val registration = passiveClient.setPassiveListenerServiceAsync(
                    SleepPassiveListenerService::class.java,
                    PassiveListenerConfig.builder()
                        .setDataTypes(emptySet())
                        .setShouldUserActivityInfoBeRequested(true)
                        .build()
                )
                registration.addListener({
                    runCatching { registration.get() }.onSuccess {
                        monitoringStarting = false
                        monitoringRegistered = true
                        sleepState = SleepStateStore.read(this)
                        status = "已开始监听睡眠状态"
                        WatchLogStore.append(this, "sleep_listener_registered")
                    }.onFailure { error ->
                        monitoringStarting = false
                        sleepState = "无法读取：${error.message ?: error.javaClass.simpleName}"
                        WatchLogStore.append(this, "sleep_listener_error", mapOf("error" to (error.message ?: error.javaClass.simpleName)))
                    }
                }, ContextCompat.getMainExecutor(this))
            }.onFailure { error ->
                monitoringStarting = false
                sleepState = "睡眠状态不可用：${error.message ?: error.javaClass.simpleName}"
                WatchLogStore.append(this, "sleep_capabilities_error", mapOf("error" to (error.message ?: error.javaClass.simpleName)))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @Composable
    private fun Screen() {
        refreshVersion
        val listState = rememberScalingLazyListState()
        val snapshot = HealthReportStore.readSnapshot(this)
        val logs = WatchLogStore.read(this).take(4)
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize().background(Color.Black), state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            autoCentering = AutoCenteringParams(itemIndex = 0), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Eir 手表状态", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(snapshot?.sleepState ?: sleepState, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Wear OS 睡眠事件", color = Color.LightGray, fontSize = 12.sp)
                }
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("皮肤温度", color = Color.LightGray, fontSize = 12.sp)
                    Text(snapshot?.skinTemperatureCelsius?.let { "%.1f °C".format(it) } ?: "暂无读数", color = Color.White, fontSize = 20.sp)
                    Text("按需测量需三星 Health Sensor SDK", color = Color.DarkGray, fontSize = 10.sp)
                }
            }
            item {
                Button(onClick = { SkinTemperatureOnDemand.request(this@MainActivity) { refreshVersion++ } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303030), contentColor = Color.White)) {
                    Text("读取皮肤温度")
                }
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("日志", color = Color.White, fontSize = 14.sp)
                    logs.forEach { log -> Text("${log.time.formatTime()} ${log.event}", color = Color.DarkGray, fontSize = 10.sp, maxLines = 1) }
                }
            }
            item { Text(status, color = Color.LightGray, fontSize = 11.sp, maxLines = 2) }
            item {
                Button(onClick = { if (hasActivityPermission()) startSleepMonitoring() else permissionRequest.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303030), contentColor = Color.White)) {
                    Text(if (hasActivityPermission()) "重新连接睡眠监听" else "授予活动权限")
                }
            }
        }
    }
}

private fun Long.formatTime(): String =
    if (this == 0L) "--:--:--" else java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(this))
