package com.sssoft.hk.model

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 차량·운전자 학습 프로필. 모든 값은 (평균, 불확실성)을 가지며 주행·충전 기록으로 계속 갱신된다.
 * 몬테카를로는 이 분포에서 값을 뽑으므로, 학습이 쌓일수록 예측 범위가 좁아진다.
 *
 * - [energy]  차량 물리값: 공기저항·굴러가는 저항·공조·저온·가속 손실·날씨·밤·타이어 ([EnergyModel])
 * - [pace]    속도 습관: ln(실제 주행시간 / 내비 예상) = 조건 효과의 합 ([TripContext.habitFeatures])
 * - [accel]   가속 습관: ln(km·톤당 가속 에너지 / 평균) = 조건 효과의 합
 * - [destinations] 자주 가는 목적지별 보정 (최근 [MAX_DEST]곳만 유지)
 * - 충전 효율, 배터리 실사용 용량
 *
 * 조건을 칸으로 나누지 않고 "효과의 합"으로 학습하므로, 처음 겪는 조합(비 오는 밤 출퇴근 등)도
 * 각 효과를 합쳐 예측한다. 오래된 기록은 갱신마다 비중을 줄여 잊는다.
 * AAOS는 운전자 계정마다 앱 데이터가 분리되므로 프로필도 운전자별로 쌓인다.
 */
