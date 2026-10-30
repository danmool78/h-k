package com.sssoft.chalrang.core

/** 설정 페이지에 모아 둔 것 (기기 안에만 저장) */
data class AppSettings(
    // 소리
    /** 음성 안내 */
    val voice: Boolean = true,
    /** 넘칠 때 "찰랑" 소리 */
    val chime: Boolean = true,
    /** 안내 적게: "부드럽게" 주의·예고는 말하지 않음 (위험·휴게소는 말함) */
    val fewerAlerts: Boolean = false,
    // 차량
    /** 물이 자주 넘치면 자동 환기 (외기 유입·에어컨·온도·풍량) */
    val autoVent: Boolean = true,
    /** 환기할 때 아이 옆 창문을 열지 묻기 */
    val askWindow: Boolean = true,
    /** 창문 여는 정도 (%) */
    val windowPercent: Int = CarePolicy.WINDOW_PERCENT,
    // 길
    /** 가는 길 멀미 위험 구간 미리보기 */
    val routePreview: Boolean = true,
    /** 지형(고도)을 인터넷에서 받아 오르내림도 보기 */
    val terrain: Boolean = true,
    /** 길 안내가 시작되면 3D 경로 브리핑 (정차 중일 때만) */
    val briefing: Boolean = true,
    // 주행
    /** 주행이 끝나면 평가 보여 주기 */
    val tripReport: Boolean = true,
    /** 물을 평소 운전 습관만큼 채우기 (끄면 늘 60%) */
    val habitFill: Boolean = true,
    // 시연
    /** 관리자 휴대폰 시연 받기 */
    val phoneDemo: Boolean = true,
    /** 시연 길 (engine.DemoRoute id, "" = 만든 주말 나들이) */
    val demoRoute: String = "daegwallyeong",
) {
    fun encode(): String = listOf(
        "voice" to voice, "chime" to chime, "fewerAlerts" to fewerAlerts,
        "autoVent" to autoVent, "askWindow" to askWindow, "windowPercent" to windowPercent,
        "routePreview" to routePreview, "terrain" to terrain, "briefing" to briefing,
        "tripReport" to tripReport, "habitFill" to habitFill, "phoneDemo" to phoneDemo, "demoRoute" to demoRoute,
    ).joinToString("\n") { (k, v) -> "$k=$v" }

    companion object {
        val WINDOW_CHOICES = listOf(10, 25, 40)

        fun decode(text: String?): AppSettings {
            val m = text.orEmpty().lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            val d = AppSettings()
            fun b(k: String, def: Boolean) = m[k]?.toBooleanStrictOrNull() ?: def
            return AppSettings(
                voice = b("voice", d.voice), chime = b("chime", d.chime), fewerAlerts = b("fewerAlerts", d.fewerAlerts),
                autoVent = b("autoVent", d.autoVent), askWindow = b("askWindow", d.askWindow),
                windowPercent = m["windowPercent"]?.toIntOrNull()?.coerceIn(5, 60) ?: d.windowPercent,
                routePreview = b("routePreview", d.routePreview), terrain = b("terrain", d.terrain), briefing = b("briefing", d.briefing),
                tripReport = b("tripReport", d.tripReport), habitFill = b("habitFill", d.habitFill), phoneDemo = b("phoneDemo", d.phoneDemo),
                demoRoute = m["demoRoute"] ?: d.demoRoute,
            )
        }
    }
}
