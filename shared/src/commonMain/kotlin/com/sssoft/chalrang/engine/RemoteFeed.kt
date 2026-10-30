package com.sssoft.chalrang.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 웹 공개 설정 (Firebase 웹 앱 설정과 같은 값, 비밀 아님. 쓰기는 Firestore 규칙이 관리자만 허용) */
object FirebaseWeb {
    const val PROJECT = "h-y-server"
    const val API_KEY = "AIzaSyAC-If99_ZU2DoU4VTxzBtwRchGVg2JRqQ"
    const val DOCS = "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents"
}

/** 플랫폼 HTTP. 실패하면 null (앱: HttpURLConnection, 웹: fetch) */
expect suspend fun httpRequest(url: String, method: String = "GET", body: String? = null): String?

/**
 * 휴대폰 시연: 관리자가 휴대폰(/phone/)에서 시연을 시작하고 흔들면, 휴대폰 가속도가 0.5초 묶음으로
 * Firestore `demo/live/{세션}/{순번}` 에 올라온다. 여기서는 그것을 받아 차 흔들림 대신 물컵에 넣는다.
 *
 * - 2초마다 `demo/live` 를 보고 시연 중인지 안다
 * - 시연 중이면 0.4초마다 새 묶음을 받는다 (Firestore REST, 라이브러리 없이)
 * - 10초 동안 새 묶음이 없으면 끝난 것으로 본다
 */
class RemoteFeed {

    /** 지금 휴대폰 시연 중인지 */
    var active = false; private set
    private var session: String? = null
    private var lastSeq = -1L
    private var lastBatchAt = 0L
    private var clockMs = 0L

    private val queue = ArrayDeque<Pair<Double, Double>>()
    /** 받은 묶음의 시료 간격 (초) */
    var sampleDt = 0.05; private set
    private var playing = false

    /**
     * 화면이 보이는 동안만 부른다 (앱: Activity STARTED, 웹: 화면). 취소되면 멈춘다.
     * 시연 중이 아니면 확인 간격을 2초 → 최대 10초로 늘려 쓸데없는 요청을 줄인다.
     */
    suspend fun run() {
        var idleMs = STATE_MS
        var sinceState = Long.MAX_VALUE / 2
        while (currentCoroutineContext().isActive) {
            if (sinceState >= (if (session != null) STATE_MS else idleMs)) {
                sinceState = 0
                pollState()
                idleMs = if (session != null) STATE_MS else minOf(idleMs * 2, IDLE_MAX_MS)
            }
            if (session != null) pollBatches()
            delay(BATCH_MS)
            sinceState += BATCH_MS
            clockMs += BATCH_MS
            if (active && clockMs - lastBatchAt > STALE_MS) stop()
        }
    }

    /**
     * 엔진이 [dt] 초 동안 쓸 가속도 (앞뒤, 좌우 m/s²). 받은 시료를 순서대로 꺼낸다.
     * 1초쯤 쌓인 뒤에 재생을 시작하고(망 지연 흡수), 너무 밀리면 오래된 것을 버린다.
     */
    fun take(dt: Double): List<Pair<Double, Double>> {
        if (!playing && queue.size >= START_BUFFER) playing = true
        if (!playing) return emptyList()
        while (queue.size > MAX_BUFFER) queue.removeFirst()
        acc += dt
        val out = ArrayList<Pair<Double, Double>>()
        while (acc >= sampleDt && queue.isNotEmpty()) {
            out += queue.removeFirst()
            acc -= sampleDt
        }
        if (queue.isEmpty()) { playing = false; acc = 0.0 }
        return out
    }
    private var acc = 0.0

    private fun stop() {
        active = false
        session = null
        lastSeq = -1
        queue.clear()
        playing = false
    }

    private suspend fun pollState() {
        val text = httpRequest("${FirebaseWeb.DOCS}/demo/live?key=${FirebaseWeb.API_KEY}") ?: run { if (active) stop(); return }
        val fields = runCatching { Json.parseToJsonElement(text).jsonObject["fields"]?.jsonObject }.getOrNull() ?: return
        val on = fields["active"]?.let { bool(it) } == true
        val s = fields["session"]?.let { str(it) }
        if (!on || s == null) {
            if (active) stop()
            return
        }
        if (s != session) {
            stop()
            session = s
            active = true
            lastBatchAt = clockMs
        }
    }

    private suspend fun pollBatches() {
        val s = session ?: return
        val body = """{"structuredQuery":{"from":[{"collectionId":"$s"}],""" +
            """"where":{"fieldFilter":{"field":{"fieldPath":"seq"},"op":"GREATER_THAN","value":{"integerValue":"$lastSeq"}}},""" +
            """"orderBy":[{"field":{"fieldPath":"seq"}}],"limit":40}}"""
        val text = httpRequest("${FirebaseWeb.DOCS}/demo/live:runQuery?key=${FirebaseWeb.API_KEY}", "POST", body) ?: return
        accept(text)
    }

    /** Firestore runQuery 응답(묶음 목록)을 받아 시료를 쌓는다 */
    internal fun accept(text: String) {
        val rows = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return
        for (row in rows) {
            val f = row.jsonObject["document"]?.jsonObject?.get("fields")?.jsonObject ?: continue
            val seq = f["seq"]?.let { num(it) }?.toLong() ?: continue
            if (seq <= lastSeq) continue
            lastSeq = seq
            f["dt"]?.let { num(it) }?.let { if (it in 0.01..0.5) sampleDt = it }
            val ax = f["ax"]?.let { arr(it) } ?: continue
            val ay = f["ay"]?.let { arr(it) } ?: continue
            // 손으로 세게 흔들면 수십 m/s² 가 나온다: 차가 낼 수 있는 가속(급제동 약 0.8g)까지만
            for (k in 0 until minOf(ax.size, ay.size)) queue.addLast(ax[k].coerceIn(-MAX_ACC, MAX_ACC) to ay[k].coerceIn(-MAX_ACC, MAX_ACC))
            lastBatchAt = clockMs
            active = true
        }
    }

    companion object {
        const val STATE_MS = 2_000L
        const val IDLE_MAX_MS = 10_000L
        const val BATCH_MS = 400L
        /** 받는 가속도 한도 (m/s²) */
        const val MAX_ACC = 8.0
        const val STALE_MS = 10_000L
        const val START_BUFFER = 20
        const val MAX_BUFFER = 120

        // Firestore REST 값 꺼내기
        private fun value(e: JsonElement): JsonObject = e.jsonObject
        fun bool(e: JsonElement): Boolean? = value(e)["booleanValue"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        fun str(e: JsonElement): String? = value(e)["stringValue"]?.jsonPrimitive?.content
        fun num(e: JsonElement): Double? {
            val v = value(e)
            return (v["doubleValue"] ?: v["integerValue"])?.jsonPrimitive?.content?.toDoubleOrNull()
        }
        fun arr(e: JsonElement): List<Double>? =
            (value(e)["arrayValue"]?.jsonObject?.get("values") as? JsonArray)?.map { num(it) ?: 0.0 }
                ?: if (value(e).containsKey("arrayValue")) emptyList() else null
    }
}
