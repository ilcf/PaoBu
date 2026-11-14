package com.ilcf.paobu

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/* ---------------- 设置 ---------------- */

enum class Strategy(val label: String, val desc: String) {
    SMART("稳起·匀速·末段提速", "第1公里故意压慢几秒，中间贴着平均配速，最后1–2公里提速。5K 目标跑推荐。"),
    EVEN("全程匀速", "每公里同一配速，检验能否稳住。"),
    NEG("负分割", "每公里比上一公里略快，后半程比前半程快。")
}

data class RunConfig(
    val distKm: Double = 5.0,
    val paceSec: Int = 276,          // 目标平均配速，秒/公里
    val strategy: Strategy = Strategy.SMART,
    val cadence: Int = 178,
    val metronome: Boolean = true,
    val metroVol: Int = 70,
    val sound: Int = 0,              // 0 清脆 1 柔和
    val voice: Boolean = true,
    val kmAnnounce: Boolean = true,
    val intervalSec: Int = 0,        // 定时播报当前配速，0 = 关
    val deviationSec: Int = 5,       // 偏离计划超过 N 秒提醒，0 = 关
    val autoFinish: Boolean = true
) {
    val targetSec: Double get() = distKm * paceSec
}

object Prefs {
    private const val NAME = "paobu"
    fun load(ctx: Context): RunConfig {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val d = RunConfig()
        return RunConfig(
            distKm = p.getFloat("distKm", d.distKm.toFloat()).toDouble(),
            paceSec = p.getInt("paceSec", d.paceSec),
            strategy = runCatching { Strategy.valueOf(p.getString("strategy", d.strategy.name)!!) }.getOrDefault(d.strategy),
            cadence = p.getInt("cadence", d.cadence),
            metronome = p.getBoolean("metronome", d.metronome),
            metroVol = p.getInt("metroVol", d.metroVol),
            sound = p.getInt("sound", d.sound),
            voice = p.getBoolean("voice", d.voice),
            kmAnnounce = p.getBoolean("kmAnnounce", d.kmAnnounce),
            intervalSec = p.getInt("intervalSec", d.intervalSec),
            deviationSec = p.getInt("deviationSec", d.deviationSec),
            autoFinish = p.getBoolean("autoFinish", d.autoFinish)
        )
    }

    fun save(ctx: Context, c: RunConfig) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putFloat("distKm", c.distKm.toFloat())
            .putInt("paceSec", c.paceSec)
            .putString("strategy", c.strategy.name)
            .putInt("cadence", c.cadence)
            .putBoolean("metronome", c.metronome)
            .putInt("metroVol", c.metroVol)
            .putInt("sound", c.sound)
            .putBoolean("voice", c.voice)
            .putBoolean("kmAnnounce", c.kmAnnounce)
            .putInt("intervalSec", c.intervalSec)
            .putInt("deviationSec", c.deviationSec)
            .putBoolean("autoFinish", c.autoFinish)
            .apply()
    }
}

/* ---------------- 每公里计划 ---------------- */

data class Seg(val index: Int, val lenKm: Double, val pace: Double, val startKm: Double, val cumSec: Double, val startSec: Double)

fun buildPlan(c: RunConfig): List<Seg> {
    val d = c.distKm
    val n = ceil(d - 1e-9).toInt().coerceAtLeast(1)
    val lens = (0 until n).map { min(1.0, d - it) }
    val offs = lens.mapIndexed { i, len ->
        val x = (i + len / 2) / d
        when (c.strategy) {
            Strategy.EVEN -> 0.0
            Strategy.NEG -> 5.0 * (1 - 2 * x) * min(1.0, c.paceSec / 276.0)
            Strategy.SMART -> when {
                n == 1 -> 0.0
                i == 0 -> 4.0
                i == n - 1 -> -7.0
                i == n - 2 && n >= 5 -> -2.0
                else -> 0.0
            }
        }
    }
    val mean = offs.indices.sumOf { offs[it] * lens[it] } / d
    var cum = 0.0
    var startKm = 0.0
    return lens.indices.map { i ->
        val pace = c.paceSec + offs[i] - mean
        val start = cum
        cum += pace * lens[i]
        val s = Seg(i, lens[i], pace, startKm, cum, start)
        startKm += lens[i]
        s
    }
}

