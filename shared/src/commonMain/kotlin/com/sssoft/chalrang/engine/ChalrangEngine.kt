package com.sssoft.chalrang.engine

import com.sssoft.chalrang.core.Calibration
import com.sssoft.chalrang.core.CareAction
import com.sssoft.chalrang.core.CareInput
import com.sssoft.chalrang.core.CarePolicy
import com.sssoft.chalrang.core.Child
import com.sssoft.chalrang.core.CupSetup
import com.sssoft.chalrang.core.DriveScenario
import com.sssoft.chalrang.core.DriverStyle
import com.sssoft.chalrang.core.Frame
import com.sssoft.chalrang.core.MotionEstimator
import com.sssoft.chalrang.core.Seat
import com.sssoft.chalrang.core.SeatState
import com.sssoft.chalrang.core.TiltLog
import com.sssoft.chalrang.core.WaterCup
import com.sssoft.chalrang.core.Window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.TimeSource

data class UiState(
    val tiltX: Double = 0.0,
    val tiltY: Double = 0.0,
    /** 화면에 그릴 물 높이 */
    val level: Double = WaterCup.DEFAULT_LEVEL,
    val dose: Double = 0.0,
    val spilling: Boolean = false,
    val setup: CupSetup = CupSetup(emptyList(), emptyList(), null, emptyList()),
    val children: List<Child> = emptyList(),
    val car: CarStatus = CarStatus(),
    /** 시연 중이면 운전 습관 이름 */
    val demo: String? = null,
    /** 시연 진행 0~1 */
    val demoProgress: Float = 0f,
    /** 시연에서 아이가 탄 것으로 가정했는지 (에뮬레이터 좌석이 비어 있을 때) */
    val assumedSeat: Boolean = false,
    /** 마지막 음성 안내 */
    val lastSaid: String? = null,
    val raining: Boolean = false,
    /** 창문을 열지 묻는 중이면 그 창문들 */
    val askWindows: List<Window>? = null,
)

/**
 * 찰랑의 중심. 앱(차량 SDK)과 웹(점검용 가짜 차량)이 같은 코드로 20Hz 로
 * 흔들림(차량 신호 또는 시연 시나리오) → 물컵 → 판단(CarePolicy) → 차량 조치·소리
 * 를 돌린다.
 */
