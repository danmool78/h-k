package com.sssoft.chalrang.core

/** 내비가 알려 준 앞으로 올 움직임 */
enum class ManeuverKind(val label: String, val shaky: Boolean) {
    TURN("교차로 회전", false),
    SHARP_TURN("급한 회전", true),
    UTURN("유턴", true),
    IC_EXIT("진출로", true),
    IC_ENTER("진입로", true),
    ROUNDABOUT("회전 교차로", true),
    SPEED_BUMP("과속 방지턱", true),
    REST_AREA("휴게소", false),
    OTHER("", false),
}

data class Upcoming(val kind: ManeuverKind, val distanceM: Double)

/** 차량에 내리는 조치 */
sealed interface CareAction {
    /** "찰랑" 소리 */
    data object Chime : CareAction
    data class Say(val text: String) : CareAction
    /** 외기 유입 + 에어컨 + 온도 조금 낮춤 (on), 원래대로 (off) */
    data class FreshAir(val on: Boolean) : CareAction
    /** 창문을 [percent]% 연다 */
    data class OpenWindows(val windows: List<Window>, val percent: Int) : CareAction
    /** 연 창문을 원래대로 닫는다 */
    data object CloseWindows : CareAction
}

/** 한 번의 판단에 필요한 값 */
data class CareInput(
    val nowSec: Double,
    val setup: CupSetup,
    val dose: Double,
    /** 막 넘치기 시작했는지 */
    val spillStarted: Boolean,
    val speedKmh: Double?,
    /** 와이퍼가 움직이면 비 → 창문을 열지 않는다 */
    val raining: Boolean = false,
    val next: Upcoming? = null,
    /** 경로상 다음 휴게소까지 (km) */
    val restAreaKm: Double? = null,
    /** 보호자가 "지금 멀미" 를 눌렀다 */
    val sickNow: Boolean = false,
)

/**
 * 멀미 누적량과 차량 상황으로 조치를 정한다 (화면·SDK 없음, 테스트 가능).
 *
 * - 넘칠 때마다 "찰랑"
 * - 주의 (누적 0.6): "다음 구간은 부드럽게"
 * - 자주 넘침 (누적 0.5, 또는 3분 안에 4번 넘치고 누적 0.25): 외기 유입 + 아이 옆 창문 조금 (비 오거나 고속이면 창문은 닫힌 채)
 * - 위험 (누적 1.0): 휴게소에서 쉬어 가기
 * - 회복 (누적 0.25 미만, 3분 동안 안 넘침): 공조·창문 원래대로
 * - 내비 다음 안내가 흔들리는 곳(진출로·유턴 등)이고 누적이 쌓여 있으면 미리 예고
 */
class CarePolicy {

    var ventilating = false; private set
    var windowsOpen = false; private set
    private val spillTimes = ArrayDeque<Double>()
    private var lastChime = Double.NEGATIVE_INFINITY
    private var lastCaution = Double.NEGATIVE_INFINITY
    private var lastDanger = Double.NEGATIVE_INFINITY
    private var lastPreview = Double.NEGATIVE_INFINITY
    private var lastSpill = Double.NEGATIVE_INFINITY

    /** 최근 [SPILL_WINDOW] 초 동안 넘친 횟수 */
    val recentSpills: Int get() = spillTimes.size

