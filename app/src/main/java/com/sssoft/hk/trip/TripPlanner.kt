package com.sssoft.hk.trip

import com.sssoft.hk.model.ArrivalResult
import com.sssoft.hk.model.ChargePlanResult
import com.sssoft.hk.model.Charger
import com.sssoft.hk.model.DriveMode
import com.sssoft.hk.model.EvMonteCarlo
import com.sssoft.hk.model.TripContext
import com.sssoft.hk.model.TripEnv
import com.sssoft.hk.model.VehicleProfile
import com.sssoft.hk.model.Weather
import com.sssoft.hk.navi.Destination
import com.sssoft.hk.navi.LatLng
import com.sssoft.hk.navi.Trip
import com.sssoft.hk.server.ChargerApi
import com.sssoft.hk.server.StationView
import com.sssoft.hk.vehicle.VehicleReading
import kotlin.math.pow

/** 예측에 필요한 현재 조건: 학습 프로필 + 차량 상태 + 날씨. */
data class TripConditions(
    val socPercent: Int,
    val profile: VehicleProfile,
    val tempC: Double,
    val weather: Weather = Weather.CLEAR,
    val occupants: Int = 1,
    val luggageKg: Double = 30.0,
    val hvacOn: Boolean = true,
    val cabinSetC: Double = 22.0,
    val tirePressureKpa: Double? = null,
    val mode: DriveMode = DriveMode.NORMAL,
    val departMs: Long = System.currentTimeMillis(),
) {
    fun env(plannedKm: Double, origin: LatLng?, dest: Destination): TripEnv {
        val here = origin ?: dest.location
        return TripEnv(
            context = TripContext.of(plannedKm, departMs, here.lat, here.lng, weather, dest.location.lat, dest.location.lng, mode),
            tempC = tempC, occupants = occupants, luggageKg = luggageKg,
            hvacOn = hvacOn, cabinSetC = cabinSetC, tirePressureKpa = tirePressureKpa,
        )
    }

    companion object {
        fun from(r: VehicleReading, profile: VehicleProfile, tempC: Double, weather: Weather, luggageKg: Double = 30.0) =
            TripConditions(
                socPercent = r.socPercent, profile = profile.withNominalCapacity(r.capacityKwh),
                tempC = tempC, weather = weather, occupants = r.occupants, luggageKg = luggageKg,
                hvacOn = r.hvacOn, cabinSetC = r.cabinSetC, tirePressureKpa = r.tirePressureKpa, mode = r.mode,
            )
    }
}

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
    /** 충전 없이 갈 때 (도착 확률, 주행시간) */
    val direct: ArrivalResult,
    val env: TripEnv,
) {
    val best: StopOption? get() = options.firstOrNull()
    val station: StationView? get() = best?.station

    /** 기준 대비 절약 시간(분, 중앙값 기준). */
    val savedMin: Double? get() {
        val b = best ?: return null
        val base = baseline ?: return null
        return (base.plan.minutesP50 - b.plan.minutesP50).takeIf { base.station.id != b.station.id }
    }

    /** 충전·대기 포함 총 소요 (중앙값, 늦어질 때) 분 */
    val totalMinutes: Pair<Double, Double>? get() = when (val p = plan) {
        is ChargePlanResult.Plan -> p.minutesP50 to p.minutesP90
        is ChargePlanResult.NoChargeNeeded -> direct.minutesP50 to direct.minutesP90
        is ChargePlanResult.Infeasible -> null
    }
}

/**
 * 내비 경로 + 차량 상태 + 학습 프로필 + 서버 충전소 실시간 상태를 몬테카를로에 넣는다.
 * UI, Gleo 음성 호출, 주행 중 감시가 같이 쓴다.
 */
class TripPlanner(private val api: ChargerApi = ChargerApi()) {

    fun arrival(c: TripConditions, origin: LatLng?, dest: Destination): ArrivalResult =
        EvMonteCarlo(c.profile).arrivalProbability(c.socPercent / 100.0, dest.remainKm, dest.remainMin, c.env(dest.remainKm, origin, dest))

    suspend fun recommend(c: TripConditions, origin: LatLng?, dest: Destination, trip: Trip?): ChargingRecommendation {
        val totalKm = trip?.distanceKm ?: dest.remainKm
        val totalMin = trip?.etaMin ?: dest.remainMin
        val env = c.env(totalKm, origin, dest)
        val soc0 = c.socPercent / 100.0
        val mc = EvMonteCarlo(c.profile)
        val direct = mc.arrivalProbability(soc0, totalKm, totalMin, env)

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

        if (direct.probability >= 0.95) {
            return ChargingRecommendation(ChargePlanResult.NoChargeNeeded(direct.probability), emptyList(), null, cands.size, direct, env)
        }

        val waits = cands.associate { it.view.id to expectedWaitMin(it.view, it.ratio * totalMin) }
        val chargers = cands.map { cd ->
            Charger(cd.view.id, cd.ratio * totalKm, cd.view.maxKw.coerceIn(30.0, 350.0), waits.getValue(cd.view.id), cd.view.highway)
        }
        val byId = cands.associateBy { it.view.id }
        val options = mc.bestStopPerCharger(soc0, totalKm, totalMin, env, chargers)
            .map { p ->
                val cd = byId.getValue(p.charger)
                StopOption(cd.view, cd.ratio * totalKm, cd.ratio * totalMin, waits.getValue(p.charger), p, cd.naviPick)
            }
            .sortedBy { EvMonteCarlo.score(it.plan) }
        val baseline = options.firstOrNull { it.naviPick } ?: options.maxByOrNull { it.station.maxKw }
        val plan = options.firstOrNull()?.plan ?: ChargePlanResult.Infeasible(direct.probability)
        return ChargingRecommendation(plan, options, baseline, cands.size, direct, env)
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
