package com.sssoft.chalrang.web

import com.sssoft.chalrang.engine.LocalSensor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 브라우저 움직임 센서(devicemotion)를 모아 두는 곳. 값마다 Kotlin 을 부르지 않고 글자로 쌓아 두었다가
 * [takeMotion] 으로 한꺼번에 꺼낸다. 아이폰은 화면을 한 번 눌러야 권한을 물을 수 있어 첫 터치 때 묻는다 (그 전엔 값이 안 온다).
 * 줄마다 "시각(초),중력x,y,z,가속x,y,z" (중력 = 중력 포함 값 − 순수 가속).
 */
private fun startMotion(): Unit = js(
    """{
        if (window.__chalrangMotion != null) return;
        window.__chalrangMotion = '';
        const on = (e) => {
            const g = e.accelerationIncludingGravity, a = e.acceleration;
            if (!g || g.x == null) return;
            const lx = a && a.x != null ? a.x : 0, ly = a && a.y != null ? a.y : 0, lz = a && a.z != null ? a.z : 0;
            const s = window.__chalrangMotion;
            if (s.length > 200000) return;
            window.__chalrangMotion = s + (e.timeStamp / 1000).toFixed(3) + ',' + (g.x - lx).toFixed(3) + ',' + (g.y - ly).toFixed(3) + ',' + ((g.z || 0) - lz).toFixed(3) +
                ',' + lx.toFixed(3) + ',' + ly.toFixed(3) + ',' + lz.toFixed(3) + ';';
        };
        // 바로 듣는다 (안드로이드·최신 크롬은 권한이 필요 없다). 아이폰은 권한을 받아야 값이 오므로 첫 터치 때 묻는다
        window.addEventListener('devicemotion', on);
        if (typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function') {
            const ask = () => { DeviceMotionEvent.requestPermission().catch(() => {}); };
            window.addEventListener('touchend', ask, { once: true });
            window.addEventListener('click', ask, { once: true });
        }
    }""",
)

private fun takeMotion(): String = js("{ const s = window.__chalrangMotion || ''; window.__chalrangMotion = ''; return s; }")

/** 화면이 떠 있는 동안 [DT] 마다 쌓인 센서 값을 [sensor] 로 넘긴다. [now] = 엔진 시계 */
suspend fun pumpMotion(sensor: LocalSensor, now: () -> Double) {
    startMotion()
    while (currentCoroutineContext().isActive) {
        delay((LocalSensor.DT * 1000).toLong())
        val text = takeMotion()
        if (text.isEmpty()) continue
        var got = false
        for (line in text.split(';')) {
            val v = line.split(',')
            if (v.size != 7) continue
            val d = v.map { it.toDoubleOrNull() ?: Double.NaN }
            if (d.any { it.isNaN() }) continue
            sensor.onMotion(d[0], d[1], d[2], d[3], d[4], d[5], d[6])
            got = true
        }
        if (got) sensor.received(now())
    }
}
