package icu.dbeidachazi.eir.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var snapshot by mutableStateOf<PhoneHealthSnapshot?>(null)
    private var uploadStatus by mutableStateOf("尚未上报")
    private var uploadSentAt by mutableStateOf(0L)
    private var samsungReadStatus by mutableStateOf("尚未读取 Samsung Health")
    private var historyCount by mutableStateOf(0)
    private var recentHistory by mutableStateOf(emptyList<String>())

    private fun refreshHistory() {
        historyCount = HealthDataStore.historyCount(this)
        recentHistory = HealthDataStore.recentHistory(this)
    }

    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == HealthDataStore.ACTION_UPDATED) {
                    snapshot = HealthDataStore.read(this@MainActivity)
                    uploadStatus = HealthDataStore.uploadStatus(this@MainActivity)
                    uploadSentAt = HealthDataStore.uploadSentAt(this@MainActivity)
                    refreshHistory()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneUploadScheduler.ensureScheduled(this)
        snapshot = HealthDataStore.read(this)
        uploadStatus = HealthDataStore.uploadStatus(this)
        uploadSentAt = HealthDataStore.uploadSentAt(this)
        refreshHistory()
        ContextCompat.registerReceiver(
            this,
            updates,
            IntentFilter(HealthDataStore.ACTION_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF006A6A),
                    onPrimary = Color.White,
                    background = Color(0xFFF4F7F8),
                    surface = Color.White,
                    onSurface = Color(0xFF172022)
                )
            ) {
                PhoneHealthScreen(
                    snapshot = snapshot,
                    uploadStatus = uploadStatus,
                    uploadSentAt = uploadSentAt,
                    samsungReadStatus = samsungReadStatus,
                    historyCount = historyCount,
                    recentHistory = recentHistory,
                    onReadSamsung = {
                        samsungReadStatus = "正在读取 Samsung Health..."
                        lifecycleScope.launch {
                            val result = runCatching { SamsungHealthReader.readOnce(this@MainActivity) }
                                .getOrElse { SamsungReadResult(null, null, null, null, null, null, 0, null, 0L, "读取失败：${it.message ?: it.javaClass.simpleName}") }
                            HealthDataStore.saveSamsungRead(
                                this@MainActivity,
                                result.heartRateBpm,
                                result.steps,
                                result.caloriesKcal,
                                result.sleepState,
                                result.oxygenSaturation,
                                result.bodyTemperatureCelsius,
                                result.capturedAt,
                                result.workoutCount,
                                result.latestWorkout
                            )
                            snapshot = HealthDataStore.read(this@MainActivity)
                            refreshHistory()
                            samsungReadStatus = result.message
                        }
                    },
                    onUploadNow = { PhoneUploadScheduler.runNow(this) }
                )
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(updates)
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
private fun PhoneHealthScreen(
    snapshot: PhoneHealthSnapshot?,
    uploadStatus: String,
    uploadSentAt: Long,
    samsungReadStatus: String,
    historyCount: Int,
    recentHistory: List<String>,
    onReadSamsung: () -> Unit,
    onUploadNow: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF4F7F8)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Eir 健康监控", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (snapshot == null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Text("等待手表数据", modifier = Modifier.padding(20.dp), fontSize = 18.sp)
                }
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF006A6A))) {
                    Column(modifier = Modifier.fillMaxWidth().padding(22.dp)) {
                        Text("手表实时睡眠状态", color = Color(0xFFB8F0EC), fontSize = 16.sp)
                        Text(
                            snapshot.sleepState,
                            color = Color.White,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold
                        )
                    Text("状态变化于 " + formatTime(snapshot.sleepStateChangedAt), color = Color.White, fontSize = 14.sp)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard("手表实时皮温", snapshot.skinTemperatureCelsius?.let { "%.1f °C".format(Locale.getDefault(), it) } ?: "未测量", Modifier.weight(1f))
                    MetricCard("手表快照时间", formatTime(snapshot.capturedAt), Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard("手表皮温来源", if (snapshot.skinTemperatureCelsius != null) "实时" else "未接入", Modifier.weight(1f))
                    MetricCard("手表事件时间", formatTime(snapshot.sleepStateChangedAt), Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard("Samsung 血氧历史", snapshot.oxygenSaturation?.let { "%.1f %%".format(Locale.getDefault(), it) } ?: "暂无数据", Modifier.weight(1f))
                    MetricCard("Samsung 皮温历史", snapshot.bodyTemperatureCelsius?.let { "%.1f °C".format(Locale.getDefault(), it) } ?: "暂无数据", Modifier.weight(1f))
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Samsung Health 已同步数据", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(samsungReadStatus, fontSize = 14.sp, color = Color(0xFF536164))
                    Text("心率  ${snapshot?.samsungHeartRateBpm?.let { "%.0f bpm".format(Locale.getDefault(), it) } ?: "暂无"}", fontSize = 16.sp)
                    Text("步数  ${snapshot?.samsungSteps?.toString() ?: "暂无"}", fontSize = 16.sp)
                    Text("卡路里  ${snapshot?.samsungCaloriesKcal?.let { "%.0f kcal".format(Locale.getDefault(), it) } ?: "暂无"}", fontSize = 16.sp)
                    Text("睡眠阶段（历史）  ${snapshot?.samsungSleepState ?: "暂无"}", fontSize = 16.sp)
                    Text("运动历史  ${snapshot?.samsungWorkoutCount?.takeIf { it > 0 }?.toString() ?: "暂无"} 条", fontSize = 16.sp)
                    snapshot?.samsungLatestWorkout?.let { Text("最近运动  $it", fontSize = 13.sp, color = Color(0xFF536164), maxLines = 2) }
                    Text("读取时间  ${formatTime(snapshot?.samsungCapturedAt ?: 0L)}", fontSize = 14.sp, color = Color(0xFF536164))
                    Button(
                        onClick = onReadSamsung,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))
                    ) { Text("读取 Samsung Health 最新数据") }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("上报状态", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    HorizontalDivider()
                    Text(uploadStatus, fontSize = 17.sp, color = Color(0xFF006A6A))
                    Text("最近发送  " + formatTime(uploadSentAt), fontSize = 14.sp, color = Color(0xFF536164))
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("本地历史档案", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("已持久化 $historyCount 条记录", fontSize = 14.sp, color = Color(0xFF536164))
                    recentHistory.forEach { line ->
                        Text(line, fontSize = 11.sp, color = Color(0xFF536164), maxLines = 2)
                    }
                    if (recentHistory.isEmpty()) Text("等待首次读取或手表事件", fontSize = 14.sp, color = Color(0xFF536164))
                }
            }
            Button(
                onClick = onUploadNow,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006A6A))
            ) {
                Text("立即上报", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@androidx.compose.runtime.Composable
private fun MetricCard(label: String, value: String, modifier: Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(label, fontSize = 14.sp, color = Color(0xFF536164))
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatTime(value: Long): String =
    if (value == 0L) "暂无" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
