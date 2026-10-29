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
    /** 창문을 열지 운전자에게 묻는다 (화면에 예/아니오) */
    data class AskWindow(val windows: List<Window>) : CareAction
    /** 질문을 내린다 (답했거나 시간이 지남) */
    data object AskDone : CareAction
    /** 먼지 나는 길: 바깥 공기 대신 내부 순환 + 공기청정 */
    data object DustGuard : CareAction
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
    /** 창문 질문에 대한 답. true = 열기, false = 열지 않기 (먼지), null = 답 없음 */
    val windowAnswer: Boolean? = null,
)

/**
 * 멀미 누적량과 차량 상황으로 조치를 정한다 (화면·SDK 없음, 테스트 가능).
 *
 * - 넘칠 때마다 "찰랑"
 * - 주의 (누적 0.6): "다음 구간은 부드럽게"
 * - 자주 넘침 (누적 0.5, 또는 3분 안에 4번 넘치고 누적 0.25): 외기 유입 + 아이 옆 창문을 열지 묻는다
 *   (비 오거나 고속이면 묻지 않음). "열지 않기" = 먼지 나는 길 → 내부 순환 + 공기청정, 이번 환기 동안 다시 묻지 않음
 * - 위험 (누적 1.0): 휴게소에서 쉬어 가기
 * - 회복 (누적 0.25 미만, 3분 동안 안 넘침): 공조·창문 원래대로
 * - 내비 다음 안내가 흔들리는 곳(진출로·유턴 등)이고 누적이 쌓여 있으면 미리 예고
 */
/** [askTimeoutSec]: 창문 질문에 답이 없으면 열지 않는 시간 (시연은 배속만큼 늘린다) */
class CarePolicy(private val askTimeoutSec: Double = ASK_TIMEOUT) {

    var ventilating = false; private set
    var windowsOpen = false; private set
    /** 창문 질문을 띄운 시각 (답을 기다리는 중) */
    var askingSince: Double? = null; private set
    /** 이번 환기에서 창문 열기를 허락/거절했는지 */
    private var windowApproved = false
    private var windowDeclined = false
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

        // 창문 질문에 답이 왔거나 시간이 지남
        val asking = askingSince
        if (asking != null) {
            val answer = input.windowAnswer
            if (answer == true) {
                askingSince = null
                windowApproved = true
                out += CareAction.AskDone
                if (windowOk(input)) {
                    windowsOpen = true
                    out += CareAction.OpenWindows(input.setup.windows, WINDOW_PERCENT)
                }
            } else if (answer == false) {
                askingSince = null
                windowDeclined = true
                out += CareAction.AskDone
                out += CareAction.DustGuard
                out += CareAction.Say("창문은 닫아 두고 내부 순환과 공기청정으로 바꿀게요")
            } else if (now - asking >= askTimeoutSec) {
                askingSince = null
                windowDeclined = true
                out += CareAction.AskDone
            }
        }

        // 환기 시작
        if (frequent && !ventilating) {
            ventilating = true
            out += CareAction.FreshAir(true)
            if (windowOk(input) && input.setup.windows.isNotEmpty()) {
                ask(now, input, out, "바깥 공기를 넣을게요. $who 쪽 창문도 조금 열까요?")
            } else {
                out += CareAction.Say("바깥 공기를 넣고 조금 시원하게 할게요")
            }
        } else if (ventilating) {
            // 환기 중: 비·고속이면 창문 닫고, 다시 괜찮아지면 (허락받았으면) 연다
            if (windowsOpen && !windowOk(input, closing = true)) {
                windowsOpen = false
                out += CareAction.CloseWindows
            } else if (!windowsOpen && windowOk(input) && input.setup.windows.isNotEmpty() && input.dose >= VENTILATE) {
                if (windowApproved) {
                    windowsOpen = true
                    out += CareAction.OpenWindows(input.setup.windows, WINDOW_PERCENT)
                } else if (!windowDeclined && askingSince == null) {
                    ask(now, input, out, "$who 쪽 창문을 조금 열까요?")
                }
            }
            // 회복
            if (input.dose < RECOVERED && now - lastSpill >= SPILL_WINDOW) {
                restore(out)
                out += CareAction.Say("$who 컨디션이 돌아왔어요. 창문과 공조를 원래대로 할게요")
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

    private fun ask(now: Double, input: CareInput, out: MutableList<CareAction>, text: String) {
        askingSince = now
        out += CareAction.AskWindow(input.setup.windows)
        out += CareAction.Say(text)
    }

    private fun restore(out: MutableList<CareAction>) {
        if (askingSince != null) out += CareAction.AskDone
        if (windowsOpen) out += CareAction.CloseWindows
        if (ventilating) out += CareAction.FreshAir(false)
        askingSince = null
        windowsOpen = false
        ventilating = false
        windowApproved = false
        windowDeclined = false
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
        /** 창문 질문에 답이 없으면 열지 않는다 (초) */
        const val ASK_TIMEOUT = 20.0
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
