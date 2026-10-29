package com.sssoft.chalrang.car

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.sssoft.chalrang.core.ManeuverKind
import com.sssoft.chalrang.core.MotionEstimator
import com.sssoft.chalrang.core.Upcoming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 내비에서 받은 앞으로 올 길 */
data class RoadAhead(
    /** 가장 가까운 안내 (회전·진출로·방지턱 등) */
    val next: Upcoming? = null,
    /** 경로상 다음 휴게소까지 (km) */
    val restAreaKm: Double? = null,
)

/**
 * Pleos NaviHelper 연결. 회전 안내(TBT)·주의 지점으로 흔들리는 곳을 미리 알고,
 * 현재 위치·진행 방향은 흔들림 추정(속도·좌우 가속도)에 보탠다.
 * 거리 단위는 m 로 본다 (에뮬레이터에서 확인 필요).
 */
class NaviLink(context: Context) {

    private val navi = NaviHelper(context.applicationContext)
    private val _ahead = MutableStateFlow(RoadAhead())
    val ahead: StateFlow<RoadAhead> = _ahead.asStateFlow()

    var motion: MotionEstimator? = null
    var clock: () -> Double = { System.nanoTime() / 1e9 }
    var connected = false; private set

    private var turns: List<TBTInfo> = emptyList()
    private var cautions: List<CautionPoint> = emptyList()

    private val listener = object : NaviHelperEventListener {
        override fun onTBTInfo(info: List<TBTInfo>) {
            turns = info
            publish()
        }

        override fun onCautionPointInfo(info: List<CautionPoint>) {
            cautions = info
            publish()
        }

        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            motion?.onPosition(clock(), info.latitude, info.longitude, info.heading)
        }

        override fun onRouteEnded() {
            turns = emptyList(); cautions = emptyList(); publish()
        }
    }

    fun start() {
        try {
            navi.initialize({
                connected = true
                navi.addListener(listener)
            }, { e -> Log.w(TAG, "NaviHelper 초기화 실패", e) })
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper 초기화 실패", e)
        }
    }

    /** 1초마다 불러 회전 안내와 위치를 새로 받는다 */
    fun poll() {
        if (!connected) return
        runCatching { navi.getTBTInfo() }
        runCatching { navi.getCurrentLocationInfo() }
    }

    fun stop() {
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
        connected = false
    }

    private fun publish() {
        val list = turns.mapNotNull { t -> kindOf(t.type)?.let { Upcoming(it, t.distance) } } +
            cautions.mapNotNull { c -> kindOf(c.type)?.let { Upcoming(it, c.remainDistance.toDouble()) } }
        val ahead = list.filter { it.distanceM >= 0 }.sortedBy { it.distanceM }
        _ahead.value = RoadAhead(
            next = ahead.firstOrNull { it.kind != ManeuverKind.REST_AREA },
            restAreaKm = ahead.firstOrNull { it.kind == ManeuverKind.REST_AREA }?.distanceM?.div(1000),
        )
    }

    companion object {
        private const val TAG = "NaviLink"

        fun kindOf(t: TurnType): ManeuverKind? = when (t) {
            TurnType.TURN_LEFT, TurnType.TURN_RIGHT -> ManeuverKind.TURN
            TurnType.TURN_SHARP_L, TurnType.TURN_SHARP_R -> ManeuverKind.SHARP_TURN
            TurnType.TURN_UTURN -> ManeuverKind.UTURN
            TurnType.TURN_EXIT_IC_L, TurnType.TURN_EXIT_IC_R, TurnType.TURN_EXIT_IC_C -> ManeuverKind.IC_EXIT
            TurnType.TURN_ENTER_IC_L, TurnType.TURN_ENTER_IC_R, TurnType.TURN_ENTER_IC_C -> ManeuverKind.IC_ENTER
            TurnType.TURN_ENTER_ROUNDABOUT_02, TurnType.TURN_ENTER_ROUNDABOUT_03, TurnType.TURN_ENTER_ROUNDABOUT_04,
            TurnType.TURN_ENTER_ROUNDABOUT_06, TurnType.TURN_ENTER_ROUNDABOUT_08, TurnType.TURN_ENTER_ROUNDABOUT_09,
            TurnType.TURN_ENTER_ROUNDABOUT_10, TurnType.TURN_ENTER_ROUNDABOUT_12 -> ManeuverKind.ROUNDABOUT
            TurnType.TURN_RESTAREA, TurnType.TURN_ENTER_SERVICEAREA -> ManeuverKind.REST_AREA
            else -> null
        }

        fun kindOf(t: CautionPointType): ManeuverKind? = when (t) {
            CautionPointType.SPEED_BUMP -> ManeuverKind.SPEED_BUMP
            else -> null
        }
    }
}
