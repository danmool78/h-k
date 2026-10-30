package com.sssoft.chalrang.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.sssoft.chalrang.engine.VoicePort
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 소리 안내. 운전 중에는 화면 대신 소리가 주된 피드백이다.
 * - [chime]: "찰랑" 물소리 (합성). 재생할 때만 오디오 트랙을 만들고 끝나면 바로 반납
 * - [say]: 안드로이드 기본 TTS. 말할 일이 생기면 켜고, 다 말한 뒤 [IDLE_MS] 동안 더 없으면 반납
 *   (Pleos TTS 가 열리면 이곳만 바꾸면 된다)
 * 모든 TTS 상태는 메인 스레드에서만 바꾼다.
 */
class Voice(context: Context) : VoicePort {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<String>()
    private val releaseTts = Runnable { shutdownTts() }

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** 합성한 물소리 (한 번만 만들어 둔다, 약 50KB) */
    private val chimePcm: ShortArray by lazy { synthSlosh() }

    override fun say(text: String) {
        main.post {
            main.removeCallbacks(releaseTts)
            if (tts == null) create()
            if (ready) speak(text) else {
                if (pending.size >= MAX_PENDING) pending.removeFirst()
                pending.addLast(text)
            }
        }
    }

    private fun create() {
        tts = TextToSpeech(app) { status ->
            main.post {
                val engine = tts ?: return@post
                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "TTS 시작 실패: $status")
                    shutdownTts()
                    return@post
                }
                ready = true
                engine.language = Locale.KOREAN
                engine.setAudioAttributes(attrs)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { main.post { scheduleRelease() } }
                    @Deprecated("API 21 이전")
                    override fun onError(id: String?) { main.post { scheduleRelease() } }
                })
                while (pending.isNotEmpty()) speak(pending.removeFirst())
            }
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "chalrang-${text.hashCode()}")
    }

    /** 다 말했으면 잠시 뒤 반납 (그 사이 또 말하면 취소) */
    private fun scheduleRelease() {
        if (tts?.isSpeaking == true) return
        main.removeCallbacks(releaseTts)
        main.postDelayed(releaseTts, IDLE_MS)
    }

    private fun shutdownTts() {
        main.removeCallbacks(releaseTts)
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
        ready = false
        pending.clear()
    }

    override fun chime() {
        try {
            val pcm = chimePcm
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            var released = false
            val done = Runnable {
                if (!released) {
                    released = true
                    runCatching { track.stop() }
                    track.release()
                }
            }
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { main.post(done) }
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            }, main)
            track.play()
            // 끝 신호가 오지 않아도 반드시 반납
            main.postDelayed(done, (pcm.size * 1000L / RATE) + 500)
        } catch (e: Exception) {
            Log.w(TAG, "찰랑 소리 실패", e)
        }
    }

    fun release() {
        main.post { shutdownTts() }
    }

    companion object {
        private const val TAG = "Voice"
        private const val RATE = 22050
        /** 다 말한 뒤 TTS 를 반납할 때까지 (밀리초) */
        const val IDLE_MS = 20_000L
        private const val MAX_PENDING = 3

        /**
         * "찰랑" 물소리: 잡음을 공명 필터(중심 450 → 1300Hz 로 미끄러짐)에 통과시킨 출렁임 +
         * 작은 물방울 몇 개. 웹(Browser.kt)도 같은 방식으로 만든다.
         */
        private fun synthSlosh(): ShortArray {
            val len = (RATE * 0.7).toInt()
            val out = DoubleArray(len)
            val rnd = Random(7)
            // 출렁임: 2차 공명 필터를 지난 잡음
            var y1 = 0.0
            var y2 = 0.0
            for (i in 0 until len) {
                val t = i.toDouble() / RATE
                val fc = 450 + 850 * (1 - exp(-t * 6))
                val r = 0.985
                val w = 2 * PI * fc / RATE
                val x = rnd.nextDouble() * 2 - 1
                val y = x * (1 - r) + 2 * r * kotlin.math.cos(w) * y1 - r * r * y2
                y2 = y1; y1 = y
                val env = (1 - exp(-t * 60)) * exp(-t * 5.5) * (1 + 0.35 * sin(2 * PI * 7 * t))
                out[i] += y * env * 6
            }
            // 물방울: 짧게 위로 미끄러지는 낮은 음
            for ((start, f0) in listOf(0.05 to 520.0, 0.16 to 760.0, 0.27 to 610.0, 0.38 to 900.0)) {
                val s0 = (start * RATE).toInt()
                var phase = 0.0
                for (i in 0 until minOf(len - s0, (RATE * 0.09).toInt())) {
                    val t = i.toDouble() / RATE
                    phase += 2 * PI * f0 * (1 + 1.8 * t / 0.09) / RATE
                    out[s0 + i] += 0.22 * exp(-t * 45) * sin(phase)
                }
            }
            val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
            return ShortArray(len) { (out[it] / peak * 0.55 * Short.MAX_VALUE).toInt().toShort() }
        }
    }
}