data class VehicleProfile(
    val energy: BayesLinear = BayesLinear.prior(EnergyModel.PRIOR_MEAN, EnergyModel.PRIOR_STD, ENERGY_FORGET, nonNegative = true),
    val pace: BayesLinear = BayesLinear.prior(DoubleArray(HABITS), PACE_PRIOR_STD, PACE_FORGET),
    val accel: BayesLinear = BayesLinear.prior(ACCEL_PRIOR_MEAN, ACCEL_PRIOR_STD, ACCEL_FORGET),
    val destinations: Map<String, DestBias> = emptyMap(),
    /** 실사용 배터리 용량 (kWh) */
    val capacityKwh: Double = 74.0,
    val capacityStd: Double = 3.0,
    val capacityLearned: Boolean = false,
    val curbMassKg: Double = 1950.0,
    /** 차량 최대 충전 출력 (kW) */
    val vehicleMaxKw: Double = 200.0,
    /** 권장 타이어 공기압 (kPa) */
    val tirePressureNominalKpa: Double = 250.0,
    /** 충전 효율 계수 k (실제 출력 / 기대 출력)의 로그 평균·표준편차 */
    val chargeLogK: Double = ln(0.9),
    val chargeLogKStd: Double = CHARGE_PRIOR_STD,
    val reserveSoc: Double = 0.10,
    val sessions: Int = 0,
    /** ln(실제 소모 / 내비 예측 소모). 차량 내비의 도착 예상 배터리를 이 운전자·차에 맞게 보정 */
    val navLogBias: Double = 0.0,
    val navLogBiasStd: Double = NAV_BIAS_PRIOR_STD,
    val navTrips: Int = 0,
) {
    val chargeK: Double get() = exp(chargeLogK)
    val segments: Int get() = energy.updates
    val trips: Int get() = pace.updates

    // ---- 예측 ----

    /** 이 조건에서 속도 습관 ln(실제/내비)의 (평균, 표준편차). 목적지 보정 포함. */
    fun paceLog(ctx: TripContext): Pair<Double, Double> {
        val x = ctx.habitFeatures()
        val dest = ctx.destKey?.let { destinations[it] }
        val mean = pace.predict(x) + (dest?.shrunkPace ?: 0.0)
        return mean to sqrt(pace.predictVar(x) + PACE_NOISE * PACE_NOISE)
    }

    /** 이 조건에서 km·톤당 가속 에너지 (Wh). */
    fun accelWhPerKmTon(ctx: TripContext): Double {
        val dest = ctx.destKey?.let { destinations[it] }
        return DEFAULT_ACCEL_WH_PER_KM_TON * exp(accel.predict(ctx.habitFeatures()) + (dest?.shrunkAccel ?: 0.0))
    }

    fun tireUnderinflation(pressureKpa: Double?): Double =
        pressureKpa?.let { ((tirePressureNominalKpa - it) / tirePressureNominalKpa).coerceIn(0.0, 0.5) } ?: 0.0

    // ---- 학습도 ----

    /**
     * 학습도 (0~1). 학습 횟수가 아니라 "예측 불확실성이 처음보다 얼마나 줄었나"로 계산한다.
     * 날마다 다른 몫은 학습으로 줄지 않으므로, 불확실성이 처음의 30% 이하가 되면 100% 로 본다.
     */
    val learningProgress: LearningProgress get() {
        fun shrink(now: Double, prior: Double) = ((1 - now / prior) / 0.7).coerceIn(0.0, 1.0)
        val mass = curbMassKg + EnergyModel.PASSENGER_KG + 30
        val ref = EnergyInput(
            distKm = 100.0, hours = 1.0, tempC = 5.0, massKg = mass, hvacOn = true, cabinSetC = 22.0,
            weather = Weather.CLEAR, night = false, tireUnderinflation = 0.0,
            accelKineticWh = EnergyModel.accelWh(DEFAULT_ACCEL_WH_PER_KM_TON, 100.0, mass),
        )
        val x = EnergyModel.features(ref)
        val priorEnergy = BayesLinear.prior(EnergyModel.PRIOR_MEAN, EnergyModel.PRIOR_STD, ENERGY_FORGET, true)
        val vehicle = shrink(sqrt(energy.predictVar(x)), sqrt(priorEnergy.predictVar(x)))

        val hx = TripContext(TripType.SHORT, night = false, weather = Weather.CLEAR, weekend = false).habitFeatures()
        val priorPace = BayesLinear.prior(DoubleArray(HABITS), PACE_PRIOR_STD, PACE_FORGET)
        val habit = shrink(sqrt(pace.predictVar(hx)), sqrt(priorPace.predictVar(hx)))

        // 내비 보정을 학습했다면 차량 특성은 그만큼 알고 있는 것
        val navCal = ((NAV_BIAS_PRIOR_STD - navLogBiasStd) / (NAV_BIAS_PRIOR_STD - NAV_BIAS_FLOOR)).coerceIn(0.0, 1.0)
        val charging = ((CHARGE_PRIOR_STD - chargeLogKStd) / (CHARGE_PRIOR_STD - 0.04)).coerceIn(0.0, 1.0)
        val places = (destinations.values.count { it.visits >= 2 } / 5.0).coerceIn(0.0, 1.0)
        return LearningProgress(maxOf(vehicle, navCal), habit, charging, places)
    }

    // ---- 학습 ----

    /** 주행 구간 하나로 차량 물리값과 가속 습관(조건별)·목적지 보정을 갱신. */
    fun learnSegment(s: DriveSegment): VehicleProfile {
        val e = s.energyInput
        val x = EnergyModel.features(e)
        val y = s.energyWh - EnergyModel.climbWh(e)
        val pred = energy.predict(x)
        var p = copy(energy = energy.update(x, y, EnergyModel.noiseStdWh(pred)))

        if (s.distKm > 0.5 && s.energyInput.accelKineticWh > 0) {
            val obs = ln(s.energyInput.accelKineticWh / s.distKm / (e.massKg / 1000.0) / DEFAULT_ACCEL_WH_PER_KM_TON)
            val hx = s.context.habitFeatures()
            val resid = obs - accel.predict(hx)
            p = p.copy(accel = p.accel.update(hx, obs, ACCEL_NOISE))
            p = p.withDest(s.context.destKey) { it.learnAccel(resid) }
        }
        return p
    }

    /** 주행 하나가 끝났을 때 내비 예상 대비 실제 주행시간으로 속도 습관 갱신 (정차·충전 시간 제외). */
    fun learnPace(ctx: TripContext, navEtaMin: Double, actualDriveMin: Double): VehicleProfile {
        if (navEtaMin < 5 || actualDriveMin <= 0) return this
        val obs = ln(actualDriveMin / navEtaMin)
        val x = ctx.habitFeatures()
        val resid = obs - pace.predict(x)
        return copy(pace = pace.update(x, obs, PACE_NOISE)).withDest(ctx.destKey) { it.learnPace(resid) }
    }

    /**
     * 주행 하나가 끝났을 때 내비 예측 소모(출발 배터리 − 내비 도착 예상) 대비 실제 소모로 보정값 갱신.
     * 최근 약 30회 위주, 날마다 다른 몫은 [NAV_BIAS_FLOOR] 아래로 줄이지 않는다.
     */
    fun learnNavBias(navUsedSoc: Double, actualUsedSoc: Double): VehicleProfile {
        if (navUsedSoc < 0.03 || actualUsedSoc <= 0) return this
        val inflated = navLogBiasStd / sqrt(PACE_FORGET)
        val (mean, std) = gaussUpdate(navLogBias, inflated, ln(actualUsedSoc / navUsedSoc), NAV_BIAS_OBS_STD)
        return copy(navLogBias = mean, navLogBiasStd = maxOf(std, NAV_BIAS_FLOOR), navTrips = navTrips + 1)
    }

    /** 충전 기록으로 충전 효율 계수 갱신. */
    fun learnCharge(c: ChargeSession): VehicleProfile {
        val predAtK1 = ChargeCurve.minutes(c.fromSoc, c.toSoc, c.chargerKw, capacityKwh, vehicleMaxKw, c.tempC, k = 1.0)
        if (c.minutes <= 0 || predAtK1 <= 0) return this
        val (mean, std) = gaussUpdate(chargeLogK, chargeLogKStd, ln(predAtK1 / c.minutes), CHARGE_OBS_STD)
        return copy(chargeLogK = mean, chargeLogKStd = std, sessions = sessions + 1)
    }

    /** 배터리 잔량(Wh)과 SOC 변화로 실사용 용량 갱신. */
    fun learnCapacity(deltaWh: Double, deltaSoc: Double): VehicleProfile {
        if (deltaSoc < 0.05) return this
        val obs = deltaWh / 1000.0 / deltaSoc
        val (mean, std) = gaussUpdate(capacityKwh, capacityStd, obs, obs * 0.03)
        return copy(capacityKwh = mean, capacityStd = std, capacityLearned = true)
    }

    /** SDK가 주는 공칭 용량을 아직 학습 전일 때만 반영. */
    fun withNominalCapacity(nominalKwh: Double?): VehicleProfile =
        if (nominalKwh == null || capacityLearned) this else copy(capacityKwh = nominalKwh * 0.95)

    /** 목적지 보정 갱신. 최근 [MAX_DEST]곳만 남기고 오래 안 간 곳부터 지운다. */
    private fun withDest(key: String?, f: (DestBias) -> DestBias): VehicleProfile {
        if (key == null) return this
        // 같은 밀리초에 여러 번 갱신돼도 순서가 유지되게 항상 증가
        val stamp = maxOf(System.currentTimeMillis(), (destinations.values.maxOfOrNull { it.lastUsed } ?: 0L) + 1)
        val updated = f(destinations[key] ?: DestBias()).copy(lastUsed = stamp)
        val all = destinations + (key to updated)
        val kept = if (all.size <= MAX_DEST) all
        else all.entries.sortedByDescending { it.value.lastUsed }.take(MAX_DEST).associate { it.key to it.value }
        return copy(destinations = kept)
    }

    fun toJson(): String = JSONObject()
        .put("energy", energy.toJson()).put("pace", pace.toJson()).put("accel", accel.toJson())
        .put("destinations", JSONObject().apply { destinations.forEach { (k, v) -> put(k, v.toJson()) } })
        .put("capacityKwh", capacityKwh).put("capacityStd", capacityStd).put("capacityLearned", capacityLearned)
        .put("curbMassKg", curbMassKg).put("vehicleMaxKw", vehicleMaxKw).put("tirePressureNominalKpa", tirePressureNominalKpa)
        .put("chargeLogK", chargeLogK).put("chargeLogKStd", chargeLogKStd)
        .put("reserveSoc", reserveSoc).put("sessions", sessions)
        .put("navLogBias", navLogBias).put("navLogBiasStd", navLogBiasStd).put("navTrips", navTrips)
        .toString()

    companion object {
        val HABITS = TripContext.HABIT_NAMES.size
        /** 일반 혼합 주행의 km·톤당 가속 운동에너지 (Wh). 가속 습관 지수 1.0 기준. */
        const val DEFAULT_ACCEL_WH_PER_KM_TON = 25.0
        const val MAX_DEST = 30

        /** 차량 물리값: 유효 기억 약 100구간 */
        const val ENERGY_FORGET = 0.99
        /** 속도 습관: 약 30회 주행 */
        const val PACE_FORGET = 0.97
        /** 가속 습관: 약 50구간 */
        const val ACCEL_FORGET = 0.98

        /** [기본, 출퇴근, 장거리, 밤, 비, 눈, 주말, 에코, 스포츠] 효과의 사전 범위 */
        val PACE_PRIOR_STD = doubleArrayOf(0.10, 0.06, 0.06, 0.05, 0.06, 0.10, 0.05, 0.04, 0.04)
        /** 가속 습관 시작값 ln(배수): 출퇴근은 가다 서다로 크고(×1.35), 장거리는 정속이라 작다(×0.37) */
        val ACCEL_PRIOR_MEAN = doubleArrayOf(0.0, 0.3, -1.0, 0.0, 0.0, 0.0, 0.0, -0.1, 0.2)
        val ACCEL_PRIOR_STD = doubleArrayOf(0.30, 0.20, 0.20, 0.15, 0.15, 0.20, 0.15, 0.15, 0.20)
        /** 같은 조건이라도 날마다 다른 정도 (학습으로 줄지 않는 몫) */
        const val PACE_NOISE = 0.06
        const val ACCEL_NOISE = 0.25
        private const val CHARGE_OBS_STD = 0.10
        private const val CHARGE_PRIOR_STD = 0.15
        /** 내비 예측 소모와 실제의 차이: 처음엔 ±8%, 학습해도 ±2% 아래로는 안 줄임 */
        const val NAV_BIAS_PRIOR_STD = 0.08
        private const val NAV_BIAS_OBS_STD = 0.05
        private const val NAV_BIAS_FLOOR = 0.02

        fun fromJson(s: String): VehicleProfile {
            val o = JSONObject(s)
            val d = o.optJSONObject("destinations")
            val dest = d?.keys()?.asSequence()?.associateWith { DestBias.fromJson(d.getJSONObject(it)) }.orEmpty()
            return VehicleProfile(
                energy = BayesLinear.fromJson(o.optJSONObject("energy"), EnergyModel.PRIOR_MEAN, EnergyModel.PRIOR_STD, ENERGY_FORGET, true),
                pace = BayesLinear.fromJson(o.optJSONObject("pace"), DoubleArray(HABITS), PACE_PRIOR_STD, PACE_FORGET),
                accel = BayesLinear.fromJson(o.optJSONObject("accel"), ACCEL_PRIOR_MEAN, ACCEL_PRIOR_STD, ACCEL_FORGET),
                destinations = dest,
                capacityKwh = o.optDouble("capacityKwh", 74.0), capacityStd = o.optDouble("capacityStd", 3.0),
                capacityLearned = o.optBoolean("capacityLearned", false),
                curbMassKg = o.optDouble("curbMassKg", 1950.0), vehicleMaxKw = o.optDouble("vehicleMaxKw", 200.0),
                tirePressureNominalKpa = o.optDouble("tirePressureNominalKpa", 250.0),
                chargeLogK = o.optDouble("chargeLogK", ln(0.9)), chargeLogKStd = o.optDouble("chargeLogKStd", 0.15),
                reserveSoc = o.optDouble("reserveSoc", 0.10), sessions = o.optInt("sessions", 0),
                navLogBias = o.optDouble("navLogBias", 0.0), navLogBiasStd = o.optDouble("navLogBiasStd", NAV_BIAS_PRIOR_STD),
                navTrips = o.optInt("navTrips", 0),
            )
        }

        /** 1차원 가우스 갱신 → (평균, 표준편차) */
        fun gaussUpdate(mean: Double, std: Double, obs: Double, obsStd: Double): Pair<Double, Double> {
            val v0 = std * std
            val v1 = obsStd * obsStd
            return (mean * v1 + obs * v0) / (v0 + v1) to sqrt(v0 * v1 / (v0 + v1))
        }
    }
}

