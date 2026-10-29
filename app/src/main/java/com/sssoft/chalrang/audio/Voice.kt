package com.sssoft.chalrang.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 소리 안내. 운전 중에는 화면 대신 소리가 주된 피드백이다.
 * - [chime]: "찰랑" (물방울 소리, 직접 합성)
 * - [say]: 안드로이드 기본 TTS (Pleos TTS 가 열리면 바꿀 수 있게 이곳에만 둔다)
 */
class Voice(context: Context) {

    @Volatile private var ready = false
    private val tts: TextToSpeech

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val chimePcm: ShortArray = synthChime()

    init {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) engine?.language = Locale.KOREAN
        }
        tts = engine
        tts.setAudioAttributes(attrs)
    }

    fun say(text: String) {
        if (!ready) {
            Log.i(TAG, "TTS 준비 전: $text")
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun chime() {
        try {
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
                .setBufferSizeInBytes(chimePcm.size * 2)
                .build()
            track.write(chimePcm, 0, chimePcm.size)
            track.setNotificationMarkerPosition(chimePcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
            track.play()
        } catch (e: Exception) {
            Log.w(TAG, "찰랑 소리 실패", e)
        }
    }

    fun release() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        private const val TAG = "Voice"
        private const val RATE = 22050

        /** 물방울 두 번: 위로 미끄러지는 맑은 음 + 짧은 감쇠 ("찰-랑") */
        private fun synthChime(): ShortArray {
            val drops = listOf(0.0 to 1250.0, 0.13 to 1650.0)
            val len = (RATE * 0.55).toInt()
            val out = DoubleArray(len)
            for ((start, f0) in drops) {
                val s0 = (start * RATE).toInt()
                var phase = 0.0
                for (i in 0 until len - s0) {
                    val t = i.toDouble() / RATE
                    val f = f0 * (1 + 0.6 * (1 - exp(-t * 40)))   // 물방울처럼 음이 살짝 올라간다
                    phase += 2 * PI * f / RATE
                    val env = exp(-t * 11) * (1 - exp(-t * 600))
                    out[s0 + i] += env * (sin(phase) + 0.25 * sin(2.01 * phase))
                }
            }
            val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
            return ShortArray(len) { (out[it] / peak * 0.6 * Short.MAX_VALUE).toInt().toShort() }
        }
    }
}
