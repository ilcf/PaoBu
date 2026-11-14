@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ilcf.paobu

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt

private val Accent = Color(0xFFFF6A33)
private val Lane = Color(0xFF6F9BFF)
private val Good = Color(0xFF4CC98B)
private val Warn = Color(0xFFF0B04A)
private val Bg = Color(0xFF0F141A)
private val Surface = Color(0xFF18202A)
private val Muted = Color(0xFF93A1B0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, onPrimary = Color(0xFF1A0E08), secondary = Lane,
                    background = Bg, surface = Surface, onSurface = Color(0xFFE8EDF2), onBackground = Color(0xFFE8EDF2)
                )
            ) {
                Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) { App() }
            }
        }
    }
}

@Composable
private fun App() {
    val snap by RunBus.run.collectAsState()
    when (snap.status) {
        Status.IDLE -> SetupScreen()
        Status.RUNNING, Status.PAUSED -> RunScreen(snap)
        Status.FINISHED -> SummaryScreen(snap)
    }
}

/* =================== 跑前设置 =================== */

private fun hasPerm(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
@Composable
private fun SetupScreen() {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(Prefs.load(ctx)) }
    fun upd(c: RunConfig) { cfg = c; Prefs.save(ctx, c) }
    val gpsAcc by RunBus.gpsAcc.collectAsState()
    var locGranted by remember { mutableStateOf(hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) }
    var ttsOk by remember { mutableStateOf<Boolean?>(null) }
    var testVoice by remember { mutableStateOf<Voice?>(null) }
    var preview by remember { mutableStateOf<Metronome?>(null) }
    var showBgHelp by remember { mutableStateOf(false) }

    // 语音引擎检测 + GPS 预热
    DisposableEffect(locGranted) {
        val v = Voice(ctx) { ttsOk = it }
        testVoice = v
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = LocationListener { RunBus.gpsAcc.value = if (it.hasAccuracy()) it.accuracy else null }
        if (locGranted) runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper()) }
        onDispose {
            runCatching { lm.removeUpdates(l) }
            v.shutdown()
            preview?.stop()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        locGranted = hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        if (locGranted && res.isNotEmpty()) {
            preview?.stop(); preview = null
            RunService.send(ctx, RunService.ACTION_START)
        }
    }
    fun start() {
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) need += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { !hasPerm(ctx, it) }
        if (missing.isEmpty()) {
            preview?.stop(); preview = null
            RunService.send(ctx, RunService.ACTION_START)
        } else permLauncher.launch(missing.toTypedArray())
    }

    val plan = remember(cfg.distKm, cfg.paceSec, cfg.strategy) { buildPlan(cfg) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("跑步节拍", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            GpsBadge(gpsAcc, locGranted)
        }

        Section("目标") {
            Text("距离", color = Muted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3.0, 5.0, 8.0, 10.0).forEach { d ->
                    FilterChip(selected = abs(cfg.distKm - d) < 1e-6, onClick = { upd(cfg.copy(distKm = d)) }, label = { Text("${d.roundToInt()} km") })
                }
            }
            NumberField("自定义距离 (km)", "%.2f".format(cfg.distKm).trimEnd('0').trimEnd('.')) { t ->
                t.toDoubleOrNull()?.takeIf { it in 0.5..42.2 }?.let { upd(cfg.copy(distKm = it)) }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("平均配速 /km", color = Muted, fontSize = 13.sp)
                    Text(fmtClock(cfg.paceSec.toDouble()), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Accent)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("预计用时", color = Muted, fontSize = 13.sp)
                    Text(fmtClock(cfg.targetSec), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-5, -1, 1, 5).forEach { d ->
                    OutlinedButton(onClick = { upd(cfg.copy(paceSec = (cfg.paceSec + d).coerceIn(150, 600))) }, modifier = Modifier.weight(1f)) {
                        Text(if (d > 0) "+${d}秒" else "${d}秒")
                    }
                }
            }
            NumberField("或直接输入目标用时（如 23:00）", fmtClock(cfg.targetSec), KeyboardType.Text) { t ->
                parseClock(t)?.takeIf { it > 0 }?.let { upd(cfg.copy(paceSec = (it / cfg.distKm).roundToInt().coerceIn(150, 600))) }
            }
            TextButton(onClick = { upd(cfg.copy(distKm = 5.0, paceSec = 276)) }) { Text("恢复我的目标：5 km · 23:00") }
        }

        Section("每公里计划") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Strategy.values().forEach { s ->
                    FilterChip(selected = cfg.strategy == s, onClick = { upd(cfg.copy(strategy = s)) }, label = { Text(s.label) })
                }
            }
            Text(cfg.strategy.desc, color = Muted, fontSize = 13.sp)
            plan.forEach { s ->
                val d = (s.pace - cfg.paceSec).roundToInt()
                Row(Modifier.fillMaxWidth()) {
                    Text(if (s.lenKm < 0.999) "${s.index + 1} km (${(s.lenKm * 1000).roundToInt()}m)" else "${s.index + 1} km", Modifier.weight(1f))
                    Text(fmtClock(s.pace), Modifier.width(56.dp), fontWeight = FontWeight.SemiBold)
                    Text(if (d == 0) "±0" else if (d > 0) "+$d" else "$d", Modifier.width(40.dp), color = if (d < 0) Good else if (d > 0) Warn else Muted)
                    Text("累计 ${fmtClock(s.cumSec)}", Modifier.width(96.dp), textAlign = TextAlign.End, color = Muted)
                }
            }
        }

        Section("步频与节拍器") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(160 to "慢跑", 165 to "轻松", 170 to "有氧", 175 to "节奏", 180 to "目标跑", 185 to "冲刺").forEach { (v, n) ->
                    FilterChip(selected = cfg.cadence == v, onClick = { upd(cfg.copy(cadence = v)) }, label = { Text("$n $v") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { upd(cfg.copy(cadence = (cfg.cadence - 1).coerceAtLeast(120))) }) { Text("−") }
                Text("${cfg.cadence} 步/分", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { upd(cfg.copy(cadence = (cfg.cadence + 1).coerceAtMost(220))) }) { Text("+") }
                Spacer(Modifier.weight(1f))
                val stride = 1000.0 / cfg.paceSec * 60 / cfg.cadence
                Text("步幅 ${"%.2f".format(stride)} m", color = Muted)
            }
            SwitchRow("跑步时播放节拍", cfg.metronome) { upd(cfg.copy(metronome = it)) }
            if (cfg.metronome) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = cfg.sound == 0, onClick = { upd(cfg.copy(sound = 0)) }, label = { Text("清脆") })
                    FilterChip(selected = cfg.sound == 1, onClick = { upd(cfg.copy(sound = 1)) }, label = { Text("柔和") })
                }
                Text("节拍音量 ${cfg.metroVol}", color = Muted, fontSize = 13.sp)
                Slider(value = cfg.metroVol.toFloat(), onValueChange = { upd(cfg.copy(metroVol = it.roundToInt())) }, valueRange = 10f..100f)
                OutlinedButton(onClick = {
                    if (preview != null) { preview?.stop(); preview = null }
                    else preview = Metronome(cfg.cadence, cfg.metroVol, cfg.sound).also { it.start() }
                }) { Text(if (preview != null) "停止试听" else "试听节拍") }
                Text("节拍不会打断其他音乐 App，可以边听歌边跟拍。", color = Muted, fontSize = 13.sp)
            }
        }

        Section("语音提醒") {
            SwitchRow("语音播报", cfg.voice) { upd(cfg.copy(voice = it)) }
            when (ttsOk) {
                null -> Text("正在检测语音引擎…", color = Muted, fontSize = 13.sp)
                true -> Text("中文语音可用。播报时音乐会自动变小声。", color = Good, fontSize = 13.sp)
                false -> Column {
                    Text("没有找到可用的中文语音引擎，播报会变成提示音。请在系统设置里把“文字转语音”的引擎设为系统自带的中文引擎。", color = Warn, fontSize = 13.sp)
                    TextButton(onClick = { runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } }) { Text("打开语音设置") }
                }
            }
            SwitchRow("每公里播报用时和计划差距", cfg.kmAnnounce) { upd(cfg.copy(kmAnnounce = it)) }
            Text("偏离计划提醒（领先/落后超过）", color = Muted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "关", 3 to "3 秒", 5 to "5 秒", 10 to "10 秒").forEach { (v, n) ->
                    FilterChip(selected = cfg.deviationSec == v, onClick = { upd(cfg.copy(deviationSec = v)) }, label = { Text(n) })
                }
            }
            Text("定时播报当前配速", color = Muted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "关", 30 to "30 秒", 60 to "1 分钟", 120 to "2 分钟").forEach { (v, n) ->
                    FilterChip(selected = cfg.intervalSec == v, onClick = { upd(cfg.copy(intervalSec = v)) }, label = { Text(n) })
                }
            }
            SwitchRow("跑到目标距离自动结束", cfg.autoFinish) { upd(cfg.copy(autoFinish = it)) }
            OutlinedButton(onClick = { testVoice?.say("第1公里，用时4分41秒。正好在计划上。下一公里目标4分37秒。", flush = true) }) { Text("试听语音") }
        }

        Section("锁屏后台运行（第一次务必设置）") {
            Text("小米、一加会在锁屏后清理后台，导致距离不再记录。请把本 App 设为“电池无限制 / 允许后台运行 / 允许自启动”。", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val pm = ctx.getSystemService(PowerManager::class.java)
                    if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
                    } else showBgHelp = true
                }) { Text("关闭电池优化") }
                OutlinedButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
                }) { Text("应用详情设置") }
            }
            TextButton(onClick = { showBgHelp = true }) { Text("红米 / 一加 具体怎么设？") }
        }

        Button(
            onClick = { start() },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) { Text("开始跑步", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        if (gpsAcc == null || (gpsAcc ?: 99f) > 20f) {
            Text("建议在室外等 GPS 精度到 20 米以内再开始，起步阶段距离会更准。", color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showBgHelp) {
        AlertDialog(
            onDismissRequest = { showBgHelp = false },
            confirmButton = { TextButton(onClick = { showBgHelp = false }) { Text("知道了") } },
            title = { Text("后台运行设置") },
            text = {
                Text(
                    "红米 K40（MIUI / HyperOS）：\n" +
                        "1. 设置 → 应用设置 → 应用管理 → 跑步节拍 → 省电策略 → 无限制\n" +
                        "2. 同页面打开“自启动”\n" +
                        "3. 最近任务里下拉本 App 卡片加锁\n\n" +
                        "一加 Ace 5（ColorOS / OxygenOS）：\n" +
                        "1. 设置 → 应用 → 应用管理 → 跑步节拍 → 耗电管理 → 允许后台行为、允许完全后台行为\n" +
                        "2. 同页面打开“允许自启动”\n" +
                        "3. 最近任务里长按卡片 → 锁定\n\n" +
                        "定位权限选“使用 App 时允许”即可；跑步时通知栏会一直显示“跑步中”。"
                )
            }
        )
    }
}

/* =================== 跑步中 =================== */

@Composable
private fun RunScreen(s: RunSnapshot) {
    val ctx = LocalContext.current
    var confirmStop by remember { mutableStateOf(false) }
    val c = s.config
    val paused = s.status == Status.PAUSED
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (paused) "已暂停" else "跑步中", fontSize = 18.sp, color = if (paused) Warn else Good, modifier = Modifier.weight(1f))
            GpsBadge(s.gpsAcc, true)
        }
        // 距离
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%.2f".format(s.distM / 1000), fontSize = 84.sp, fontWeight = FontWeight.Bold, lineHeight = 84.sp)
            Text("公里 / 目标 ${"%.1f".format(c.distKm)}", color = Muted)
        }
        LinearProgressIndicator(
            progress = { (s.distM / (c.distKm * 1000)).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = Accent, trackColor = Surface
        )
        // 计划差距
        val d = s.deltaSec
        val (dTxt, dCol) = when {
            d == null -> "等待距离数据…" to Muted
            abs(d) <= 2 -> "正好在计划上" to Good
            d > 0 -> "落后计划 ${d.roundToInt()} 秒" to Warn
            else -> "领先计划 ${(-d).roundToInt()} 秒" to Good
        }
        Box(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
            Text(dTxt, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = dCol)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("用时", fmtClock(s.elapsedSec), Modifier.weight(1f))
            Metric("本公里目标", fmtPace(s.targetPace), Modifier.weight(1f), Accent)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("当前配速", fmtPace(s.curPace), Modifier.weight(1f), paceColor(s.curPace, s.targetPace))
            Metric("平均配速", fmtPace(s.avgPace), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("当前步频", if (s.hasStepSensor) (s.curCad?.toString() ?: "--") else "无传感器", Modifier.weight(1f), cadColor(s.curCad, c.cadence))
            Metric("平均步频", s.avgCad?.toString() ?: "--", Modifier.weight(1f))
        }
        if (s.splits.isNotEmpty()) {
            val last = s.splits.last()
            Text("上一公里 ${fmtClock(last.sec)} · 累计 ${fmtClock(last.cumSec)}（计划 ${fmtClock(last.planCumSec)}）", color = Muted, fontSize = 14.sp)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { RunService.send(ctx, if (paused) RunService.ACTION_RESUME else RunService.ACTION_PAUSE) },
                modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (paused) Good else Accent)
            ) { Text(if (paused) "继续" else "暂停", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = { confirmStop = true }, modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(16.dp)) {
                Text("结束", fontSize = 22.sp)
            }
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            confirmButton = { TextButton(onClick = { confirmStop = false; RunService.send(ctx, RunService.ACTION_STOP) }) { Text("结束跑步") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("继续跑") } },
            title = { Text("结束这次跑步？") },
            text = { Text("已跑 ${"%.2f".format(s.distM / 1000)} km，用时 ${fmtClock(s.elapsedSec)}") }
        )
    }
}

