package com.sssoft.chalrang.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.util.Log
import com.sssoft.chalrang.core.MotionEstimator
import com.sssoft.chalrang.core.Seat
import com.sssoft.chalrang.core.SeatState
import com.sssoft.chalrang.core.Window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 차량 조치 상태 (화면 표시용) */
data class CarStatus(
    val connected: Boolean = false,
    val freshAir: Boolean = false,
    /** 먼지 나는 길: 내부 순환 + 공기청정 */
    val dustGuard: Boolean = false,
    /** 열어 둔 창문과 열린 정도 (%) */
    val openWindows: Map<Window, Int> = emptyMap(),
    /** 마지막으로 한 조치 (예: "외기 유입 · 에어컨 · 22→20℃") */
    val lastAction: String? = null,
    val error: String? = null,
)

/**
 * Pleos Vehicle SDK 연결.
 * - 읽기: 좌석 탑승·안전벨트, 조향각, 브레이크, 주행 거리, 와이퍼(비)
 * - 제어: 공조 (외기 유입·에어컨·온도·풍량), 창문 위치. 바꾸기 전 값을 저장해 두었다가 되돌린다.
 *
 * 차량이 지원하지 않는 기능은 checkXxxCapabilities 로 확인해 건너뛴다.
 */
class CarLink(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)

    private val _seats = MutableStateFlow<Map<Seat, SeatState>>(emptyMap())
    val seats: StateFlow<Map<Seat, SeatState>> = _seats.asStateFlow()

    private val _raining = MutableStateFlow(false)
    val raining: StateFlow<Boolean> = _raining.asStateFlow()

    private val _status = MutableStateFlow(CarStatus())
    val status: StateFlow<CarStatus> = _status.asStateFlow()

    /** 흔들림 추정에 쓰는 신호를 받는 곳 (시각은 초) */
    var motion: MotionEstimator? = null
    var clock: () -> Double = { System.nanoTime() / 1e9 }

    // 지원 범위
    private var seatAreas: List<Seat> = emptyList()
    private var beltSeats: Set<Seat> = emptySet()
    private var windowBounds: Map<Window, Pair<Int, Int>> = emptyMap()
    private var hvacRecircAreas: List<SeatArea> = emptyList()
    private var hvacAcAreas: List<SeatArea> = emptyList()
    private var hvacCleanerAreas: List<SeatArea> = emptyList()
    private var hvacTempBounds: Map<SeatArea, Pair<Float, Float>> = emptyMap()
    private var hvacFanBounds: Map<SeatArea, Pair<Int, Int>> = emptyMap()

    // 되돌릴 값
    private val savedRecirc = mutableMapOf<SeatArea, Boolean>()
    private val savedAc = mutableMapOf<SeatArea, Boolean>()
    private val savedCleaner = mutableMapOf<SeatArea, Boolean>()
    private val savedTemp = mutableMapOf<SeatArea, Float>()
    private val savedFan = mutableMapOf<SeatArea, Int>()
    private val savedWindow = mutableMapOf<Window, Int>()

    private val occupancyListeners = mutableMapOf<Seat, SeatOccupancyListener>()
    private val beltListeners = mutableMapOf<Seat, SeatBeltBuckledStateListener>()

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(value: Float?) {
            if (value != null) motion?.onSteering(clock(), value.toDouble())
        }
        override fun onFailed(e: Exception) = warn("조향각 수신 실패", e)
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(value: Boolean?) {
            motion?.onBrake(value == true)
        }
        override fun onFailed(e: Exception) = warn("브레이크 수신 실패", e)
    }

    private val distanceListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            // AAOS 주행 거리 단위는 km
            if (value != null) motion?.onDistance(clock(), value * 1000.0)
        }
        override fun onFailed(e: Exception) = warn("주행 거리 수신 실패", e)
    }

    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(value: WiperState?) {
            _raining.value = value == WiperState.ON
        }
        override fun onFailed(e: Exception) = warn("와이퍼 수신 실패", e)
    }

    fun start() {
        try {
            vehicle.initialize()
        } catch (e: Exception) {
            warn("Vehicle SDK 초기화 실패", e)
            return
        }
        _status.update { it.copy(connected = true) }
        safe("좌석") { startSeats() }
        safe("조향각") {
            val wheel = vehicle.getSteeringWheel()
            wheel.checkSteeringWheelAngleCapabilities({ _, bounds ->
                motion?.steeringRatio = MotionEstimator.ratioForMaxAngle(bounds.maxValue?.toDouble())
                wheel.registerSteeringWheelAngle(steeringListener)
            }, { e -> warn("조향각 미지원", e) })
        }
        safe("브레이크") { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }
        safe("주행 거리") { vehicle.getOdometer().registerDistanceDriven(distanceListener) }
        safe("와이퍼") {
            vehicle.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), wiperListener)
        }
        safe("공조") { checkHvac() }
        safe("창문") {
            vehicle.getWindow().checkWindowPositionCapabilities({ _, map ->
                windowBounds = map.mapNotNull { (area, b) ->
                    val w = windowOf(area) ?: return@mapNotNull null
                    val lo = b.minValue ?: return@mapNotNull null
                    val hi = b.maxValue ?: return@mapNotNull null
                    w to (lo to hi)
                }.toMap()
            }, { e -> warn("창문 제어 미지원", e) })
        }
    }

    fun stop() {
        safe("해제") {
            val seat = vehicle.getSeat()
            occupancyListeners.forEach { (s, l) -> seat.unregisterSeatOccupancy(seatArea(s), l) }
            beltListeners.forEach { (s, l) -> seat.unregisterSeatBeltBuckledState(seatArea(s), l) }
            vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
            vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
            vehicle.getOdometer().unregisterDistanceDriven(distanceListener)
            vehicle.getWiper().unregisterWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), wiperListener)
            vehicle.release()
        }
    }

    // ---- 좌석 ----

    private fun startSeats() {
        val seat = vehicle.getSeat()
        seat.checkSeatOccupancyCapabilities({ _, areas ->
            seatAreas = areas.mapNotNull(::seatOf)
            for (s in seatAreas) {
                val l = object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(value: Boolean?) = setSeat(s, occupied = value == true)
                    override fun onFailed(e: Exception) = warn("${s.label} 탑승 수신 실패", e)
                }
                occupancyListeners[s] = l
                seat.getCurrentSeatOccupancy(seatArea(s), { v -> setSeat(s, occupied = v == true) }, { e -> warn("${s.label} 탑승 조회 실패", e) })
                seat.registerSeatOccupancy(seatArea(s), l)
            }
        }, { e -> warn("좌석 탑승 감지 미지원", e) })
        seat.checkSeatBeltBuckledCapabilities({ _, areas ->
            beltSeats = areas.mapNotNull(::seatOf).toSet()
            for (s in beltSeats) {
                val l = object : SeatBeltBuckledStateListener {
                    override fun onSeatBeltBuckledStateUpdated(value: Boolean?) = setSeat(s, belted = value)
                    override fun onFailed(e: Exception) = warn("${s.label} 벨트 수신 실패", e)
                }
                beltListeners[s] = l
                seat.getCurrentSeatBeltBuckledState(seatArea(s), { v -> setSeat(s, belted = v) }, { e -> warn("${s.label} 벨트 조회 실패", e) })
                seat.registerSeatBeltBuckledState(seatArea(s), l)
            }
        }, { e -> warn("안전벨트 감지 미지원", e) })
    }

    private fun setSeat(seat: Seat, occupied: Boolean? = null, belted: Boolean? = null) {
        _seats.update { m ->
            val old = m[seat] ?: SeatState(false)
            m + (seat to SeatState(occupied ?: old.occupied, belted ?: old.belted))
        }
    }

    // ---- 공조 ----

    private fun checkHvac() {
        val hvac = vehicle.getHVAC()
        hvac.checkHvacRecirculationCapabilities({ _, areas -> hvacRecircAreas = areas }, { e -> warn("내외기 전환 미지원", e) })
        hvac.checkHvacAcCapabilities({ _, areas -> hvacAcAreas = areas }, { e -> warn("에어컨 제어 미지원", e) })
        hvac.checkHvacAirCleanerCapabilities({ _, areas -> hvacCleanerAreas = areas }, { e -> warn("공기청정 미지원", e) })
        hvac.checkHvacTemperatureCapabilities({ _, map ->
            hvacTempBounds = map.mapNotNull { (a, b) ->
                val lo = b.minValue ?: return@mapNotNull null
                val hi = b.maxValue ?: return@mapNotNull null
                a to (lo to hi)
            }.toMap()
        }, { e -> warn("온도 제어 미지원", e) })
        hvac.checkHvacFanSpeedCapabilities({ _, map ->
            hvacFanBounds = map.mapNotNull { (a, b) ->
                val lo = b.minValue ?: return@mapNotNull null
                val hi = b.maxValue ?: return@mapNotNull null
                a to (lo to hi)
            }.toMap()
        }, { e -> warn("풍량 제어 미지원", e) })
    }

    /**
     * 환기: 외기 유입 + 에어컨 + 온도 2℃ 낮춤 + 풍량 한 단계 올림.
     * 끌 때는 켜기 전 값으로 되돌린다.
     */
    fun freshAir(on: Boolean) = safe("공조 제어") {
        if (on == _status.value.freshAir) return@safe
        val hvac = vehicle.getHVAC()
        // 온도는 조회가 끝나면 note() 로 덧붙는다
        val parts = if (on) {
            listOfNotNull(
                "외기 유입".takeIf { hvacRecircAreas.isNotEmpty() },
                "에어컨".takeIf { hvacAcAreas.isNotEmpty() },
                "풍량 +1".takeIf { hvacFanBounds.isNotEmpty() },
            ).ifEmpty { if (hvacTempBounds.isEmpty()) listOf("공조 제어를 지원하지 않는 차량") else emptyList() }
        } else {
            listOf("공조 원래대로")
        }
        _status.update { it.copy(freshAir = on, dustGuard = false, lastAction = parts.joinToString(" · ").ifEmpty { null }) }
        if (on) {
            for (a in hvacRecircAreas) {
                hvac.getHvacRecirculationState(a, { v -> if (v != null) savedRecirc[a] = v; hvac.setHvacRecirculation(a, false, { _, _ -> }, { e -> warn("외기 전환 실패", e) }) }, { e -> warn("내외기 조회 실패", e) })
            }
            for (a in hvacAcAreas) {
                hvac.getHvacAcState(a, { v -> if (v != null) savedAc[a] = v; hvac.setHvacAc(a, true, { _, _ -> }, { e -> warn("에어컨 실패", e) }) }, { e -> warn("에어컨 조회 실패", e) })
            }
            for ((a, b) in hvacTempBounds) {
                hvac.getHvacTemperature(a, TemperatureUnit.CELSIUS, { v ->
                    if (v != null) {
                        savedTemp[a] = v
                        val target = (v - COOL_DOWN_C).coerceIn(b.first, b.second)
                        hvac.setHvacTemperature(a, target, TemperatureUnit.CELSIUS, { _, _ -> }, { e -> warn("온도 설정 실패", e) })
                        note("${v.fmt()}→${target.fmt()}℃")
                    }
                }, { e -> warn("온도 조회 실패", e) })
            }
            for ((a, b) in hvacFanBounds) {
                hvac.getHvacFanSpeed(a, { v ->
                    if (v != null) {
                        savedFan[a] = v
                        hvac.setHvacFanSpeed(a, (v + 1).coerceIn(b.first, b.second), { _, _ -> }, { e -> warn("풍량 설정 실패", e) })
                    }
                }, { e -> warn("풍량 조회 실패", e) })
            }
        } else {
            savedRecirc.forEach { (a, v) -> hvac.setHvacRecirculation(a, v, { _, _ -> }, { e -> warn("내외기 복원 실패", e) }) }
            savedAc.forEach { (a, v) -> hvac.setHvacAc(a, v, { _, _ -> }, { e -> warn("에어컨 복원 실패", e) }) }
            savedCleaner.forEach { (a, v) -> hvac.setHvacAirCleaner(a, v, { _, _ -> }, { e -> warn("공기청정 복원 실패", e) }) }
            savedTemp.forEach { (a, v) -> hvac.setHvacTemperature(a, v, TemperatureUnit.CELSIUS, { _, _ -> }, { e -> warn("온도 복원 실패", e) }) }
            savedFan.forEach { (a, v) -> hvac.setHvacFanSpeed(a, v, { _, _ -> }, { e -> warn("풍량 복원 실패", e) }) }
            savedRecirc.clear(); savedAc.clear(); savedCleaner.clear(); savedTemp.clear(); savedFan.clear()
        }
    }

    /**
     * 먼지 나는 길: 외기 유입을 내부 순환으로 되돌리고 공기청정을 켠다 (에어컨·온도는 그대로).
     * 환기를 끌 때 [freshAir] (false) 가 처음 값으로 함께 되돌린다.
     */
    fun dustGuard() = safe("공조 제어") {
        val hvac = vehicle.getHVAC()
        for (a in hvacRecircAreas) {
            if (a !in savedRecirc) hvac.getHvacRecirculationState(a, { v -> if (v != null) savedRecirc.putIfAbsent(a, v) }, { e -> warn("내외기 조회 실패", e) })
            hvac.setHvacRecirculation(a, true, { _, _ -> }, { e -> warn("내부 순환 실패", e) })
        }
        for (a in hvacCleanerAreas) {
            hvac.getHvacAirCleanerState(a, { v ->
                if (v != null) savedCleaner.putIfAbsent(a, v)
                hvac.setHvacAirCleaner(a, true, { _, _ -> }, { e -> warn("공기청정 실패", e) })
            }, { e -> warn("공기청정 조회 실패", e) })
        }
        val parts = listOfNotNull(
            "내부 순환".takeIf { hvacRecircAreas.isNotEmpty() },
            "공기청정".takeIf { hvacCleanerAreas.isNotEmpty() },
        )
        _status.update { it.copy(dustGuard = true, lastAction = parts.joinToString(" · ").ifEmpty { "공조 제어를 지원하지 않는 차량" }) }
    }

    // ---- 창문 ----

    /** [windows] 를 [percent]% 연다. 이미 더 열려 있으면 그대로 */
    fun openWindows(windows: List<Window>, percent: Int) = safe("창문 제어") {
        val win = vehicle.getWindow()
        val opened = mutableMapOf<Window, Int>()
        for (w in windows) {
            val (lo, hi) = windowBounds[w] ?: continue
            val target = lo + (hi - lo) * percent / 100
            win.getCurrentWindowPosition(windowArea(w), { cur ->
                val now = cur ?: lo
                savedWindow.putIfAbsent(w, now)
                if (now < target) win.setWindowPosition(windowArea(w), target, { _, _ -> }, { e -> warn("창문 열기 실패", e) })
            }, { e -> warn("창문 위치 조회 실패", e) })
            opened[w] = percent
        }
        if (opened.isNotEmpty()) {
            _status.update { it.copy(openWindows = it.openWindows + opened, lastAction = opened.keys.joinToString { w -> windowLabel(w) } + " 창문 $percent%") }
        } else if (windows.isNotEmpty()) {
            _status.update { it.copy(lastAction = "창문 제어를 지원하지 않는 차량") }
        }
    }

    /** 연 창문을 열기 전 위치로 */
    fun closeWindows() = safe("창문 제어") {
        val win = vehicle.getWindow()
        savedWindow.forEach { (w, pos) -> win.setWindowPosition(windowArea(w), pos, { _, _ -> }, { e -> warn("창문 닫기 실패", e) }) }
        savedWindow.clear()
        _status.update { it.copy(openWindows = emptyMap(), lastAction = "창문 원래대로") }
    }

    // ---- 도우미 ----

    private fun note(s: String) {
        _status.update { st -> st.copy(lastAction = listOfNotNull(st.lastAction, s).joinToString(" · ")) }
    }

    private inline fun safe(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            warn("$what 실패", e)
        }
    }

    private fun warn(msg: String, e: Exception) {
        Log.w(TAG, msg, e)
        _status.update { it.copy(error = "$msg: ${e.message ?: e.javaClass.simpleName}") }
    }

    companion object {
        private const val TAG = "CarLink"
        const val COOL_DOWN_C = 2f

        fun seatArea(s: Seat) = SeatArea(
            when (s) {
                Seat.FRONT_RIGHT -> SeatValue.ROW_1_RIGHT
                Seat.REAR_LEFT -> SeatValue.ROW_2_LEFT
                Seat.REAR_CENTER -> SeatValue.ROW_2_CENTER
                Seat.REAR_RIGHT -> SeatValue.ROW_2_RIGHT
            },
        )

        fun seatOf(a: SeatArea): Seat? = when (a.values.singleOrNull()) {
            SeatValue.ROW_1_RIGHT -> Seat.FRONT_RIGHT
            SeatValue.ROW_2_LEFT -> Seat.REAR_LEFT
            SeatValue.ROW_2_CENTER -> Seat.REAR_CENTER
            SeatValue.ROW_2_RIGHT -> Seat.REAR_RIGHT
            else -> null
        }

        fun windowArea(w: Window) = WindowArea(
            when (w) {
                Window.FRONT_RIGHT -> WindowValue.ROW_1_RIGHT
                Window.REAR_LEFT -> WindowValue.ROW_2_LEFT
                Window.REAR_RIGHT -> WindowValue.ROW_2_RIGHT
            },
        )

        fun windowOf(a: WindowArea): Window? = when (a.values.singleOrNull()) {
            WindowValue.ROW_1_RIGHT -> Window.FRONT_RIGHT
            WindowValue.ROW_2_LEFT -> Window.REAR_LEFT
            WindowValue.ROW_2_RIGHT -> Window.REAR_RIGHT
            else -> null
        }

        fun windowLabel(w: Window) = when (w) {
            Window.FRONT_RIGHT -> "조수석"
            Window.REAR_LEFT -> "뒷좌석 왼쪽"
            Window.REAR_RIGHT -> "뒷좌석 오른쪽"
        }

        private fun Float.fmt() = if (this % 1f == 0f) toInt().toString() else "%.1f".format(this)
    }
}