/** 计划中跑到 km 处应该用的时间（秒） */
fun planTimeAt(plan: List<Seg>, km: Double): Double {
    for (s in plan) {
        if (km <= s.startKm + s.lenKm + 1e-9) return s.startSec + (km - s.startKm).coerceAtLeast(0.0) * s.pace
    }
    val last = plan.last()
    return last.cumSec + (km - last.startKm - last.lenKm) * last.pace
}

fun planPaceAt(plan: List<Seg>, km: Double): Double =
    (plan.firstOrNull { km < it.startKm + it.lenKm - 1e-9 } ?: plan.last()).pace

/* ---------------- 格式化 ---------------- */

fun fmtClock(sec: Double): String {
    val s = sec.roundToInt().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

fun fmtPace(sec: Double?): String = if (sec == null || sec.isNaN() || sec <= 0 || sec > 1800) "--" else fmtClock(sec)

/** 语音用：4分37秒 / 1小时2分5秒 */
fun sayDur(sec: Double): String {
    val s = sec.roundToInt().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    val sb = StringBuilder()
    if (h > 0) sb.append("${h}小时")
    if (m > 0 || h > 0) sb.append("${m}分")
    if (ss > 0 || sb.isEmpty()) sb.append("${ss}秒")
    return sb.toString()
}

fun parseClock(t: String): Double? {
    val p = t.trim().replace('：', ':').split(':').map { it.trim().toDoubleOrNull() ?: return null }
    return when (p.size) {
        1 -> p[0] * 60
        2 -> p[0] * 60 + p[1]
        3 -> p[0] * 3600 + p[1] * 60 + p[2]
        else -> null
    }
}

/* ---------------- 运行状态（服务 → 界面） ---------------- */

enum class Status { IDLE, RUNNING, PAUSED, FINISHED }

data class Split(val km: Int, val lenKm: Double, val sec: Double, val cumSec: Double, val planCumSec: Double)

data class RunSnapshot(
    val status: Status = Status.IDLE,
    val config: RunConfig = RunConfig(),
    val elapsedSec: Double = 0.0,
    val distM: Double = 0.0,
    val curPace: Double? = null,
    val avgPace: Double? = null,
    val curCad: Int? = null,
    val avgCad: Int? = null,
    val steps: Int = 0,
    val deltaSec: Double? = null,      // 正 = 落后计划，负 = 领先
    val targetPace: Double = 0.0,
    val splits: List<Split> = emptyList(),
    val gpsAcc: Float? = null,
    val hasStepSensor: Boolean = true
)

object RunBus {
    val run = MutableStateFlow(RunSnapshot())
    val gpsAcc = MutableStateFlow<Float?>(null)
}

/* ---------------- GPS 平滑（简单卡尔曼） ---------------- */

class Kalman(private val q: Double = 3.0) {
    private var lat = 0.0
    private var lng = 0.0
    private var variance = -1.0
    private var ts = 0L

    fun reset() { variance = -1.0 }

    fun process(la: Double, lo: Double, accuracy: Float, timeMs: Long): Pair<Double, Double> {
        val acc = accuracy.toDouble().coerceAtLeast(1.0)
        if (variance < 0) {
            lat = la; lng = lo; variance = acc * acc; ts = timeMs
        } else {
            val dt = timeMs - ts
            if (dt > 0) { variance += dt * q * q / 1000.0; ts = timeMs }
            val k = variance / (variance + acc * acc)
            lat += k * (la - lat)
            lng += k * (lo - lng)
            variance *= (1 - k)
        }
        return lat to lng
    }
}
