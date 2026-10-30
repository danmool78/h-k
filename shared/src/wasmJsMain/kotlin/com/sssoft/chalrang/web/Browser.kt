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

/** 앱(Voice.kt)과 같은 "찰랑": 위로 미끄러지는 맑은 음 두 번 */
private fun jsChime(): Unit = js(
    """{
        const AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) return;
        const ctx = window.__chalrangAudio || (window.__chalrangAudio = new AC());
        if (ctx.state === 'suspended') ctx.resume();
        const t0 = ctx.currentTime;
        [[0, 1250], [0.13, 1650]].forEach(([d, f]) => {
            const o = ctx.createOscillator();
            const g = ctx.createGain();
            o.type = 'sine';
            o.frequency.setValueAtTime(f, t0 + d);
            o.frequency.exponentialRampToValueAtTime(f * 1.6, t0 + d + 0.08);
            g.gain.setValueAtTime(0.0001, t0 + d);
            g.gain.exponentialRampToValueAtTime(0.25, t0 + d + 0.005);
            g.gain.exponentialRampToValueAtTime(0.0001, t0 + d + 0.45);
            o.connect(g).connect(ctx.destination);
            o.start(t0 + d);
            o.stop(t0 + d + 0.5);
        });
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
