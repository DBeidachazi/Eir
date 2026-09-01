package icu.dbeidachazi.eir.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.*
import androidx.wear.compose.material3.*
import icu.dbeidachazi.eir.presentation.theme.EirTheme

class MainActivity : ComponentActivity() {
    private lateinit var client: androidx.health.services.client.MeasureClient
    private var heartRate by mutableStateOf<Double?>(null)
    private var steps by mutableStateOf<Long?>(null)
    private var calories by mutableStateOf<Double?>(null)
    private var distance by mutableStateOf<Double?>(null)
    private val receivedTypes = mutableSetOf<DeltaDataType<*, *>>()
    private var status by mutableStateOf("请先授予健康权限")
    private var reading by mutableStateOf(false)
    private val requestedTypes = listOf(
        DataType.HEART_RATE_BPM,
        DataType.STEPS,
        DataType.CALORIES,
        DataType.DISTANCE
    )
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        if (reading) {
            reading = false
            stopMeasurements()
            status = "10 秒内没有收到可用数据，请佩戴手表后重试"
        }
    }

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            status =
                if (r.values.all { it }) "权限已授予，点击读取心率" else "需要心率和活动识别权限"
        }
    private val callback = object : MeasureCallback {
        override fun onRegistered() { status = "已连接传感器，等待数据…" }
        override fun onRegistrationFailed(error: Throwable) {
            timeoutHandler.removeCallbacks(timeout)
            reading = false
            if (reading) status = "部分数据不可用：${error.message ?: error.javaClass.simpleName}"
        }
        override fun onAvailabilityChanged(type: DeltaDataType<*, *>, availability: Availability) {
            status = "传感器：$availability"
        }

        override fun onDataReceived(data: DataPointContainer) {
            if (!reading) return
            data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let { heartRate = it.value; finishType(DataType.HEART_RATE_BPM) }
            data.getData(DataType.STEPS).lastOrNull()?.let { steps = it.value; finishType(DataType.STEPS) }
            data.getData(DataType.CALORIES).lastOrNull()?.let { calories = it.value; finishType(DataType.CALORIES) }
            data.getData(DataType.DISTANCE).lastOrNull()?.let { distance = it.value; finishType(DataType.DISTANCE) }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        client = HealthServices.getClient(this).measureClient
        if (ContextCompat.checkSelfPermission(this, requiredPermission()) == PackageManager.PERMISSION_GRANTED) {
            status = "权限已授予，点击读取心率"
        }
        setContent { EirTheme { Screen() } }
    }

    private fun requiredPermission(): String = if (Build.VERSION.SDK_INT >= 36) {
        "android.permission.health.READ_HEART_RATE"
    } else {
        Manifest.permission.BODY_SENSORS
    }

    private fun read() {
        val permission = requiredPermission()
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(permission))
            return
        }
        heartRate = null
        steps = null
        calories = null
        distance = null
        receivedTypes.clear()
        reading = true
        status = "正在读取健康数据…"
        timeoutHandler.postDelayed(timeout, 10_000)
        try {
            requestedTypes.forEach { client.registerMeasureCallback(it, callback) }
        } catch (error: Throwable) {
            timeoutHandler.removeCallbacks(timeout)
            reading = false
            status = "心率读取失败：${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun finishType(type: DeltaDataType<*, *>) {
        if (!receivedTypes.add(type)) return
        client.unregisterMeasureCallbackAsync(type, callback)
        if (receivedTypes.size == requestedTypes.size) {
            timeoutHandler.removeCallbacks(timeout)
            reading = false
            status = "已读取当前可用数据"
        }
    }

    private fun stopMeasurements() {
        requestedTypes.forEach { client.unregisterMeasureCallbackAsync(it, callback) }
    }

    @Composable
    private fun Screen() {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            Arrangement.Center,
            Alignment.CenterHorizontally
        ) {
            Text("Eir 健康 PoC", color = Color.White)
            Text(heartRate?.let { "心率：${it.toInt()} bpm" } ?: "心率：暂无数据", color = Color.White)
            Text(steps?.let { "步数：$it" } ?: "步数：暂无数据", color = Color.White)
            Text(calories?.let { "卡路里：${it.toInt()} kcal" } ?: "卡路里：暂无数据", color = Color.White)
            Text(distance?.let { "距离：${"%.0f".format(it)} m" } ?: "距离：暂无数据", color = Color.White)
            Text(status, color = Color.White)
            Button(
                onClick = ::read,
                enabled = !reading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303030), contentColor = Color.White)
            ) { Text(if (reading) "读取中…" else "读取一次健康数据") }
        }
    }
}
