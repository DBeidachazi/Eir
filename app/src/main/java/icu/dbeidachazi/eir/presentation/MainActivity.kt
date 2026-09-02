package icu.dbeidachazi.eir.presentation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityState
import com.google.android.gms.wearable.Wearable
import androidx.wear.compose.material3.Text
import icu.dbeidachazi.eir.presentation.theme.EirTheme

class MainActivity : ComponentActivity() {
    private lateinit var passiveClient: PassiveMonitoringClient
    private var sleepState by mutableStateOf("等待读取手表当前状态")
    private var status by mutableStateOf("请先授予活动识别权限")
    private var monitoringRegistered = false
    private var monitoringStarting = false
    private var receiverRegistered = false
    private var phoneConnected by mutableStateOf(false)
    private val connectionHandler = Handler(Looper.getMainLooper())
    private val connectionPoll = object : Runnable {
        override fun run() {
            refreshPhoneConnection()
            connectionHandler.postDelayed(this, 15_000L)
        }
    }

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
                HealthReportStore.ACTION_UPDATED -> refreshPhoneConnection()
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
        else permissionRequest.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        setContent { EirTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        if (::passiveClient.isInitialized && hasActivityPermission()) startSleepMonitoring()
        connectionHandler.removeCallbacks(connectionPoll)
        connectionHandler.post(connectionPoll)
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(updates)
        receiverRegistered = false
        connectionHandler.removeCallbacks(connectionPoll)
        super.onDestroy()
    }

    private fun hasActivityPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACTIVITY_RECOGNITION
    ) == PackageManager.PERMISSION_GRANTED

    private fun refreshPhoneConnection() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes -> phoneConnected = nodes.isNotEmpty() }
            .addOnFailureListener { phoneConnected = false }
    }

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
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Eir", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(if (phoneConnected) "已连接手机 Eir" else "未连接手机 Eir", color = Color.White, fontSize = 18.sp)
                Text(if (phoneConnected) "●" else "○", color = if (phoneConnected) Color(0xFF55D66F) else Color(0xFF888888), fontSize = 32.sp)
            }
        }
    }
}

private fun Long.formatTime(): String =
    if (this == 0L) "--:--:--" else java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(this))