/** 학습도 (각 0~1). */
data class LearningProgress(val vehicle: Double, val habit: Double, val charging: Double, val places: Double) {
    val overall: Double get() = 0.45 * vehicle + 0.25 * habit + 0.2 * charging + 0.1 * places
}

/** 자주 가는 목적지별 보정 (모델 예측과 실제의 차이 이동평균). 몇 번 안 가본 곳은 보정을 약하게 쓴다. */
data class DestBias(
    val paceResid: Double = 0.0,
    val accelResid: Double = 0.0,
    val visits: Int = 0,
    val lastUsed: Long = 0,
) {
    private val shrink: Double get() = visits / (visits + 3.0)
    val shrunkPace: Double get() = paceResid * shrink
    val shrunkAccel: Double get() = accelResid * shrink

    fun learnPace(resid: Double) = copy(paceResid = ema(paceResid, resid), visits = visits + 1)
    fun learnAccel(resid: Double) = copy(accelResid = ema(accelResid, resid))

    private fun ema(old: Double, obs: Double) = old + (obs - old) / minOf(visits + 1, 10)

    fun toJson(): JSONObject = JSONObject()
        .put("paceResid", paceResid).put("accelResid", accelResid).put("visits", visits).put("lastUsed", lastUsed)

    companion object {
        fun fromJson(o: JSONObject) = DestBias(
            o.optDouble("paceResid", 0.0), o.optDouble("accelResid", 0.0), o.optInt("visits", 0), o.optLong("lastUsed", 0),
        )
    }
}

/** 학습용 주행 구간 하나 (몇 km 또는 몇 분 단위). */
data class DriveSegment(
    val energyInput: EnergyInput,
    /** 실제 소모 (Wh). 배터리 잔량 변화로 측정 */
    val energyWh: Double,
    val context: TripContext,
) {
    val distKm: Double get() = energyInput.distKm
}

/** 학습용 충전 기록. */
data class ChargeSession(
    val fromSoc: Double,
    val toSoc: Double,
    val minutes: Double,
    val chargerKw: Double,
    val tempC: Double,
)
