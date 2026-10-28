package com.sssoft.hk.trip

import com.sssoft.hk.model.ArrivalResult
import com.sssoft.hk.model.ChargePlanResult
import com.sssoft.hk.model.Charger
import com.sssoft.hk.model.EvMonteCarlo
import com.sssoft.hk.model.EvSpec
import com.sssoft.hk.navi.Destination
import com.sssoft.hk.navi.LatLng
import com.sssoft.hk.navi.Trip
import com.sssoft.hk.server.ChargerApi
import com.sssoft.hk.server.StationView
import kotlin.math.pow

data class TripConditions(
    val socPercent: Int,
    val capacityKwh: Double?,
    val tempC: Double,
    val ascentPerKm: Double = 3.0,
)

data class ChargingRecommendation(
    val plan: ChargePlanResult,
    /** Plan 일 때 선택된 충전소. */
    val station: StationView?,
    val candidates: List<StationView>,
)

/**
 * 내비 경로 + 차량 배터리 + 서버 충전소 실시간 상태를 몬테카를로 모델에 넣는다.
 * UI와 Gleo 음성 호출이 같이 쓴다.
 */
class TripPlanner(private val api: ChargerApi = ChargerApi()) {

    private fun spec(c: TripConditions) = c.capacityKwh?.let { EvSpec(batteryKwh = it) } ?: EvSpec()

    fun arrival(c: TripConditions, remainKm: Double): ArrivalResult =
        EvMonteCarlo().arrivalProbability(spec(c), c.socPercent / 100.0, remainKm, c.tempC, c.ascentPerKm * remainKm)

    suspend fun recommend(
        c: TripConditions, origin: LatLng?, dest: Destination, trip: Trip?,
    ): ChargingRecommendation {
        val totalKm = trip?.distanceKm ?: dest.remainKm
        val totalMin = trip?.etaMin ?: dest.remainMin
        val speedKmh = if (totalMin > 0) (totalKm / (totalMin / 60.0)).coerceIn(30.0, 120.0) else 90.0

        // 1) 내비가 경로상에 둔 충전소를 실제 충전소와 매칭
        val routePoints = trip?.chargers.orEmpty().map { it.location to it.ratioOnRoute }
        var matched: List<Pair<StationView, Double>> = emptyList()
        if (routePoints.isNotEmpty()) {
            val views = api.match(routePoints.map { (p, r) -> p to r * totalMin }, radiusKm = 0.3)
            matched = views.zip(routePoints).mapNotNull { (v, rp) -> v?.let { it to rp.second } }
        }
        // 2) 없으면 출발-도착 직선상 30/50/70% 지점 주변에서 찾기 (경로 형상이 없을 때의 근사)
        if (matched.isEmpty() && origin != null) {
            val ratios = listOf(0.3, 0.5, 0.7)
            val pts = ratios.map { r ->
                LatLng(origin.lat + (dest.location.lat - origin.lat) * r, origin.lng + (dest.location.lng - origin.lng) * r)
            }
            val views = api.match(pts.zip(ratios).map { (p, r) -> p to r * totalMin }, radiusKm = 10.0)
            matched = views.zip(ratios).mapNotNull { (v, r) -> v?.let { it to r } }
        }

        val usable = matched.filter { it.first.inService > 0 }.distinctBy { it.first.id }
        val chargers = usable.map { (v, r) ->
            Charger(
                name = v.id,
                atKm = r * totalKm,
                powerKw = v.maxKw.coerceIn(30.0, 250.0),
                waitMeanMin = expectedWaitMin(v, r * totalMin),
            )
        }
        val plan = EvMonteCarlo().planCharging(
            spec(c), c.socPercent / 100.0, totalKm, c.tempC, c.ascentPerKm, speedKmh, chargers,
        )
        val station = (plan as? ChargePlanResult.Plan)?.let { p -> usable.first { it.first.id == p.charger }.first }
        return ChargingRecommendation(plan, station, usable.map { it.first })
    }

    companion object {
        /**
         * 도착 시점 예상 대기(분, 로그정규 평균).
         * 곧 도착하면 현재 상태를, 멀면 같은 요일·시간대 평균 사용률을 쓴다. 사용률→대기 변환은 경험식.
         */
        fun expectedWaitMin(v: StationView, etaMin: Double): Double {
            val now = v.waitMin
            val fromHistory = v.forecastBusy?.let { 1.0 + 25.0 * it.pow(2) }
            return when {
                etaMin <= 20 -> now ?: 15.0
                fromHistory != null -> fromHistory
                else -> (now ?: 8.0).coerceAtLeast(2.0)
            }.coerceAtLeast(0.5)
        }
    }
}
