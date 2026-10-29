package com.sssoft.chalrang.core

/** 운전석을 뺀 좌석. 창문은 그 좌석 옆 창문 */
enum class Seat(val label: String) {
    FRONT_RIGHT("조수석"),
    REAR_LEFT("뒷좌석 왼쪽"),
    REAR_CENTER("뒷좌석 가운데"),
    REAR_RIGHT("뒷좌석 오른쪽"),
}

/** 창문 위치 (운전석 창문은 쓰지 않는다) */
enum class Window { FRONT_RIGHT, REAR_LEFT, REAR_RIGHT }

/** 아이 프로필. [level] = 물 높이 (멀미 기준), [calibrations] = "지금 멀미" 로 맞춘 횟수 */
data class Child(
    val id: String,
    val name: String,
    val seat: Seat,
    val level: Double = WaterCup.DEFAULT_LEVEL,
    val calibrations: Int = 0,
)

/** 좌석 하나의 상태. 벨트 정보가 없으면 탑승만 본다 (짐과 구분 못 함) */
data class SeatState(val occupied: Boolean, val belted: Boolean? = null) {
    val riding: Boolean get() = occupied && belted != false
}

/** 지금 물컵 설정 */
data class CupSetup(
    /** 지금 탄 아이들 (물 높이가 높은 순) */
    val children: List<Child>,
    /** 등록되지 않은 동승자가 앉은 좌석 */
    val guests: List<Seat>,
    /** 물 높이. null = 혼자 주행 (물컵 끔) */
    val level: Double?,
    /** 환기할 때 열 창문 (아이 옆) */
    val windows: List<Window>,
) {
    val active: Boolean get() = level != null

    /** 기준을 맞출 아이 ("지금 멀미" 를 눌렀을 때). 가장 예민한 아이 */
    val primary: Child? get() = children.firstOrNull()

    companion object {
        /** 등록 안 된 동승자 물 높이 */
        const val GUEST_LEVEL = 0.5

        /**
         * 좌석 상태로 물컵을 고른다.
         * - 등록한 아이가 자기 자리에 탔으면 그 아이 기준, 여럿이면 가장 예민한 아이
         * - 모르는 동승자만 있으면 보통 기준
         * - 혼자면 끔
         */
        fun choose(seats: Map<Seat, SeatState>, children: List<Child>): CupSetup {
            val riding = seats.filterValues { it.riding }.keys
            val aboard = children.filter { it.seat in riding }.sortedByDescending { it.level }
            val guests = riding.filter { s -> aboard.none { it.seat == s } }.sorted()
            val level = when {
                aboard.isNotEmpty() -> aboard.first().level
                guests.isNotEmpty() -> GUEST_LEVEL
                else -> null
            }
            val windowSeats = aboard.map { it.seat }.ifEmpty { guests }
            return CupSetup(aboard, guests, level, windowSeats.flatMap(::windowsFor).distinct())
        }

        fun windowsFor(seat: Seat): List<Window> = when (seat) {
            Seat.FRONT_RIGHT -> listOf(Window.FRONT_RIGHT)
            Seat.REAR_LEFT -> listOf(Window.REAR_LEFT)
            Seat.REAR_RIGHT -> listOf(Window.REAR_RIGHT)
            Seat.REAR_CENTER -> listOf(Window.REAR_LEFT, Window.REAR_RIGHT)
        }
    }
}
