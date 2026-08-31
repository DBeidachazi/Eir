package icu.dbeidachazi.eir.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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
    private var status by mutableStateOf("请先授予健康权限")
    private var reading by mutableStateOf(false)
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { if (reading) { reading = false; status = "10 秒内没有收到心率，请佩戴并贴合手腕后重试" } }

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            status =
                if (r.values.all { it }) "权限已授予，点击读取心率" else "需要心率和活动识别权限"
        }
    private val callback = object : MeasureCallback {
        override fun onRegistered() { status = "已连接心率传感器，等待数据…" }
        override fun onRegistrationFailed(error: Throwable) { reading = false; status = "心率读取失败：${error.message ?: error.javaClass.simpleName}" }
        override fun onAvailabilityChanged(type: DeltaDataType<*, *>, availability: Availability) {
            status = "传感器：$availability"
        }

        override fun onDataReceived(data: DataPointContainer) {
            data.getData(DataType.HEART_RATE_BPM).lastOrNull()
                ?.let { heartRate = it.value; reading = false; status = "已读取" }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); client =
            HealthServices.getClient(this).measureClient; setContent { EirTheme { Screen() } }
    }

    private fun read() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BODY_SENSORS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.launch(
                arrayOf(
                    Manifest.permission.BODY_SENSORS,
                    "android.permission.health.READ_HEART_RATE",
                    Manifest.permission.ACTIVITY_RECOGNITION
                )
            ); return
        }
        reading = true; status = "正在读取…"; timeoutHandler.postDelayed(timeout, 10_000); client.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
    }

    @Composable
    private fun Screen() {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(16.dp),
            Arrangement.Center,
            Alignment.CenterHorizontally
        ) {
            Text("Eir 健康 PoC", color = Color.White)
            Text(heartRate?.let { "心率：${it.toInt()} bpm" } ?: "心率：暂无数据", color = Color.White)
            Text(status, color = Color.White)
            Button(
                onClick = ::read,
                enabled = !reading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303030), contentColor = Color.White)
            ) { Text(if (reading) "读取中…" else "读取当前心率") }
        }
    }
}