    fun decide(input: CareInput): List<CareAction> {
        val now = input.nowSec
        val out = mutableListOf<CareAction>()
        if (input.spillStarted) {
            spillTimes.addLast(now)
            lastSpill = now
        }
        while (spillTimes.isNotEmpty() && now - spillTimes.first() > SPILL_WINDOW) spillTimes.removeFirst()

        if (!input.setup.active) {
            // 혼자 주행: 아무것도 안 하고, 하던 조치는 되돌린다
            restore(out)
            return out
        }

        if (input.spillStarted && now - lastChime >= CHIME_GAP) {
            out += CareAction.Chime
            lastChime = now
        }

        val who = input.setup.primary?.name ?: "동승자"
        val danger = input.dose >= DANGER || input.sickNow
        val frequent = input.dose >= VENTILATE || (recentSpills >= FREQUENT_SPILLS && input.dose >= FREQUENT_MIN_DOSE) || input.sickNow

        // 환기 시작
        if (frequent && !ventilating) {
            ventilating = true
            out += CareAction.FreshAir(true)
            val canOpen = windowOk(input)
            if (canOpen && input.setup.windows.isNotEmpty()) {
                windowsOpen = true
                out += CareAction.OpenWindows(input.setup.windows, WINDOW_PERCENT)
                out += CareAction.Say("${who} 쪽 창문을 조금 열고 바깥 공기를 넣을게요")
            } else {
                out += CareAction.Say("바깥 공기를 넣고 조금 시원하게 할게요")
            }
        } else if (ventilating) {
            // 환기 중: 비·고속이면 창문 닫고, 다시 괜찮아지면 연다
            if (windowsOpen && !windowOk(input, closing = true)) {
                windowsOpen = false
                out += CareAction.CloseWindows
            } else if (!windowsOpen && windowOk(input) && input.setup.windows.isNotEmpty() && input.dose >= VENTILATE) {
                windowsOpen = true
                out += CareAction.OpenWindows(input.setup.windows, WINDOW_PERCENT)
            }
            // 회복
            if (input.dose < RECOVERED && now - lastSpill >= SPILL_WINDOW) {
                restore(out)
                out += CareAction.Say("${who} 컨디션이 돌아왔어요. 창문과 공조를 원래대로 할게요")
            }
        }

        when {
            danger && (input.sickNow || now - lastDanger >= DANGER_GAP) -> {
                lastDanger = now
                lastCaution = now
                out += CareAction.Chime
                val rest = input.restAreaKm
                out += CareAction.Say(
                    if (rest != null && rest <= REST_AREA_MAX_KM) {
                        "${who} 멀미 위험이 높아요. ${formatKm(rest)} 앞 휴게소에서 쉬어 가세요"
                    } else {
                        "${who} 멀미 위험이 높아요. 안전한 곳에서 잠시 쉬어 가세요"
                    },
                )
            }
            input.dose >= CAUTION && now - lastCaution >= CAUTION_GAP -> {
                lastCaution = now
                out += CareAction.Say("물이 찰랑거려요. 다음 구간은 부드럽게 가 주세요")
            }
            else -> {
                val next = input.next
                if (next != null && next.kind.shaky && next.distanceM <= PREVIEW_M &&
                    input.dose >= PREVIEW_DOSE && now - lastPreview >= PREVIEW_GAP
                ) {
                    lastPreview = now
                    out += CareAction.Say("곧 ${withYeyo(next.kind.label)}. 천천히 부드럽게")
                }
            }
        }
        return out
    }

    private fun windowOk(input: CareInput, closing: Boolean = false): Boolean {
        if (input.raining) return false
        // 속도를 모르면 새로 열지는 않고, 이미 열려 있으면 둔다
        val v = input.speedKmh ?: return closing
        return v <= if (closing) WINDOW_CLOSE_KMH else WINDOW_OPEN_KMH
    }

    private fun restore(out: MutableList<CareAction>) {
        if (windowsOpen) out += CareAction.CloseWindows
        if (ventilating) out += CareAction.FreshAir(false)
        windowsOpen = false
        ventilating = false
    }

    companion object {
        const val CAUTION = 0.6
        const val VENTILATE = 0.5
        const val DANGER = 1.0
        const val RECOVERED = 0.25
        const val FREQUENT_SPILLS = 4
        /** 살짝 넘친 것만으로는 환기하지 않는다 */
        const val FREQUENT_MIN_DOSE = 0.25
        const val SPILL_WINDOW = 180.0
        const val CHIME_GAP = 6.0
        const val CAUTION_GAP = 180.0
        const val DANGER_GAP = 600.0
        const val PREVIEW_GAP = 60.0
        const val PREVIEW_M = 400.0
        const val PREVIEW_DOSE = 0.3
        const val WINDOW_PERCENT = 25
        /** 이 속도 이하에서만 창문을 연다 (바람 소리·안전) */
        const val WINDOW_OPEN_KMH = 80.0
        const val WINDOW_CLOSE_KMH = 90.0
        const val REST_AREA_MAX_KM = 30.0

        /** "진출로" → "진출로예요", "유턴" → "유턴이에요" */
        fun withYeyo(word: String): String {
            val last = word.lastOrNull() ?: return word
            val hasFinal = last in '가'..'힣' && (last - '가') % 28 != 0
            return word + if (hasFinal) "이에요" else "예요"
        }

        fun formatKm(km: Double): String = if (km < 1) "${(km * 1000).toInt() / 100 * 100}m" else "${km.toInt()}km"
    }
}
