package com.ilcf.paobu

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

class RunService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "com.ilcf.paobu.START"
        const val ACTION_PAUSE = "com.ilcf.paobu.PAUSE"
        const val ACTION_RESUME = "com.ilcf.paobu.RESUME"
        const val ACTION_STOP = "com.ilcf.paobu.STOP"
        private const val CHANNEL = "run"
        private const val NID = 1

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, RunService::class.java).setAction(action)
            if (action == ACTION_START) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cfg: RunConfig
    private lateinit var plan: List<Seg>
    private var lm: LocationManager? = null
    private var sm: SensorManager? = null
    private var voice: Voice? = null
    private var metro: Metronome? = null
    private var wake: PowerManager.WakeLock? = null
    private var started = false

    // 计时
    private var startRt = 0L
    private var pausedTotal = 0L
    private var pauseAt = 0L
    private var running = false
    private var finished = false
    private var frozenSec = -1.0

    // 距离
    private val kf = Kalman()
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastFixRt = 0L
    private var distM = 0.0
    private var acc: Float? = null
    private val samples = ArrayDeque<Pair<Double, Double>>() // (用时秒, 距离米)

    // 步频
    private val stepTimes = ArrayDeque<Long>()
    private var steps = 0
    private var lastCounter = -1f
    private var hasDetector = false
    private var hasCounter = false

    // 分段与播报
    private val splits = mutableListOf<Split>()
    private var lastSplitCum = 0.0
    private var lastInterval = 0.0
    private var lastAlert = -1000.0
    private var lastSpeak = -1000.0
    private var lastNotif = 0L

    private val locListener = LocationListener { onLoc(it) }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (!finished) handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!started) begin()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> finish(manual = true)
        }
        return START_NOT_STICKY
    }

    /* ---------- 开始 ---------- */

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun begin() {
        started = true
        cfg = Prefs.load(this)
        plan = buildPlan(cfg)
        createChannel()
        val n = buildNotif("准备开始…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NID, n)

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "paobu:run").also { it.acquire() }

        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper()) }

        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            hasDetector = sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, 0) == true
        }
        if (!hasDetector) sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            hasCounter = sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, 0) == true
        }

        voice = Voice(this) { ok ->
            if (cfg.voice && ok) {
                voice?.say("开始跑步。目标${fmtKm(cfg.distKm)}公里，平均配速${sayDur(cfg.paceSec.toDouble())}，预计用时${sayDur(cfg.targetSec)}。")
                lastSpeak = elapsed()
            }
        }
        if (cfg.metronome) metro = Metronome(cfg.cadence, cfg.metroVol, cfg.sound).also { it.start() }

        startRt = SystemClock.elapsedRealtime()
        running = true
        handler.post(ticker)
    }

    private fun fmtKm(d: Double): String = if (abs(d - d.roundToInt()) < 1e-6) d.roundToInt().toString() else "%.1f".format(d)

    private fun elapsed(): Double {
        if (frozenSec >= 0) return frozenSec
        if (startRt == 0L) return 0.0
        val end = if (running) SystemClock.elapsedRealtime() else pauseAt
        return (end - startRt - pausedTotal) / 1000.0
    }

    /* ---------- GPS ---------- */

    private fun onLoc(l: Location) {
        acc = if (l.hasAccuracy()) l.accuracy else null
        RunBus.gpsAcc.value = acc
        if (!running) return
        if (!l.hasAccuracy() || l.accuracy > 25f) return
        val now = SystemClock.elapsedRealtime()
        val (fl, fo) = kf.process(l.latitude, l.longitude, l.accuracy, l.elapsedRealtimeNanos / 1_000_000)
        if (lastLat.isNaN()) {
            lastLat = fl; lastLon = fo; lastFixRt = now; return
        }
        val r = FloatArray(1)
        Location.distanceBetween(lastLat, lastLon, fl, fo, r)
        val d = r[0].toDouble()
        val dt = (now - lastFixRt) / 1000.0
        if (dt > 0 && d / dt > 9.0) return                   // 跳点
        if (l.hasSpeed() && l.speed < 0.6f && d < 3.0) return // 原地漂移
        distM += d
        lastLat = fl; lastLon = fo; lastFixRt = now
        checkSplits()
        if (cfg.autoFinish && distM >= cfg.distKm * 1000) finish(manual = false)
    }

    private fun checkSplits() {
        val fullKm = floor(cfg.distKm + 1e-9).toInt()
        while (splits.size < fullKm && distM >= (splits.size + 1) * 1000.0) {
            val km = splits.size + 1
            val cum = elapsed()
            val s = Split(km, 1.0, cum - lastSplitCum, cum, planTimeAt(plan, km.toDouble()))
            splits.add(s)
            lastSplitCum = cum
            announceKm(s)
        }
    }

    /* ---------- 步频传感器 ---------- */

    override fun onSensorChanged(ev: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        when (ev.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> if (running) { steps++; stepTimes.addLast(now) }
            Sensor.TYPE_STEP_COUNTER -> {
                val c = ev.values[0]
                if (running && lastCounter >= 0) {
                    val n = (c - lastCounter).toInt().coerceIn(0, 60)
                    steps += n
                    repeat(n) { stepTimes.addLast(now) }
                }
                lastCounter = c
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /* ---------- 每秒刷新 ---------- */

    private fun tick() {
        val e = elapsed()
        val now = SystemClock.elapsedRealtime()
        if (running) {
            samples.addLast(e to distM)
            while (samples.size > 2 && e - samples.first().first > 25) samples.removeFirst()
        }
        var curPace: Double? = null
        if (samples.size >= 2) {
            val f = samples.first()
            val dd = distM - f.second
            val dtt = e - f.first
            if (dd > 15 && dtt >= 8) curPace = dtt / dd * 1000
        }
        val avgPace = if (distM > 50) e / distM * 1000 else null
        while (stepTimes.isNotEmpty() && now - stepTimes.first() > 15000) stepTimes.removeFirst()
        val window = minOf(15.0, e).coerceAtLeast(1.0)
        val curCad = if (e >= 6 && (hasDetector || hasCounter)) (stepTimes.size * 60.0 / window).roundToInt() else null
        val avgCad = if (e >= 30 && steps > 0) (steps / (e / 60)).roundToInt() else null
        val km = distM / 1000
        val delta = if (distM > 100) e - planTimeAt(plan, km) else null
        val target = planPaceAt(plan, km)

        RunBus.run.value = RunSnapshot(
            status = when { finished -> Status.FINISHED; running -> Status.RUNNING; else -> Status.PAUSED },
            config = cfg, elapsedSec = e, distM = distM, curPace = curPace, avgPace = avgPace,
            curCad = curCad, avgCad = avgCad, steps = steps, deltaSec = delta, targetPace = target,
            splits = splits.toList(), gpsAcc = acc, hasStepSensor = hasDetector || hasCounter
        )

        if (running) {
            // 定时播报当前配速
            if (cfg.intervalSec > 0 && e - lastInterval >= cfg.intervalSec && e - lastSpeak > 8 && curPace != null) {
                lastInterval = e
                val diff = curPace - target
                val verdict = when { diff > 3 -> "偏慢"; diff < -3 -> "偏快"; else -> "正好" }
                speak("当前配速${sayDur(curPace)}，目标${sayDur(target)}，$verdict。")
            }
            // 偏离计划提醒
            if (cfg.deviationSec > 0 && delta != null && abs(delta) > cfg.deviationSec &&
                e - lastAlert > 60 && e - lastSpeak > 10 && distM > 300
            ) {
                lastAlert = e
                val s = abs(delta).roundToInt()
                speak(if (delta > 0) "落后计划${s}秒，请加快一点。" else "领先计划${s}秒，可以稍微放松，留点体力。")
            }
            if (now - lastNotif > 5000) {
                lastNotif = now
                updateNotif()
            }
        }
    }

    private fun speak(text: String) {
        lastSpeak = elapsed()
        if (cfg.voice) voice?.say(text) else voice?.beep()
    }

    private fun announceKm(s: Split) {
        if (!cfg.kmAnnounce) { voice?.beep(); return }
        val d = s.cumSec - s.planCumSec
        val sb = StringBuilder("第${s.km}公里，用时${sayDur(s.sec)}。")
        val ds = abs(d).roundToInt()
        sb.append(
            when {
                ds <= 2 -> "正好在计划上。"
                d > 0 -> "比计划慢${ds}秒。"
                else -> "比计划快${ds}秒。"
            }
        )
        val remainKm = cfg.distKm - s.km
        if (remainKm > 0.05) {
            val nextPace = planPaceAt(plan, s.km + 0.01)
            if (remainKm <= 1.0 + 1e-6) sb.append("最后${if (remainKm >= 0.999) "一公里" else "${(remainKm * 1000).roundToInt()}米"}，目标${sayDur(nextPace)}，加油！")
            else sb.append("下一公里目标${sayDur(nextPace)}。")
        }
        lastAlert = elapsed() // 刚报过，偏离提醒顺延
        speak(sb.toString())
    }

    /* ---------- 暂停 / 继续 / 结束 ---------- */

    private fun pause() {
        if (!running || finished) return
        running = false
        pauseAt = SystemClock.elapsedRealtime()
        metro?.pause()
        if (cfg.voice) voice?.say("已暂停", flush = true)
        tick(); updateNotif()
    }

    private fun resume() {
        if (running || finished || !started) return
        pausedTotal += SystemClock.elapsedRealtime() - pauseAt
        pauseAt = 0L
        running = true
        lastLat = Double.NaN
        kf.reset()
        samples.clear()
        metro?.resume()
        if (cfg.voice) voice?.say("继续跑步", flush = true)
        tick(); updateNotif()
    }

    private fun finish(manual: Boolean) {
        if (finished || !started) { if (!started) stopSelf(); return }
        val frozen = elapsed()
        frozenSec = frozen
        running = false
        finished = true
        metro?.stop(); metro = null
        runCatching { lm?.removeUpdates(locListener) }
        sm?.unregisterListener(this)

        // 最后不足 1 公里的一段
        val covered = splits.size.toDouble()
        if (distM / 1000 - covered > 0.05) {
            val len = distM / 1000 - covered
            splits.add(Split(splits.size + 1, len, frozen - lastSplitCum, frozen, planTimeAt(plan, distM / 1000)))
        }
        val avgPace = if (distM > 50) frozen / distM * 1000 else null
        val avgCad = if (frozen >= 30 && steps > 0) (steps / (frozen / 60)).roundToInt() else null
        RunBus.run.value = RunBus.run.value.copy(
            status = Status.FINISHED, elapsedSec = frozen, distM = distM, avgPace = avgPace, avgCad = avgCad,
            splits = splits.toList(), curPace = null, curCad = null
        )
        handler.removeCallbacks(ticker)

        val sb = StringBuilder(if (manual) "跑步结束。" else "完成${fmtKm(cfg.distKm)}公里！")
        sb.append("总距离${"%.2f".format(distM / 1000)}公里，用时${sayDur(frozen)}")
        if (avgPace != null) sb.append("，平均配速${sayDur(avgPace)}")
        if (avgCad != null) sb.append("，平均步频${avgCad}")
        sb.append("。")
        if (!manual) {
            val d = frozen - cfg.targetSec
            sb.append(if (d <= 0) "比目标快${sayDur(-d)}，太棒了！" else "比目标慢${sayDur(d)}，下次继续。")
        }
        if (cfg.voice) voice?.say(sb.toString(), flush = true) else voice?.beep()

        getSystemService(NotificationManager::class.java).notify(NID, buildNotif("已结束 · ${"%.2f".format(distM / 1000)} km · ${fmtClock(frozen)}"))
        handler.postDelayed({ stopSelf() }, 20000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        metro?.stop()
        runCatching { lm?.removeUpdates(locListener) }
        sm?.unregisterListener(this)
        voice?.shutdown()
        runCatching { if (wake?.isHeld == true) wake?.release() }
        if (started && !finished) {
            RunBus.run.value = RunBus.run.value.copy(status = Status.FINISHED)
        }
        super.onDestroy()
    }

    /* ---------- 通知 ---------- */

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "跑步中", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_run)
            .setContentTitle("跑步节拍")
            .setContentText(text)
            .setOngoing(!finished)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (started && !finished) {
            val act = if (running) ACTION_PAUSE else ACTION_RESUME
            val pi = PendingIntent.getService(
                this, 1, Intent(this, RunService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(0, if (running) "暂停" else "继续", pi)
        }
        return b.build()
    }

    private fun updateNotif() {
        if (finished) return
        val s = RunBus.run.value
        val d = s.deltaSec
        val dTxt = when {
            d == null -> ""
            abs(d) <= 2 -> " · 正好"
            d > 0 -> " · 落后${d.roundToInt()}秒"
            else -> " · 领先${(-d).roundToInt()}秒"
        }
        val text = (if (!running) "已暂停 · " else "") +
            "%.2f km · %s · 配速 %s%s".format(distM / 1000, fmtClock(s.elapsedSec), fmtPace(s.curPace ?: s.avgPace), dTxt)
        getSystemService(NotificationManager::class.java).notify(NID, buildNotif(text))
    }
}
