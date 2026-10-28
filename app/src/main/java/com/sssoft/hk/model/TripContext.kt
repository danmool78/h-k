package com.sssoft.hk.model

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

enum class Weather(val label: String) { CLEAR("맑음"), RAIN("비"), SNOW("눈") }

/** 차량 주행 모드 (Vehicle SDK DrivingMode). */
enum class DriveMode(val label: String) { ECO("에코"), NORMAL("일반"), SPORT("스포츠") }

/** 주행 유형. 계획 거리와 출발 시각으로 자동 분류. */
enum class TripType(val label: String) {
    COMMUTE("출퇴근"), LONG("장거리"), SHORT("단거리");

    companion object {
        const val LONG_KM = 80.0
    }
}

/**
 * 운전 습관과 소모에 영향을 주는 주행 조건.
 * [destKey] 는 목적지 약 500m 격자 (자주 가는 목적지별 보정에 사용).
 */
data class TripContext(
    val type: TripType,
    val night: Boolean,
    val weather: Weather,
    val weekend: Boolean,
    val mode: DriveMode = DriveMode.NORMAL,
    val destKey: String? = null,
) {
    /** 운전 습관 모델의 특징: [기본, 출퇴근, 장거리, 밤, 비, 눈, 주말, 에코, 스포츠] */
    fun habitFeatures() = doubleArrayOf(
        1.0,
        if (type == TripType.COMMUTE) 1.0 else 0.0,
        if (type == TripType.LONG) 1.0 else 0.0,
        if (night) 1.0 else 0.0,
        if (weather == Weather.RAIN) 1.0 else 0.0,
        if (weather == Weather.SNOW) 1.0 else 0.0,
        if (weekend) 1.0 else 0.0,
        if (mode == DriveMode.ECO) 1.0 else 0.0,
        if (mode == DriveMode.SPORT) 1.0 else 0.0,
    )

    companion object {
        val HABIT_NAMES = listOf("기본", "출퇴근", "장거리", "밤", "비", "눈", "주말", "에코 모드", "스포츠 모드")

        fun of(
            plannedKm: Double, departMs: Long, lat: Double, lng: Double, weather: Weather,
            destLat: Double?, destLng: Double?, mode: DriveMode = DriveMode.NORMAL,
        ): TripContext {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).apply { timeInMillis = departMs }
            val weekend = cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val type = when {
                plannedKm >= TripType.LONG_KM -> TripType.LONG
                !weekend && (hour in 6..9 || hour in 17..19) -> TripType.COMMUTE
                else -> TripType.SHORT
            }
            return TripContext(
                type = type,
                night = isDark(departMs, lat, lng),
                weather = weather,
                weekend = weekend,
                mode = mode,
                destKey =if (destLat != null && destLng != null) destKey(destLat, destLng) else null,
            )
        }

        /** 약 500m 격자 */
        fun destKey(lat: Double, lng: Double) = "%.3f,%.3f".format(Math.round(lat / 0.005) * 0.005, Math.round(lng / 0.005) * 0.005)

        /** 태양 고도가 -6° 아래(시민박명 이후)면 어두움. 간단한 천문 근사식. */
        fun isDark(epochMs: Long, lat: Double, lng: Double): Boolean = solarElevationDeg(epochMs, lat, lng) < -6.0

        fun solarElevationDeg(epochMs: Long, lat: Double, lng: Double): Double {
            val days = epochMs / 86_400_000.0
            val dayOfYear = (days % 365.2422)
            val decl = -23.44 * cos(2 * PI / 365.0 * (dayOfYear + 10)) * PI / 180
            val utcHours = (epochMs % 86_400_000L) / 3_600_000.0
            val solarTime = utcHours + lng / 15.0
            val hourAngle = (solarTime - 12.0) * 15.0 * PI / 180
            val latR = lat * PI / 180
            val sinEl = sin(latR) * sin(decl) + cos(latR) * cos(decl) * cos(hourAngle)
            return asin(sinEl) * 180 / PI
        }
    }
}
