package com.ilcf.paobu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * 节拍器：用 AudioTrack 连续写入合成的 PCM，节拍间隔精确到采样点。
 * 不申请音频焦点，所以可以和 QQ音乐 / 网易云 等其他 App 的音乐同时播放。
 */
class Metronome(private val cadence: Int, volume: Int, sound: Int) {
    private val sr = 44100
    private val click: ShortArray = synthClick(sound)
    @Volatile private var alive = true
    @Volatile private var paused = false
    private val track: AudioTrack
    private val thread: Thread

    init {
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, sr / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.setVolume(volume / 100f)
        thread = Thread({ loop() }, "metronome")
    }

    private fun synthClick(sound: Int): ShortArray {
        val f = if (sound == 1) 820.0 else 1900.0
        val tau = if (sound == 1) 0.018 else 0.012
        val harm = if (sound == 1) 2.7 else 2.0
        val ha = if (sound == 1) 0.35 else 0.25
        val n = (sr * 0.06).toInt()
        return ShortArray(n) { i ->
            val t = i / sr.toDouble()
            val att = if (i < 130) i / 130.0 else 1.0
            val env = exp(-t / tau) * att * (if (i > n - 64) (n - i) / 64.0 else 1.0)
            val v = (sin(2 * PI * f * t) + ha * sin(2 * PI * f * harm * t)) * env * 0.75
            (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
    }

    private fun loop() {
        try {
            val period = sr * 60.0 / cadence
            var next = 0.0
            var pos = 0L
            var ci = -1
            val n = 2205
            val buf = ShortArray(n)
            while (alive) {
                if (paused) { Thread.sleep(20); continue }
                for (i in 0 until n) {
                    val frame = pos + i
                    if (frame >= next) { ci = 0; next += period }
                    if (ci in click.indices) { buf[i] = click[ci]; ci++ } else buf[i] = 0
                }
                track.write(buf, 0, n)
                pos += n
            }
        } catch (_: Throwable) {
        }
    }

    fun start() { track.play(); thread.start() }
    fun pause() { paused = true; runCatching { track.pause() } }
    fun resume() { runCatching { track.play() }; paused = false }
    fun stop() {
        alive = false
        paused = false
        runCatching { thread.join(600) }
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}

/**
 * 语音播报：系统 TTS（中文）。播报时以“可压低”方式申请音频焦点，其他 App 的音乐会自动变小声。
 * 没有中文语音引擎时退回提示音。
 */
class Voice(ctx: Context, private val onReady: (Boolean) -> Unit = {}) : TextToSpeech.OnInitListener {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()
    private val pending = AtomicInteger(0)
    private var seq = 0
    private val tts = TextToSpeech(ctx.applicationContext, this)
    private var tone: ToneGenerator? = null

    @Volatile var ok = false
        private set

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            ok = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            tts.setAudioAttributes(attrs)
            tts.setSpeechRate(1.05f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { release() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { release() }
            })
        }
        onReady(ok)
    }

    private fun release() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            am.abandonAudioFocusRequest(focus)
        }
    }

    fun say(text: String, flush: Boolean = false) {
        if (!ok) { beep(); return }
        pending.incrementAndGet()
        am.requestAudioFocus(focus)
        val r = tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "u${seq++}")
        if (r != TextToSpeech.SUCCESS) release()
    }

    fun beep() {
        runCatching {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 90).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        }
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
        runCatching { tone?.release() }
        runCatching { am.abandonAudioFocusRequest(focus) }
    }
}