private fun paceColor(p: Double?, target: Double): Color = when {
    p == null -> Color.Unspecified
    p - target > 5 -> Warn
    p - target < -5 -> Lane
    else -> Good
}

private fun cadColor(c: Int?, target: Int): Color = when {
    c == null -> Color.Unspecified
    abs(c - target) <= 4 -> Good
    else -> Warn
}

/* =================== 跑完 =================== */

@Composable
private fun SummaryScreen(s: RunSnapshot) {
    val c = s.config
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("跑步完成", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("距离 km", "%.2f".format(s.distM / 1000), Modifier.weight(1f))
            Metric("用时", fmtClock(s.elapsedSec), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("平均配速", fmtPace(s.avgPace), Modifier.weight(1f), Accent)
            Metric("平均步频", s.avgCad?.toString() ?: "--", Modifier.weight(1f))
        }
        val stride = if (s.steps > 0) s.distM / s.steps else null
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("总步数", if (s.steps > 0) s.steps.toString() else "--", Modifier.weight(1f))
            Metric("平均步幅", stride?.let { "%.2f m".format(it) } ?: "--", Modifier.weight(1f))
        }
        if (s.distM >= c.distKm * 1000 - 20) {
            val d = s.elapsedSec - c.targetSec
            Text(
                if (d <= 0) "比目标 ${fmtClock(c.targetSec)} 快 ${fmtClock(-d)}" else "比目标 ${fmtClock(c.targetSec)} 慢 ${fmtClock(d)}",
                fontSize = 18.sp, color = if (d <= 0) Good else Warn, fontWeight = FontWeight.SemiBold
            )
        }
        Section("每公里") {
            Row(Modifier.fillMaxWidth()) {
                Text("公里", Modifier.weight(1f), color = Muted, fontSize = 13.sp)
                Text("配速", Modifier.width(60.dp), color = Muted, fontSize = 13.sp)
                Text("累计", Modifier.width(64.dp), color = Muted, fontSize = 13.sp)
                Text("对比计划", Modifier.width(80.dp), color = Muted, fontSize = 13.sp, textAlign = TextAlign.End)
            }
            HorizontalDivider()
            s.splits.forEach { sp ->
                val pace = sp.sec / sp.lenKm
                val d = (sp.cumSec - sp.planCumSec).roundToInt()
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(if (sp.lenKm < 0.999) "${sp.km}（${(sp.lenKm * 1000).roundToInt()}m）" else "${sp.km}", Modifier.weight(1f))
                    Text(fmtClock(pace), Modifier.width(60.dp), fontWeight = FontWeight.SemiBold)
                    Text(fmtClock(sp.cumSec), Modifier.width(64.dp))
                    Text(
                        if (abs(d) <= 2) "正好" else if (d > 0) "慢 ${d}秒" else "快 ${-d}秒",
                        Modifier.width(80.dp), textAlign = TextAlign.End, color = if (d > 2) Warn else Good
                    )
                }
            }
            if (s.splits.isEmpty()) Text("距离不足，没有分段数据。", color = Muted)
        }
        Button(
            onClick = { RunBus.run.value = RunSnapshot() },
            modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)
        ) { Text("返回", fontSize = 18.sp) }
        Text("本版本不保存历史记录，需要的话可以截图。", color = Muted, fontSize = 13.sp)
    }
}

/* =================== 小组件 =================== */

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier.background(Surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: String, type: KeyboardType = KeyboardType.Decimal, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val fm = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit(text); fm.clearFocus() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (focused && !it.isFocused) onCommit(text)
            focused = it.isFocused
        }
    )
}

@Composable
private fun GpsBadge(acc: Float?, granted: Boolean) {
    val (txt, col) = when {
        !granted -> "GPS 未授权" to Muted
        acc == null -> "GPS 搜索中" to Warn
        acc <= 10 -> "GPS 良好 ±${acc.roundToInt()}m" to Good
        acc <= 20 -> "GPS 一般 ±${acc.roundToInt()}m" to Warn
        else -> "GPS 弱 ±${acc.roundToInt()}m" to Warn
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(col, RoundedCornerShape(5.dp)))
        Spacer(Modifier.width(6.dp))
        Text(txt, color = col, fontSize = 13.sp)
    }
}
