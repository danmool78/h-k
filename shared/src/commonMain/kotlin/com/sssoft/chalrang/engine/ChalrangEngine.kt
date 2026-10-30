package com.sssoft.chalrang.engine

import com.sssoft.chalrang.core.AppSettings
import com.sssoft.chalrang.core.Calibration
import com.sssoft.chalrang.core.CareAction
import com.sssoft.chalrang.core.CareInput
import com.sssoft.chalrang.core.CarePolicy
import com.sssoft.chalrang.core.Child
import com.sssoft.chalrang.core.CupSetup
import com.sssoft.chalrang.core.DriveScenario
import com.sssoft.chalrang.core.DriveTrace
import com.sssoft.chalrang.core.TraceKind
import com.sssoft.chalrang.core.TripReport
import com.sssoft.chalrang.core.RoutePath
import com.sssoft.chalrang.core.RoutePlanner
import com.sssoft.chalrang.core.RoutePoint
import com.sssoft.chalrang.core.RoutePreview
import com.sssoft.chalrang.core.Terrain
import com.sssoft.chalrang.core.TripSummary
import com.sssoft.chalrang.core.DriverStyle
import com.sssoft.chalrang.core.Frame
import com.sssoft.chalrang.core.MotionEstimator
import com.sssoft.chalrang.core.Seat
import com.sssoft.chalrang.core.SeatState
import com.sssoft.chalrang.core.Sensitivity
import com.sssoft.chalrang.core.SickPeriod
import com.sssoft.chalrang.core.TiltLog
import com.sssoft.chalrang.core.WaterCup
import com.sssoft.chalrang.core.Window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.TimeSource

