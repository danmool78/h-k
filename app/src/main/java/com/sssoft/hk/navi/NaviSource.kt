package com.sssoft.hk.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RoutePointPoiType
import ai.pleos.playground.navi.constants.RoutePointSettingType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

data class LatLng(val lat: Double, val lng: Double)

data class Destination(
    val name: String,
    val location: LatLng,
    val remainKm: Double,
    val remainMin: Double,
)

/** 내비가 경로상에 둔 충전소. [ratioOnRoute] 는 출발 0 ~ 도착 1. [naviPick] = 내비가 자동으로 넣은 충전 경유지. */
data class RouteCharger(val name: String, val location: LatLng, val ratioOnRoute: Double, val naviPick: Boolean)

data class Trip(
    val distanceKm: Double,
    val etaMin: Double,
    val estimatedSocAtDest: Int,
    val chargers: List<RouteCharger>,
)

/**
 * Pleos NaviHelper 래퍼. 요청 → 리스너 이벤트 응답 구조를 suspend 함수로 감싼다.
 * 단위(m/초 등)는 문서에 명시가 없어 에뮬레이터에서 확인 필요 — 아래 변환은 m, 초 가정.
 */
class NaviSource(context: Context) {

    private val navi = NaviHelper(context.applicationContext)
    private var pending: Pending? = null

    private sealed interface Pending {
        class Dest(val d: CompletableDeferred<DestinationInfo?>) : Pending
        class Loc(val d: CompletableDeferred<CurrentLocationInfo?>) : Pending
        class TripP(val d: CompletableDeferred<TripInfo?>) : Pending
    }

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) {
            (pending as? Pending.Dest)?.d?.complete(info)
        }

        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            (pending as? Pending.Loc)?.d?.complete(info)
        }

        override fun onTripInfo(info: TripInfo) {
            (pending as? Pending.TripP)?.d?.complete(info)
        }

        override fun onError(code: NaviErrorCode) {
            when (val p = pending) {
                is Pending.Dest -> p.d.complete(null)
                is Pending.Loc -> p.d.complete(null)
                is Pending.TripP -> p.d.complete(null)
                null -> Unit
            }
        }
    }

    suspend fun connect(timeoutMs: Long = 2_000): Boolean {
        val ready = CompletableDeferred<Boolean>()
        navi.initialize({ ready.complete(true) }, { ready.complete(false) })
        val ok = withTimeoutOrNull(timeoutMs) { ready.await() } ?: false
        if (ok) navi.addListener(listener)
        return ok
    }

    fun release() {
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
    }

    private suspend fun <T> await(p: Pending, d: CompletableDeferred<T?>, timeoutMs: Long, request: () -> Unit): T? {
        pending = p
        request()
        return withTimeoutOrNull(timeoutMs) { d.await() }.also { pending = null }
    }

    /** 경로 안내 중인 목적지. 안내 중이 아니면 null. */
    suspend fun destination(timeoutMs: Long = 1_500): Destination? {
        val d = CompletableDeferred<DestinationInfo?>()
        val info = await(Pending.Dest(d), d, timeoutMs) { navi.getDestinationInfo() } ?: return null
        if (info.remainDistance <= 0) return null
        return Destination(
            name = info.name,
            location = LatLng(info.latitude, info.longitude),
            remainKm = info.remainDistance / 1000.0,
            remainMin = info.remainTime / 60.0,
        )
    }

    suspend fun currentLocation(timeoutMs: Long = 1_500): LatLng? {
        val d = CompletableDeferred<CurrentLocationInfo?>()
        val info = await(Pending.Loc(d), d, timeoutMs) { navi.getCurrentLocationInfo() } ?: return null
        return LatLng(info.latitude, info.longitude)
    }

    /** 목적지까지 경로를 탐색해 경로상 충전소와 도착 예상 SOC를 받는다. */
    suspend fun trip(dest: Destination, timeoutMs: Long = 2_500): Trip? {
        val d = CompletableDeferred<TripInfo?>()
        val req = TripRequest(dest.location.lat, dest.location.lng, "", dest.name, "")
        val info = await(Pending.TripP(d), d, timeoutMs) { navi.requestTrip(req) } ?: return null
        val chargers = info.summary.routePoints
            .filter { it.poiType == RoutePointPoiType.EV_CHARGING }
            .map {
                val r = it.ratioOnRoute
                RouteCharger(
                    it.poiName, LatLng(it.latitude, it.longitude), if (r > 1.0) r / 100.0 else r,
                    naviPick = it.settingType == RoutePointSettingType.AUTO,
                )
            }
        return Trip(info.distance / 1000.0, info.eta / 60.0, info.estimatedSocAtDest, chargers)
    }

    fun addChargingWaypoint(name: String, location: LatLng) {
        navi.addWaypoint(
            RequestWaypointInfo(location.lat, location.lng, name, WaypointIndex.FIRST, "", "", "", ""),
        )
    }
}
