package com.sssoft.chalrang.web

import com.sssoft.chalrang.engine.LocalSensor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 브라우저 움직임 센서(devicemotion)를 모아 두는 곳. 값마다 Kotlin 을 부르지 않고 글자로 쌓아 두었다가
 * [takeMotion] 으로 한꺼번에 꺼낸다. 아이폰은 화면을 한 번 눌러야 권한을 물을 수 있어 첫 터치 때 묻는다 (그 전엔 값이 안 온다).
 * 줄마다 "시각(초),중력x,y,z,가속x,y,z,순수가속을기기가줬는지(1/0)" (중력 = 중력 포함 값 − 순수 가속).
 */
private fun startMotion(): Unit = js(
    """{
        if (window.__chalrangMotion != null) return;
        window.__chalrangMotion = '';
        let avg = null;
        const on = (e) => {
            const g = e.accelerationIncludingGravity, a = e.acceleration;
            if (!g || g.x == null) return;
            const gz0 = g.z || 0;
            let lx, ly, lz, lin = 1;
            if (a && a.x != null) { lx = a.x; ly = a.y || 0; lz = a.z || 0; }
            else {
                // 순수 가속을 주지 않는 기기: 중력 포함 값의 느린 평균(약 1초)을 중력으로 보고 뺀다
                avg = avg ? [avg[0] * 0.98 + g.x * 0.02, avg[1] * 0.98 + g.y * 0.02, avg[2] * 0.98 + gz0 * 0.02] : [g.x, g.y, gz0];
                lx = g.x - avg[0]; ly = g.y - avg[1]; lz = gz0 - avg[2]; lin = 0;
            }
            let s = window.__chalrangMotion;
            // 꺼내 가지 못하고 너무 쌓이면 (화면이 멈췄던 때) 버리고 새로
            if (s.length > 200000) s = '';
            // 시각은 페이지 시계로 (기기가 주는 event 시각은 브라우저마다 다르다)
            window.__chalrangMotion = s + (performance.now() / 1000).toFixed(3) + ',' + (g.x - lx).toFixed(3) + ',' + (g.y - ly).toFixed(3) + ',' + (gz0 - lz).toFixed(3) +
                ',' + lx.toFixed(3) + ',' + ly.toFixed(3) + ',' + lz.toFixed(3) + ',' + lin + ';';
        };
        // 바로 듣는다 (안드로이드·최신 크롬은 권한이 필요 없다). 아이폰은 권한을 받아야 값이 오므로 첫 터치 때 묻는다
        window.addEventListener('devicemotion', on);
        // 권한을 묻는 기기: 먼저 바로 물어 보고(크롬은 바로 허락), 안 되면(아이폰) 첫 터치 때 — 앱 화면보다 먼저 잡는다(capture)
        if (typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function') {
            const ask = () => { DeviceMotionEvent.requestPermission().catch(() => {}); };
            ask();
            for (const k of ['pointerdown', 'touchend', 'click']) window.addEventListener(k, ask, { once: true, capture: true });
        }
        window.__chalrangTouch = (navigator.maxTouchPoints || 0) > 0;
    }""",
)

private fun takeMotion(): String = js("{ const s = window.__chalrangMotion || ''; window.__chalrangMotion = ''; return s; }")

private fun touchDevice(): Boolean = js("window.__chalrangTouch === true")

/** 화면이 떠 있는 동안 [DT] 마다 쌓인 센서 값을 [sensor] 로 넘긴다. [now] = 엔진 시계 */
suspend fun pumpMotion(sensor: LocalSensor, now: () -> Double) {
    startMotion()
    var count = 0
    var since = now()
    val started = since
    var ever = false
    while (currentCoroutineContext().isActive) {
        delay((LocalSensor.DT * 1000).toLong())
        // 한 번 잘못돼도 멈추지 않게
        runCatching {
            val text = takeMotion()
            if (text.isNotEmpty()) {
                var got = 0
                var linear = true
                for (line in text.split(';')) {
                    val v = line.split(',')
                    if (v.size != 8) continue
                    val d = DoubleArray(7) { v[it].toDoubleOrNull() ?: Double.NaN }
                    if (d.any { it.isNaN() }) continue
                    sensor.onMotion(d[0], d[1], d[2], d[3], d[4], d[5], d[6])
                    linear = v[7] == "1"
                    got++
                }
                if (got > 0) { sensor.received(now()); sensor.linear = linear; ever = true }
                count += got
            }
            val t = now()
            // 터치 기기인데 값이 안 오면: 권한(아이폰)을 받으려면 화면을 한 번 눌러야 한다
            sensor.needsTap = !ever && touchDevice() && t - started > 1.5
            if (t - since >= 1.0) { sensor.rateHz = (count / (t - since)).toInt(); count = 0; since = t }
        }
    }
}