data class UiState(
    val tiltX: Double = 0.0,
    val tiltY: Double = 0.0,
    /** 위아래 출렁임 (g) */
    val tiltZ: Double = 0.0,
    /** 길 경사 (rad): 화면 수면 = 출렁임 - 경사 */
    val gradeX: Double = 0.0,
    val gradeY: Double = 0.0,
    /** 화면에 그릴 물 높이 = 남은 물 (평소 운전 습관으로 채우고, 넘친 만큼 줄어든다) */
    val level: Double = WaterCup.DEFAULT_LEVEL,
    val settings: AppSettings = AppSettings(),
    /** 넘치기 시작하는 기울기 (g): 물 양 기준 (화면 그림용) */
    val freeboard: Double = WaterCup.freeboardOf(WaterCup.DEFAULT_LEVEL),
    /** 이번 주행을 시작할 때 채운 물과 그 근거 (최근 주행 점수, 없으면 null) */
    val startWater: Double = WaterCup.DEFAULT_LEVEL,
    val habitScore: Int? = null,
    /** 평소 운전 습관만큼의 물 (이보다 많이 채우면 도전) */
    val habitWater: Double = WaterCup.DEFAULT_LEVEL,
    /** 지금 물을 채우거나 덜 수 있는지 (출발 전) */
    val canAdjust: Boolean = true,
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
    /** 휴대폰 시연 중 */
    val remote: Boolean = false,
    /** 방금 끝난 주행의 평가 (닫으면 null) */
    val report: TripReport? = null,
    /** 그 전 주행 점수 (비교용) */
    val previousScore: Int? = null,
    /** 가는 길 미리보기 (길 안내·시연 중일 때) 와 지금까지 온 거리 (m) */
    val route: RoutePreview? = null,
    val routeAtM: Double = 0.0,
    /** 3D 경로 브리핑 중이면 그 경로 */
    val briefing: RoutePath? = null,
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
    /** 휴대폰 시연: 관리자가 휴대폰을 흔들면 그 가속도를 받아 차 흔들림 대신 쓴다 */
    private val remote: RemoteFeed? = RemoteFeed(),
) {
    /** 휴대폰 시연 시각 (받은 시료 수 × 간격) */
    private var remoteT = 0.0
    private var remoteWas = false

    private val cup = WaterCup()
    private var policy = CarePolicy()
    private val log = TiltLog()

    /** 상세 흔들림 기록 (차 흔들림 → 물 출렁임, 안내·조치). 화면의 "흔들림 기록" 에서 본다 */
    val trace = DriveTrace()
    /** 지금 시각 (시연은 시나리오 시각) — 기록에 쓴다 */
    private var clock = 0.0

    private val _ui = MutableStateFlow(UiState(children = store.load()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** 지난 주행 평가 (최근 것이 끝) */
    private val trips = store.loadTrips().toMutableList()
    private var settings = AppSettings.decode(store.loadSettings())
    /** 남은 물 (0~1): 주행을 시작할 때 평소 운전 습관만큼 채우고, 넘친 만큼 줄어든다 */
    private var water = habitWater()
    private var startWater = water

    /** 평소 운전 습관: 최근 5번 점수 (최근일수록 무겁게) */
    private fun habitScore(): Int? {
        val recent = trips.takeLast(HABIT_TRIPS)
        if (recent.isEmpty()) return null
        var sum = 0.0
        var w = 0.0
        recent.forEachIndexed { i, t -> val k = (i + 1).toDouble(); sum += t.score * k; w += k }
        return (sum / w).roundToInt()
    }

    /** 점수 → 처음 채우는 물: 100점이면 0.9, 0점이면 0.35 (기록이 없으면 보통 0.6). 젠틀할수록 가득 */
    private fun habitWater(): Double = if (!settings.habitFill) WaterCup.DEFAULT_LEVEL else habitScore()?.let { WATER_MIN_FILL + (WATER_MAX_FILL - WATER_MIN_FILL) * it / 100.0 } ?: WaterCup.DEFAULT_LEVEL

    /** 물 양을 조절한 만큼 (+ 도전, - 쉽게). 다음 주행을 시작할 때 평소 물에 더한다 */
    private var waterAdjust = 0.0
    /** 물컵 넘침 (물 양 기준, 화면·평가): 아이 멀미 기준과 따로 */
    private var waterSpilling = false
    private var waterSpills = 0

    /**
     * 물 양 기준 넘침: 물이 많을수록 작은 쏠림에도 넘치고, 넘친 만큼 물이 준다.
     * (아이 멀미 누적·경고는 [cup] 이 아이 기준으로 따로 계산한다)
     */
    private fun pour(dt: Double): Boolean {
        val tilt = cup.tilt
        val fb = WaterCup.freeboardOf(water)
        val now = tilt > fb
        val started = now && !waterSpilling
        if (started) waterSpills++
        waterSpilling = now
        if (now) water = (water - WaterCup.spillRate(tilt, water) * dt * WATER_PER_DOSE).coerceAtLeast(WATER_LEFT_MIN)
        return started
    }

    /**
     * 흔들림 기록: 물 높이·넘침은 화면에 보이는 물컵(남은 물) 그대로, 멀미 누적은 아이 기준.
     * 그래서 분석 화면의 물컵·넘침 기준·넘침 횟수가 첫 화면과 같다.
     */
    private fun record(t: Double, ax: Double, ay: Double, kmh: Double, az: Double) {
        trace.record(t, ax, ay, cup.tiltX, cup.tiltY, kmh, cup.dose, water, waterSpilling, az, cup.tiltZ, cup.gradeX, cup.gradeY)
    }

    /** 출발 전에만 물을 더 채우거나(도전) 덜어 낸다 */
    fun adjustWater(delta: Double) {
        if (!canAdjustWater()) return
        val base = habitWater()
        val target = (water + delta).coerceIn(WATER_MIN, WaterCup.MAX_LEVEL)
        waterAdjust = target - base
        water = target
        startWater = target
        _ui.update { it.copy(level = water, startWater = water, habitWater = base, canAdjust = true) }
    }

    private fun canAdjustWater(): Boolean = demo == null && liveMoving < 5 && (motion.speedKmh ?: 0.0) < 3 && !remoteWas

    /** 실제 주행: 달린 시간·멈춰 있는 시간 (주행이 끝났는지 보려고) */
    private var liveMoving = 0.0
    private var liveStopped = 0.0

    init {
        remote?.enabled = settings.phoneDemo
        applySettings(policy)
        _ui.update { it.copy(level = water, startWater = water, habitScore = habitScore(), habitWater = water, settings = settings) }
    }

    // ---- 설정 ----

    fun updateSettings(s: AppSettings) {
        val fillChanged = s.habitFill != settings.habitFill
        settings = s
        store.saveSettings(s.encode())
        applySettings(policy)
        remote?.enabled = s.phoneDemo
        if (!s.routePreview) clearRoute()
        if (fillChanged && canAdjustWater()) {
            waterAdjust = 0.0
            water = habitWater()
            startWater = water
        }
        _ui.update { it.copy(settings = s, level = water, startWater = startWater, habitWater = habitWater()) }
    }

    private fun applySettings(p: CarePolicy) {
        p.ventilate = settings.autoVent
        p.askWindow = settings.askWindow
        p.windowPercent = settings.windowPercent
        p.fewerAlerts = settings.fewerAlerts
    }

    /** 지난 주행 평가 지우기 (평소 습관도 처음부터) */
    fun clearTrips() {
        trips.clear()
        store.saveTrips(trips)
        if (canAdjustWater()) { waterAdjust = 0.0; water = habitWater(); startWater = water }
        _ui.update { it.copy(habitScore = null, habitWater = habitWater(), level = water, startWater = startWater) }
    }

    private var demo: Demo? = null
    private var sickPending = false
    private var windowAnswer: Boolean? = null

    private class Demo(val scenario: DriveScenario, val frames: Iterator<Frame>) {
        var frame: Frame? = null
        /** 아직 재생하지 않은 시나리오 시간 (초) */
        var pending = 0.0
    }

    /**
     * 처리 루프(20Hz)와 휴대폰 시연 확인을 돌린다. 화면이 보이는 동안만 부르고(앱: Activity STARTED),
     * 호출한 쪽이 취소하면 둘 다 멈춘다. 끝없이 도는 루프를 남기지 않는다.
     */
    suspend fun run() = coroutineScope {
        launch { loop() }
        launch { routeLoop() }
        remote?.let { launch { it.run() } }
    }

    /** 엔진 시각 (초). 차량·센서 쪽도 이 시각으로 값을 넣어야 [motion] 의 끊김 판정이 맞는다 */
    private val t0 = TimeSource.Monotonic.markNow()
    fun now(): Double = t0.elapsedNow().inWholeMicroseconds / 1e6

    private suspend fun CoroutineScope.loop() {
        var last = now()
        var lastPoll = 0.0
        while (isActive) {
            delay(TICK_MS)
            val now = now()
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
            // 시연: 시나리오 프레임을 DEMO_SPEED 배속으로 (브리핑을 보는 동안은 멈춤)
            if (_ui.value.briefing == null) d.pending += DEMO_SPEED * dt
            var spillStarted = false
            while (d.pending >= SCENARIO_DT && d.frames.hasNext()) {
                d.pending -= SCENARIO_DT
                val f = d.frames.next()
                val before = cup.spillCount
                cup.gradeX = f.grade
                if (f.resting) cup.rest(SCENARIO_DT) else cup.step(f.ax, f.ay, SCENARIO_DT, f.az)
                if (pour(SCENARIO_DT)) trace.event(f.t, TraceKind.SPILL)
                log.add(cup.tilt, SCENARIO_DT)
                if (cup.spillCount > before) spillStarted = true
                record(f.t, f.ax, f.ay, f.speedKmh, f.az)
                d.frame = f
            }
            if (seats.values.none { it.riding } && children.isNotEmpty()) {
                // 에뮬레이터 좌석이 비어 있으면 첫 아이가 자기 자리에 탄 것으로 본다
                seats = mapOf(children.first().seat to SeatState(true, true))
                assumed = true
            }
            val setup = applySetup(CupSetup.choose(seats, children))
            val frame = d.frame ?: return
            clock = frame.t
            input = CareInput(
                nowSec = frame.t, setup = setup, dose = cup.dose, spillStarted = spillStarted,
                speedKmh = frame.speedKmh, raining = frame.raining || car.raining.value,
                next = frame.next, restAreaKm = frame.restAreaKm, sickNow = takeSick(), windowAnswer = takeAnswer(),
            )
            if (!d.frames.hasNext()) {
                stopDemo()
                return
            }
        } else if (remote != null && remote.active && settings.phoneDemo) {
            // 휴대폰 시연: 받은 휴대폰 가속도를 차 흔들림으로
            if (!remoteWas) {
                resetTrip()
                remoteT = 0.0
                remoteWas = true
            }
            val step = remote.sampleDt
            var spillStarted = false
            for (r in remote.take(dt)) {
                remoteT += step
                val before = cup.spillCount
                cup.gradeX = r.gradeX; cup.gradeY = r.gradeY
                cup.step(r.ax, r.ay, step, r.az)
                if (pour(step)) trace.event(remoteT, TraceKind.SPILL)
                log.add(cup.tilt, step)
                if (cup.spillCount > before) spillStarted = true
                record(remoteT, r.ax, r.ay, REMOTE_KMH, r.az)
            }
            clock = remoteT
            if (seats.values.none { it.riding } && children.isNotEmpty()) {
                seats = mapOf(children.first().seat to SeatState(true, true))
                assumed = true
            }
            val setup = applySetup(CupSetup.choose(seats, children))
            input = CareInput(
                nowSec = remoteT, setup = setup, dose = cup.dose, spillStarted = spillStarted,
                speedKmh = REMOTE_KMH, raining = car.raining.value, sickNow = takeSick(), windowAnswer = takeAnswer(),
            )
        } else {
            if (remoteWas) {
                remoteWas = false
                finishTrip()
                resetTrip()
            }
            // 실제 주행: 2분 넘게 달린 뒤 3분 넘게 서 있으면 (도착·주차) 이번 주행을 평가하고 새로 시작
            val kmh = motion.speedKmh ?: 0.0
            if (kmh >= 5) { liveMoving += dt; liveStopped = 0.0 } else if (kmh < 1) liveStopped += dt
            if (liveMoving >= TRIP_MIN_SEC && liveStopped >= PARKED_SEC) {
                finishTrip()
                resetTrip()
            }
            motion.update(now)
            clock = now
            val before = cup.spillCount
            cup.gradeX = motion.gradeX; cup.gradeY = motion.gradeY
            cup.step(motion.ax, motion.ay, dt, motion.az)
            if (pour(dt)) trace.event(now, TraceKind.SPILL)
            log.add(cup.tilt, dt)
            record(now, motion.ax, motion.ay, motion.speedKmh ?: 0.0, motion.az)
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
                tiltX = cup.tiltX, tiltY = cup.tiltY, tiltZ = cup.tiltZ, gradeX = cup.gradeX, gradeY = cup.gradeY, level = water, freeboard = WaterCup.freeboardOf(water), canAdjust = canAdjustWater(),
                dose = cup.dose, spilling = waterSpilling, setup = input.setup, car = car.status.value,
                demoProgress = demo?.let { dm -> ((dm.frame?.t ?: 0.0) / dm.scenario.durationSec).toFloat() } ?: 0f,
                assumedSeat = assumed, raining = input.raining, remote = remoteWas,
            )
        }
    }

    /** 탄 사람이 바뀌면 물 높이를 바꾼다 (누적량은 그대로) */
    private fun applySetup(setup: CupSetup): CupSetup {
        cup.level = setup.level ?: ALONE_LEVEL
        cup.sensitivity = setup.primary?.sensitivity ?: Sensitivity()
        return setup
    }

    private fun takeSick(): Boolean = sickPending.also { sickPending = false }

    private fun takeAnswer(): Boolean? = windowAnswer.also { windowAnswer = null }

    private fun perform(actions: List<CareAction>) {
        for (a in actions) {
            when (a) {
                CareAction.Chime -> if (settings.chime) voice.chime()
                is CareAction.Say -> {
                    if (settings.voice) voice.say(a.text)
                    trace.event(clock, TraceKind.SAY, a.text)
                    _ui.update { it.copy(lastSaid = a.text) }
                }
                is CareAction.FreshAir -> {
                    car.freshAir(a.on)
                    trace.event(clock, TraceKind.CAR, if (a.on) "환기 시작: 외기 유입·에어컨·온도↓·풍량↑" else "공조 원래대로")
                }
                is CareAction.OpenWindows -> {
                    car.openWindows(a.windows, a.percent)
                    trace.event(clock, TraceKind.CAR, a.windows.joinToString { it.label } + " 창문 ${a.percent}%")
                }
                CareAction.CloseWindows -> {
                    car.closeWindows()
                    trace.event(clock, TraceKind.CAR, "창문 원래대로")
                }
                is CareAction.AskWindow -> _ui.update { it.copy(askWindows = a.windows) }
                CareAction.AskDone -> _ui.update { it.copy(askWindows = null) }
                CareAction.DustGuard -> {
                    car.dustGuard()
                    trace.event(clock, TraceKind.CAR, "먼지 차단: 내부 순환·공기청정")
                }
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
            // 멀미 패턴: 직전 흔들림에서 평소보다 유독 많았던 멀미 주기를 쌓는다
            val period = SickPeriod.find(trace, clock)
            val before = child.sensitivity
            val sens = period?.let { before.with(it) } ?: before
            updateChild(child.copy(level = level, calibrations = child.calibrations + 1, sensitivity = sens))
            cup.level = level
            cup.sensitivity = sens
            val pattern = when {
                sens.active && !before.active -> " ${topic(child.name)} ${periodText(sens.peakPeriod)} 흔들림에 특히 약해요."
                period != null && sens.count < Sensitivity.MIN_EVENTS -> " 멀미 패턴을 배우는 중이에요 (${sens.count}/${Sensitivity.MIN_EVENTS})."
                else -> ""
            }
            val text = "${child.name} 기준을 맞췄어요. 다음에는 멀미하기 전에 알려 드릴게요." + pattern
            if (settings.voice) voice.say(text)
            _ui.update { it.copy(lastSaid = text) }
        }
        trace.event(clock, TraceKind.SICK, child?.let { "${it.name} 기준 맞춤" } ?: "")
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
        policy = CarePolicy(askTimeoutSec = CarePolicy.ASK_TIMEOUT * DEMO_SPEED).also { applySettings(it) }
        val s = DriveScenario.weekendTrip(style)
        clearRoute()
        // 시연 경로 미리보기: 시나리오의 안내·방지턱·굽은 길과 길 높이로 (시연 시간은 배속)
        demoRoute = RoutePlanner.plan(s.routePoints(), s.totalM, s.durationSec, s.terrain())
        demo = Demo(s, s.frames(SCENARIO_DT).iterator())
        // 설정에서 브리핑을 켰으면 출발 전에 3D 브리핑 (보는 동안 시연은 멈춤)
        if (settings.briefing && settings.routePreview) {
            briefedThisRoute = true
            routeSpoken = true
            startBriefing()
        }
        _ui.update { it.copy(demo = style.name, demoProgress = 0f) }
    }

    fun stopDemo() {
        demo = null
        demoRoute = null
        clearRoute()
        finishTrip()
        resetTrip()
        _ui.update { it.copy(demo = null, demoProgress = 0f, assumedSeat = false) }
    }

    /** 주행이 끝났을 때: 기록으로 평가하고, 저장하고, 한 줄로 말한다 (너무 짧으면 하지 않음) */
    private fun finishTrip() {
        val r = TripReport.evaluate(trace, waterSpills)?.copy(startWater = startWater, endWater = water, habitWater = habitWater()) ?: return
        waterAdjust = 0.0
        val prev = trips.lastOrNull()?.score
        trips += TripSummary.of(r)
        while (trips.size > TripSummary.KEEP) trips.removeAt(0)
        store.saveTrips(trips)
        if (!settings.tripReport) return
        val text = r.spoken(prev)
        if (settings.voice) voice.say(text)
        _ui.update { it.copy(report = r, previousScore = prev, lastSaid = text) }
    }

    // ---- 가는 길 미리보기 ----

    private var routeKey: String? = null
    private var routeD0 = 0.0
    private val known = HashMap<String, RoutePoint>()
    private var terrain: Terrain? = null
    private var terrainTried = false
    private var routeSpoken = false
    private val announced = HashSet<Int>()
    private var demoRoute: RoutePreview? = null

    /** 1초마다: 길 안내(또는 시연 경로)로 위험 구간을 다시 보고, 출발 때 요약을 말하고, 구간 1km 앞에서 알린다 */
    private suspend fun routeLoop() {
        while (true) {
            delay(1_000)
            val d = demo
            if (d != null) {
                if (!settings.routePreview) continue
                val plan = demoRoute ?: continue
                route(plan, d.frame?.distM ?: 0.0)
                continue
            }
            if (!settings.routePreview) continue
            val a = navi.ahead.value
            val remain = a.remainM
            if (remain == null) {
                if (routeKey != null) clearRoute()
                continue
            }
            if (a.routeId != routeKey || remain > routeD0 + 2_000) {
                clearRoute()
                routeKey = a.routeId
                routeD0 = remain
            }
            val travelled = (routeD0 - remain).coerceAtLeast(0.0)
            var changed = false
            for (u in a.points) {
                val at = travelled + u.distanceM
                val key = "${u.kind}@${(at / 200).roundToInt()}"
                if (key !in known) { known[key] = RoutePoint(u.kind, at, u.dir); changed = true }
            }
            if (!terrainTried && settings.terrain && a.hereLat != null && a.hereLng != null && a.destLat != null && a.destLng != null) {
                terrainTried = true
                terrain = Elevation.along(a.hereLat, a.hereLng, a.destLat, a.destLng, remain)
                changed = true
            }
            val current = _ui.value.route
            val plan = if (changed || current == null) RoutePlanner.plan(known.values.toList(), routeD0, a.remainSec, terrain) else current
            route(plan, travelled)
        }
    }

    private fun route(plan: RoutePreview, atM: Double) {
        if (_ui.value.route !== plan || _ui.value.routeAtM != atM) _ui.update { it.copy(route = plan, routeAtM = atM) }
        if (!routeSpoken && settings.briefing && !briefedThisRoute && stoppedNow()) {
            // 출발 전(정차 중)이면 3D 브리핑이 요약을 대신한다
            briefedThisRoute = true
            routeSpoken = true
            startBriefing()
            return
        }
        // 아이가 탔을 때만 말한다
        if (!_ui.value.setup.active) return
        if (!routeSpoken) {
            routeSpoken = true
            say(plan.summary + (plan.restAdvice?.let { " $it." } ?: ""))
            return
        }
        for (seg in plan.segments) {
            val key = (seg.startM / 100).roundToInt()
            val ahead = seg.startM - atM
            if (key in announced || ahead > ANNOUNCE_M || seg.endM < atM) continue
            announced += key
            val len = RoutePreview.fmtKm(seg.lengthM)
            val why = seg.reasons.take(2).joinToString("·")
            say(
                if (seg.level >= 2) "곧 멀미 위험 구간이에요. 앞으로 $len, $why. 천천히 부드럽게 가 주세요"
                else "곧 $why 구간이에요. 앞으로 $len, 부드럽게 가 주세요",
            )
            break
        }
    }

    private fun say(text: String) {
        if (settings.voice) voice.say(text)
        trace.event(clock, TraceKind.SAY, text)
        _ui.update { it.copy(lastSaid = text) }
    }

    // ---- 3D 경로 브리핑 ----

    private var briefedThisRoute = false

    /** 시연은 시작 전, 실제 주행은 정차 중일 때만 (운전 중 화면을 덮지 않게) */
    private fun stoppedNow(): Boolean = demo?.let { (it.frame?.t ?: 0.0) < 1.0 } ?: ((motion.speedKmh ?: 0.0) < 5)

    /** 지금 경로(시연 또는 길 안내)로 3D 브리핑을 연다 */
    fun startBriefing() {
        val path = demo?.let { d -> demoRoute?.let { RoutePath.of(d.scenario, it) } }
            ?: _ui.value.route?.let { RoutePath.synthesize(known.values.toList(), it.totalM, terrain, it) }
            ?: return
        _ui.update { it.copy(briefing = path) }
    }

    fun endBriefing() = _ui.update { it.copy(briefing = null) }

    /** 브리핑 화면의 설명 (설정에서 음성을 끄면 글로만) */
    fun briefSay(text: String) {
        if (settings.voice) voice.say(text)
        _ui.update { it.copy(lastSaid = text) }
    }

    private fun clearRoute() {
        briefedThisRoute = false
        routeKey = null
        known.clear()
        terrain = null
        terrainTried = false
        routeSpoken = false
        announced.clear()
        _ui.update { it.copy(route = null, routeAtM = 0.0) }
    }

    /** 평가 창 닫기 */
    fun dismissReport() = _ui.update { it.copy(report = null) }

    /** 새 주행: 하던 차량 조치를 되돌리고 물컵·기록을 비운다 */
    private fun resetTrip() {
        liveMoving = 0.0
        liveStopped = 0.0
        if (policy.windowsOpen) car.closeWindows()
        if (policy.ventilating) car.freshAir(false)
        policy = CarePolicy().also { applySettings(it) }
        // 새 주행: 평소 운전 습관만큼 물을 채운다
        water = (habitWater() + waterAdjust).coerceIn(WATER_MIN, WaterCup.MAX_LEVEL)
        startWater = water
        waterSpilling = false
        waterSpills = 0
        _ui.update { it.copy(level = water, startWater = water, habitScore = habitScore(), habitWater = habitWater()) }
        windowAnswer = null
        _ui.update { it.copy(askWindows = null) }
        cup.reset()
        log.clear()
        trace.clear()
    }

    private fun periodText(sec: Double): String = "${(sec * 10).toInt() / 10.0}초"

    /** "민지" → "민지는", "하준" → "하준은" */
    private fun topic(name: String): String {
        val last = name.lastOrNull() ?: return name
        val hasFinal = last in '가'..'힣' && (last - '가') % 28 != 0
        return name + if (hasFinal) "은" else "는"
    }

    /** 끝낼 때: 하던 차량 조치를 되돌린다 */
    fun close() = resetTrip()

    private fun newId(): String = Random.nextLong().toULong().toString(16)

    companion object {
        const val TICK_MS = 50L
        const val SCENARIO_DT = 0.1
        /** 시연 배속 (13분 경로 → 약 2분 10초) */
        const val DEMO_SPEED = 6.0
        /** 휴대폰 시연에서 쓰는 속도 (창문 판단용) */
        const val REMOTE_KMH = 40.0
        /** 혼자 주행일 때 그리는 물 높이 */
        const val ALONE_LEVEL = 0.3
        /** 실제 주행 평가: 이만큼 달린 뒤 (초) */
        const val TRIP_MIN_SEC = 120.0
        /** 평소 운전 습관 = 최근 몇 번의 주행 */
        const val HABIT_TRIPS = 5
        const val WATER_MIN_FILL = 0.35
        const val WATER_MAX_FILL = 0.9
        /** 넘친 양(멀미 누적 단위) 1 에 줄어드는 물 */
        const val WATER_PER_DOSE = 0.12
        /** 아무리 넘쳐도 이만큼은 남긴다 (컵이 비어 보이지 않게) */
        const val WATER_LEFT_MIN = 0.12
        /** 덜어 낼 수 있는 한도 */
        const val WATER_MIN = 0.2
        /** 버튼 한 번에 */
        const val WATER_STEP = 0.05

        /** 위험 구간을 이만큼 앞에서 알린다 (m) */
        const val ANNOUNCE_M = 1_200.0
        /** 이만큼 서 있으면 주행이 끝난 것으로 (초): 신호 대기보다 길게 */
        const val PARKED_SEC = 180.0
    }
}
