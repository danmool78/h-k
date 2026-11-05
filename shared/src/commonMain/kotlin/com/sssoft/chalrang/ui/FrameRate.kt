package com.sssoft.chalrang.ui

/** 움직이는 화면(물컵·3D 브리핑·흔들림 그래프)을 다시 그리는 횟수 (초당). 차량 PC 가 느려도 부드럽게 보일 만큼만 */
internal const val FPS = 24

/**
 * 화면 주사(보통 60Hz)마다 깨어나도 초당 [FPS] 번만 통과시킨다.
 * 60Hz 에서는 2·3 프레임 간격을 번갈아 써서 평균 [FPS] 가 되게 (매번 2.5 프레임을 기다릴 수는 없으므로).
 */
internal class FrameGate(fps: Int = FPS) {
    private val interval = 1_000_000_000L / fps
    private var next = 0L

    /** 이 프레임([now] ns)에 그릴지 */
    fun pass(now: Long): Boolean {
        // 주사 간격의 반쯤 일찍 와도 통과 (60Hz 주사 시각이 목표 시각과 딱 맞지 않으므로)
        if (next != 0L && now < next - SLACK) return false
        // 멈췄다 돌아오면(백그라운드 등) 밀린 만큼 몰아서 그리지 않고 지금부터 다시
        next = if (next == 0L || now - next > interval) now + interval else next + interval
        return true
    }

    private companion object {
        const val SLACK = 8_000_000L
    }
}
