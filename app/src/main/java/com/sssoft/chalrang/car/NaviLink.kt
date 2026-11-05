package com.sssoft.chalrang.car

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.sssoft.chalrang.core.ManeuverKind
import com.sssoft.chalrang.core.MotionEstimator
import com.sssoft.chalrang.core.Upcoming
import com.sssoft.chalrang.engine.NaviPort
import com.sssoft.chalrang.engine.RoadAhead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pleos NaviHelper 연결. 회전 안내(TBT)·주의 지점으로 흔들리는 곳을 미리 알고,
 * 현재 위치·진행 방향은 흔들림 추정(속도·좌우 가속도)에 보탠다.
 * 거리 단위는 m 로 본다 (에뮬레이터에서 확인 필요).
 */
class NaviLink(context: Context) : NaviPort {

    private val navi = NaviHelper(context.applicationContext)
    private val _ahead = MutableStateFlow(RoadAhead())
    override val ahead: StateFlow<RoadAhead> = _ahead.asStateFlow()

    var motion: MotionEstimator? = null
    var clock: () -> Double = { System.nanoTime() / 1e9 }
    var connected = false; private set

    private val locations = context.applicationContext.getSystemService(LocationManager::class.java)
    private val app = context.applicationContext

    private var turns: List<TBTInfo> = emptyList()
    private var cautions: List<CautionPoint> = emptyList()
    private var dest: DestinationInfo? = null
    private var here: CurrentLocationInfo? = null
    /** 목적지 좌표가 비어 오면(에뮬레이터 내비는 0,0) 대신 쓸 마지막 경유지·최근 목적지 */
    private var waypoint: WaypointInfo? = null
    private var recent: RecentDestinationInfo? = null
    /** 길 안내 시작 때 받은 전체 거리·시간 (목적지 정보에 남은 거리가 없을 때) */
    private var start: RouteStartInfo? = null
    /** 내비 상태: 길 안내 중이면 ROUTE_DRIVE (onRouteEnded 가 길 안내 시작 직후에도 오므로 이것으로 판단) */
    private var status: NavigationStatus? = null
    /** 길 안내를 새로 시작할 때마다 늘린다 (SDK 경로 id 가 없을 때 대신) */
    private var routeSerial = 0

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
            val routeChanged = info.routeId != here?.routeId
            here = info
            if (routeChanged) publish()
        }

        override fun onDestinationInfo(info: DestinationInfo) {
            // 1초마다 다시 오므로 목적지가 바뀔 때만 남긴다
            if (info.latitude != dest?.latitude || info.longitude != dest?.longitude) {
                Log.d(TAG, "목적지 ${info.name} ${info.latitude},${info.longitude} 남은 ${info.remainDistance}m")
            }
            dest = info
            publish()
        }

        override fun onRouteStarted(info: RouteStartInfo) {
            // 목적지 정보는 길 안내 시작 바로 전에 오므로 지우지 않는다
            Log.d(TAG, "길 안내 시작 ${info.distance}m ${info.duration}s")
            routeSerial++
            start = info
            turns = emptyList(); cautions = emptyList()
            publish()
            runCatching { navi.getDestinationInfo() }
            runCatching { navi.getRecentDestinationInfo() }
        }

        override fun onWayPointInfo(info: List<WaypointInfo>) {
            Log.d(TAG, "경유지 ${info.size}: ${info.joinToString { "${it.name} ${it.latitude},${it.longitude}" }}")
            waypoint = info.lastOrNull { it.latitude != 0.0 || it.longitude != 0.0 }
            publish()
        }

        override fun onRecentDestinationInfo(info: List<RecentDestinationInfo>) {
            Log.d(TAG, "최근 목적지 ${info.size}: ${info.firstOrNull()?.let { "${it.name} ${it.latitude},${it.longitude}" }}")
            recent = info.firstOrNull { it.latitude != 0.0 || it.longitude != 0.0 }
            publish()
        }

        // 에뮬레이터 내비는 길 안내를 시작하자마자 onRouteEnded 도 보낸다: 끝났는지는 onNavigationStatus 로
        override fun onRouteEnded() = Unit

        override fun onNavigationStatus(status: NavigationStatus) {
            Log.d(TAG, "내비 상태 $status")
            this@NaviLink.status = status
            if (status != NavigationStatus.ROUTE_DRIVE) clear() else publish()
        }

        override fun onRouteCancelled() = clear()

        override fun onDestinationArrived(info: DestinationArrivedInfo) = clear()
    }

    private fun clear() {
        turns = emptyList(); cautions = emptyList(); dest = null; start = null; waypoint = null; recent = null
        publish()
    }

    fun start() {
        try {
            // 성공 콜백이 다시 불려도(서비스 재연결) 리스너는 한 번만: 겹치면 같은 이벤트를 여러 번 받는다
            navi.initialize({
                if (!connected) {
                    connected = true
                    navi.addListener(listener)
                }
            }, { e -> Log.w(TAG, "NaviHelper 초기화 실패", e) })
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper 초기화 실패", e)
        }
    }

    /** 1초마다 불러 회전 안내와 위치를 새로 받는다 */
    override fun poll() {
        if (!connected) return
        runCatching { navi.getTBTInfo() }
        runCatching { navi.getCurrentLocationInfo() }
        runCatching { navi.getDestinationInfo() }
    }

    fun stop() {
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
        connected = false
    }

    private fun publish() {
        val list = turns.mapNotNull { t -> kindOf(t.type)?.let { Upcoming(it, t.distance, dirOf(t.type), nameOf(t.description)) } } +
            cautions.mapNotNull { c -> kindOf(c.type)?.let { Upcoming(it, c.remainDistance.toDouble()) } }
        val ahead = list.filter { it.distanceM >= 0 }.sortedBy { it.distanceM }
        // 길 안내 중일 때만 (취소한 뒤에도 목적지 정보가 따로 온다). 상태를 아직 못 받았으면 목적지만으로
        val d = dest?.takeIf { status == null || status == NavigationStatus.ROUTE_DRIVE }
        val h = here
        val s = start
        // 차가 서 있으면 내비가 현재 위치를 보내지 않는다 (에뮬레이터): 기기 위치로 출발점을 대신한다
        val fix = if (h == null && d != null) deviceFix() else null
        // 목적지 좌표: 목적지 정보 → 마지막 경유지 → 최근 목적지 (0,0 은 비어 있는 것)
        val to = d?.let { dd ->
            when {
                dd.latitude != 0.0 || dd.longitude != 0.0 -> dd.latitude to dd.longitude
                waypoint != null -> waypoint!!.latitude to waypoint!!.longitude
                recent != null -> recent!!.latitude to recent!!.longitude
                else -> null
            }
        }
        _ahead.value = RoadAhead(
            next = ahead.firstOrNull { it.kind != ManeuverKind.REST_AREA },
            restAreaKm = ahead.firstOrNull { it.kind == ManeuverKind.REST_AREA }?.distanceM?.div(1000),
            points = ahead,
            remainM = d?.remainDistance?.toDouble()?.takeIf { it > 0 } ?: s?.distance?.toDouble()?.takeIf { d != null && it > 0 },
            remainSec = d?.remainTime?.toDouble()?.takeIf { it > 0 } ?: s?.duration?.toDouble()?.takeIf { d != null && it > 0 },
            routeId = (h?.routeId ?: "") + "#" + routeSerial,
            hereLat = h?.latitude ?: fix?.latitude, hereLng = h?.longitude ?: fix?.longitude,
            destLat = to?.first, destLng = to?.second,
            destName = nameOf(d?.name),
        )
    }

    /** 기기의 마지막 위치 (위치 권한이 있을 때) */
    private fun deviceFix(): Location? {
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            locations?.getProviders(true)?.mapNotNull { locations.getLastKnownLocation(it) }?.maxByOrNull { it.time }
        }.getOrNull()
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

        /** 내비 안내 문구·지명 (빈 것은 없는 것으로) */
        fun nameOf(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

        /** 회전 방향: -1 왼쪽, +1 오른쪽 (유턴은 왼쪽) */
        fun dirOf(t: TurnType): Int {
            val n = t.name
            return when {
                n == "TURN_UTURN" -> -1
                n.endsWith("_L") || n.endsWith("LEFT") -> -1
                n.endsWith("_R") || n.endsWith("RIGHT") -> 1
                else -> 0
            }
        }

        fun kindOf(t: CautionPointType): ManeuverKind? = when (t) {
            CautionPointType.SPEED_BUMP -> ManeuverKind.SPEED_BUMP
            CautionPointType.DOWNHILL_CAUTION_AREA -> ManeuverKind.DOWNHILL
            CautionPointType.NARROW_ROAD -> ManeuverKind.NARROW
            CautionPointType.ROAD_DAMAGE_ALERT -> ManeuverKind.ROAD_DAMAGE
            CautionPointType.FALLING_ROCK_AREA -> ManeuverKind.FALLING_ROCK
            else -> null
        }
    }
}
