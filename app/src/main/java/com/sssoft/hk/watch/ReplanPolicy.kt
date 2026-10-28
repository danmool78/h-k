package com.sssoft.hk.watch

import com.sssoft.hk.server.StationView
import com.sssoft.hk.trip.ChargingRecommendation
import com.sssoft.hk.trip.StopOption
import com.sssoft.hk.trip.TripPlanner

/** 주행 중 재계획 판단 규칙. */
object ReplanPolicy {

    /** 도착 시 예상 대기가 이 이상이면 재계획을 검토. */
    const val WAIT_TRIGGER_MIN = 10.0

    /** 바꾸는 쪽이 이만큼 이상 빨라야 경유지를 변경 (잦은 변경 방지). */
    const val MIN_SAVING_MIN = 5.0

    /** 목표 충전소 상태가 나빠졌는지. */
    fun targetDegraded(target: StationView?, etaMin: Double): Boolean =
        target == null || target.inService == 0 || TripPlanner.expectedWaitMin(target, etaMin) >= WAIT_TRIGGER_MIN

    sealed interface Decision {
        data object Keep : Decision
        data class Switch(val to: StopOption, val savedMin: Double?) : Decision
    }

    /** 새 추천과 현재 목표를 비교해 바꿀지 결정. 현재 목표가 후보에 없으면(안전하게 못 감) 바꾼다. */
    fun decide(currentTargetId: String, rec: ChargingRecommendation): Decision {
        val best = rec.best ?: return Decision.Keep
        if (best.station.id == currentTargetId) return Decision.Keep
        val current = rec.options.firstOrNull { it.station.id == currentTargetId }
            ?: return Decision.Switch(best, null)
        val saved = current.plan.minutesP50 - best.plan.minutesP50
        return if (saved >= MIN_SAVING_MIN) Decision.Switch(best, saved) else Decision.Keep
    }
}