class ChalrangEngine(
    private val car: CarPort,
    private val navi: NaviPort,
    private val voice: VoicePort,
    private val store: ChildRepo,
    /** 실제 주행에서 차량·내비 신호로 가속도를 추정하는 곳 (차량 연결 쪽이 값을 넣는다) */
    val motion: MotionEstimator = MotionEstimator(),
) {

    private val cup = WaterCup()
    private var policy = CarePolicy()
    private val log = TiltLog()

    private val _ui = MutableStateFlow(UiState(children = store.load()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var demo: Demo? = null
    private var sickPending = false
    private var windowAnswer: Boolean? = null

    private class Demo(val scenario: DriveScenario, val frames: Iterator<Frame>) {
        var frame: Frame? = null
        /** 아직 재생하지 않은 시나리오 시간 (초) */
        var pending = 0.0
    }

    /** [scope] 가 살아 있는 동안 20Hz 로 돈다 */
    fun start(scope: CoroutineScope) {
        scope.launch { loop() }
    }

    private suspend fun CoroutineScope.loop() {
        val t0 = TimeSource.Monotonic.markNow()
        var last = 0.0
        var lastPoll = 0.0
        while (isActive) {
            delay(TICK_MS)
            val now = t0.elapsedNow().inWholeMicroseconds / 1e6
            val dt = (now - last).coerceIn(0.0, 0.5)
            last = now
            if (now - lastPoll >= 1.0) {
                navi.poll()
                lastPoll = now
            }
            tick(now, dt)
        }
    }

    private fun tick(now: Double, dt: Double) {
        val d = demo
        val children = _ui.value.children
        var seats = car.seats.value
        var assumed = false
        val input: CareInput
        if (d != null) {
            // 시연: 시나리오 프레임을 DEMO_SPEED 배속으로
            d.pending += DEMO_SPEED * dt
            var spillStarted = false
            while (d.pending >= SCENARIO_DT && d.frames.hasNext()) {
                d.pending -= SCENARIO_DT
                val f = d.frames.next()
                val before = cup.spillCount
                if (f.resting) cup.rest(SCENARIO_DT) else cup.step(f.ax, f.ay, SCENARIO_DT)
                log.add(cup.tilt, SCENARIO_DT)
                if (cup.spillCount > before) spillStarted = true
                d.frame = f
            }
            if (seats.values.none { it.riding } && children.isNotEmpty()) {
                // 에뮬레이터 좌석이 비어 있으면 첫 아이가 자기 자리에 탄 것으로 본다
                seats = mapOf(children.first().seat to SeatState(true, true))
                assumed = true
            }
            val setup = applySetup(CupSetup.choose(seats, children))
            val frame = d.frame ?: return
            input = CareInput(
                nowSec = frame.t, setup = setup, dose = cup.dose, spillStarted = spillStarted,
                speedKmh = frame.speedKmh, raining = frame.raining || car.raining.value,
                next = frame.next, restAreaKm = frame.restAreaKm, sickNow = takeSick(), windowAnswer = takeAnswer(),
            )
            if (!d.frames.hasNext()) {
                stopDemo()
                return
            }
        } else {
            motion.update(now)
            val before = cup.spillCount
            cup.step(motion.ax, motion.ay, dt)
            log.add(cup.tilt, dt)
            val setup = applySetup(CupSetup.choose(seats, children))
            val ahead = navi.ahead.value
            input = CareInput(
                nowSec = now, setup = setup, dose = cup.dose, spillStarted = cup.spillCount > before,
                speedKmh = motion.speedKmh, raining = car.raining.value,
                next = ahead.next, restAreaKm = ahead.restAreaKm, sickNow = takeSick(), windowAnswer = takeAnswer(),
            )
        }
        perform(policy.decide(input))
        _ui.update {
            it.copy(
                tiltX = cup.tiltX, tiltY = cup.tiltY, level = input.setup.level ?: ALONE_LEVEL,
                dose = cup.dose, spilling = cup.spilling, setup = input.setup, car = car.status.value,
                demoProgress = demo?.let { dm -> ((dm.frame?.t ?: 0.0) / dm.scenario.durationSec).toFloat() } ?: 0f,
                assumedSeat = assumed, raining = input.raining,
            )
        }
    }

    /** 탄 사람이 바뀌면 물 높이를 바꾼다 (누적량은 그대로) */
    private fun applySetup(setup: CupSetup): CupSetup {
        cup.level = setup.level ?: ALONE_LEVEL
        return setup
    }

    private fun takeSick(): Boolean = sickPending.also { sickPending = false }

    private fun takeAnswer(): Boolean? = windowAnswer.also { windowAnswer = null }

    private fun perform(actions: List<CareAction>) {
        for (a in actions) {
            when (a) {
                CareAction.Chime -> voice.chime()
                is CareAction.Say -> {
                    voice.say(a.text)
                    _ui.update { it.copy(lastSaid = a.text) }
                }
                is CareAction.FreshAir -> car.freshAir(a.on)
                is CareAction.OpenWindows -> car.openWindows(a.windows, a.percent)
                CareAction.CloseWindows -> car.closeWindows()
                is CareAction.AskWindow -> _ui.update { it.copy(askWindows = a.windows) }
                CareAction.AskDone -> _ui.update { it.copy(askWindows = null) }
                CareAction.DustGuard -> car.dustGuard()
            }
        }
    }

    // ---- 화면에서 ----

    /**
     * "지금 멀미": 이번 주행 기록을 다시 재생해 가장 예민한 탑승 아이의 물 높이를 맞추고,
     * 바로 환기·휴식 안내를 한다.
     */
    fun sickNow() {
        val child = _ui.value.setup.primary
        if (child != null && log.size > 0) {
            val found = Calibration.levelForSickness(log)
            val level = Calibration.blend(child.level.takeIf { child.calibrations > 0 }, found)
            updateChild(child.copy(level = level, calibrations = child.calibrations + 1))
            cup.level = level
            val text = "${child.name} 기준을 맞췄어요. 다음에는 멀미하기 전에 알려 드릴게요"
            voice.say(text)
            _ui.update { it.copy(lastSaid = text) }
        }
        sickPending = true
    }

    /** 창문 질문에 답: true = 열기, false = 열지 않기 (먼지 나는 길) */
    fun answerWindow(open: Boolean) {
        windowAnswer = open
    }

    fun addChild(name: String, seat: Seat) {
        val list = _ui.value.children.filter { it.seat != seat } + Child(newId(), name.trim(), seat)
        saveChildren(list)
    }

    fun removeChild(child: Child) = saveChildren(_ui.value.children.filter { it.id != child.id })

    private fun updateChild(child: Child) = saveChildren(_ui.value.children.map { if (it.id == child.id) child else it })

    private fun saveChildren(list: List<Child>) {
        store.save(list)
        _ui.update { it.copy(children = list) }
    }

    fun startDemo(style: DriverStyle) {
        resetTrip()
        // 시연 시간은 배속으로 흐르므로 창문 질문은 실제 20초 동안 기다린다
        policy = CarePolicy(askTimeoutSec = CarePolicy.ASK_TIMEOUT * DEMO_SPEED)
        val s = DriveScenario.weekendTrip(style)
        demo = Demo(s, s.frames(SCENARIO_DT).iterator())
        _ui.update { it.copy(demo = style.name, demoProgress = 0f) }
    }

    fun stopDemo() {
        demo = null
        resetTrip()
        _ui.update { it.copy(demo = null, demoProgress = 0f, assumedSeat = false) }
    }

    /** 새 주행: 하던 차량 조치를 되돌리고 물컵·기록을 비운다 */
    private fun resetTrip() {
        if (policy.windowsOpen) car.closeWindows()
        if (policy.ventilating) car.freshAir(false)
        policy = CarePolicy()
        windowAnswer = null
        _ui.update { it.copy(askWindows = null) }
        cup.reset()
        log.clear()
    }

    /** 끝낼 때: 하던 차량 조치를 되돌린다 */
    fun close() = resetTrip()

    private fun newId(): String = Random.nextLong().toULong().toString(16)

    companion object {
        const val TICK_MS = 50L
        const val SCENARIO_DT = 0.1
        /** 시연 배속 (13분 경로 → 약 2분 10초) */
        const val DEMO_SPEED = 6.0
        /** 혼자 주행일 때 그리는 물 높이 */
        const val ALONE_LEVEL = 0.3
    }
}
