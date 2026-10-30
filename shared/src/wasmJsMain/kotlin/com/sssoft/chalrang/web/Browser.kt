package com.sssoft.chalrang.web

import com.sssoft.chalrang.core.Child
import com.sssoft.chalrang.core.Seat
import com.sssoft.chalrang.core.Sensitivity
import com.sssoft.chalrang.engine.ChildRepo
import com.sssoft.chalrang.engine.VoicePort

private fun jsSay(text: String): Unit = js(
    """{
        if (!('speechSynthesis' in window)) return;
        const u = new SpeechSynthesisUtterance(text);
        u.lang = 'ko-KR';
        window.speechSynthesis.speak(u);
    }""",
)

/**
 * 앱(Voice.kt)과 같은 "찰랑" 물소리: 잡음을 공명 필터(450 → 1300Hz)에 통과시킨 출렁임 + 작은 물방울.
 * 오디오 장치는 소리를 낼 때 열고, 5초 동안 더 없으면 닫아 반납한다.
 */
private fun jsChime(): Unit = js(
    """{
        const AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) return;
        const ctx = window.__chalrangAudio || (window.__chalrangAudio = new AC());
        if (ctx.state === 'suspended') ctx.resume();
        const t0 = ctx.currentTime;
        const len = Math.floor(ctx.sampleRate * 0.7);
        const buf = ctx.createBuffer(1, len, ctx.sampleRate);
        const d = buf.getChannelData(0);
        for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
        const src = ctx.createBufferSource(); src.buffer = buf;
        const bp = ctx.createBiquadFilter(); bp.type = 'bandpass'; bp.Q.value = 6;
        bp.frequency.setValueAtTime(450, t0); bp.frequency.exponentialRampToValueAtTime(1300, t0 + 0.4);
        const g = ctx.createGain();
        g.gain.setValueAtTime(0.0001, t0);
        g.gain.exponentialRampToValueAtTime(0.5, t0 + 0.03);
        g.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.7);
        src.connect(bp).connect(g).connect(ctx.destination);
        src.start(t0); src.stop(t0 + 0.7);
        [[0.05, 520], [0.16, 760], [0.27, 610], [0.38, 900]].forEach(([dt, f]) => {
            const o = ctx.createOscillator(); const og = ctx.createGain();
            o.frequency.setValueAtTime(f, t0 + dt); o.frequency.linearRampToValueAtTime(f * 2.8, t0 + dt + 0.09);
            og.gain.setValueAtTime(0.0001, t0 + dt); og.gain.exponentialRampToValueAtTime(0.08, t0 + dt + 0.004);
            og.gain.exponentialRampToValueAtTime(0.0001, t0 + dt + 0.09);
            o.connect(og).connect(ctx.destination); o.start(t0 + dt); o.stop(t0 + dt + 0.1);
        });
        clearTimeout(window.__chalrangAudioClose);
        window.__chalrangAudioClose = setTimeout(() => { ctx.close(); window.__chalrangAudio = null; }, 5000);
    }""",
)

private fun lsGet(key: String): String? = js("window.localStorage.getItem(key)")
private fun lsSet(key: String, value: String): Unit = js("window.localStorage.setItem(key, value)")

class BrowserVoice : VoicePort {
    /** 소리 끔 (점검할 때 조용히) */
    var muted = false

    override fun chime() {
        if (!muted) runCatching { jsChime() }
    }

    override fun say(text: String) {
        if (!muted) runCatching { jsSay(text) }
    }
}

/** 브라우저 localStorage 에 아이 프로필 저장. 처음 열면 점검용 아이 한 명을 넣어 둔다 */
class BrowserChildRepo : ChildRepo {
    override fun load(): List<Child> {
        val raw = runCatching { lsGet(KEY) }.getOrNull() ?: return listOf(Child("demo", "민지", Seat.REAR_LEFT))
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split('\t')
            runCatching {
                val periods = p.getOrNull(5)?.split(',')?.mapNotNull { it.toDoubleOrNull() }.orEmpty()
                Child(p[0], p[1], Seat.valueOf(p[2]), p[3].toDouble(), p[4].toInt(), Sensitivity(periods))
            }.getOrNull()
        }
    }

    override fun save(children: List<Child>) {
        val raw = children.joinToString("\n") { listOf(it.id, it.name.replace('\t', ' '), it.seat.name, it.level, it.calibrations, it.sensitivity.periods.joinToString(",")).joinToString("\t") }
        runCatching { lsSet(KEY, raw) }
    }

    private companion object {
        const val KEY = "chalrang.children"
    }
}
