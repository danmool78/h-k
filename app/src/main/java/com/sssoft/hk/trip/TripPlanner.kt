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

/** 충전소 하나에 들렀을 때의 최선안. */
data class StopOption(
    val station: StationView,
    val atKm: Double,
    val etaMin: Double,
    /** 도착 시점 예상 대기(분) */
    val waitMin: Double,
    val plan: ChargePlanResult.Plan,
    /** 내비가 자동으로 추천한 충전소 */
    val naviPick: Boolean,
)

data class ChargingRecommendation(
    val plan: ChargePlanResult,
    /** 총 소요시간 순으로 정렬된 후보 (목표 확률 만족하는 것만) */
    val options: List<StopOption>,
    /** 비교 기준: 내비 추천, 없으면 대기 무시하고 출력이 가장 큰 곳 */
    val baseline: StopOption?,
    val checkedStations: Int,
) {
    val best: StopOption? get() = options.firstOrNull()
    val station: StationView? get() = best?.station

    /** 기준 대비 절약 시간(분, 중앙값 기준). */
    val savedMin: Double? get() {
        val b = best ?: return null
        val base = baseline ?: return null
        return (base.plan.minutesP50 - b.plan.minutesP50).takeIf { base.station.id != b.station.id }
    }
}

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
        data class Cand(val view: StationView, val ratio: Double, val naviPick: Boolean)
        var cands: List<Cand> = emptyList()
        val routeChargers = trip?.chargers.orEmpty()
        if (routeChargers.isNotEmpty()) {
            val views = api.match(routeChargers.map { it.location to it.ratioOnRoute * totalMin }, radiusKm = 0.3)
            cands = views.zip(routeChargers).mapNotNull { (v, rc) -> v?.let { Cand(it, rc.ratioOnRoute, rc.naviPick) } }
        }
        // 2) 없으면 출발-도착 직선상 지점 주변에서 찾기 (경로 형상이 없을 때의 근사)
        if (cands.isEmpty() && origin != null) {
            val ratios = (2..18).map { it * 0.05 }
            val pts = ratios.map { r ->
                LatLng(origin.lat + (dest.location.lat - origin.lat) * r, origin.lng + (dest.location.lng - origin.lng) * r)
            }
            val views = api.match(pts.zip(ratios).map { (p, r) -> p to r * totalMin }, radiusKm = 10.0)
            cands = views.zip(ratios).mapNotNull { (v, r) -> v?.let { Cand(it, r, false) } }
        }
        cands = cands.filter { it.view.inService > 0 }.distinctBy { it.view.id }

        val waits = cands.associate { it.view.id to expectedWaitMin(it.view, it.ratio * totalMin) }
        val chargers = cands.map { cd ->
            Charger(cd.view.id, cd.ratio * totalKm, cd.view.maxKw.coerceIn(30.0, 250.0), waits.getValue(cd.view.id))
        }
        val mc = EvMonteCarlo()
        val v = spec(c)
        val soc0 = c.socPercent / 100.0
        val p0 = mc.arrivalProbability(v, soc0, totalKm, c.tempC, c.ascentPerKm * totalKm).probability
        if (p0 >= 0.95) {
            return ChargingRecommendation(ChargePlanResult.NoChargeNeeded(p0), emptyList(), null, cands.size)
        }

        val byId = cands.associateBy { it.view.id }
        val options = mc.bestStopPerCharger(v, soc0, totalKm, c.tempC, c.ascentPerKm, speedKmh, chargers)
            .map { p ->
                val cd = byId.getValue(p.charger)
                StopOption(cd.view, cd.ratio * totalKm, cd.ratio * totalMin, waits.getValue(p.charger), p, cd.naviPick)
            }
            .sortedBy { EvMonteCarlo.score(it.plan) }
        val baseline = options.firstOrNull { it.naviPick } ?: options.maxByOrNull { it.station.maxKw }
        val plan = options.firstOrNull()?.plan ?: ChargePlanResult.Infeasible(p0)
        return ChargingRecommendation(plan, options, baseline, cands.size)
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
