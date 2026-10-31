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
 * - 10초 동안 새 묶음이 없으면 멈춘 것으로, 휴대폰에서 끝내면 끝난 것으로 본다.
 *   요청 한 번 실패(망 지연)로는 끊지 않는다 — 끊으면 엔진이 주행을 새로 시작해 물컵·그래프가 처음부터 다시 그려진다
 * - 휴대폰 페이지를 그냥 닫으면 서버에 "시연 중" 이 남는다. 그래서 새 세션은 가장 최근 묶음부터 받고,
 *   만들어진 지 [FRESH_SEC] 초 넘은 묶음은 버린다 (지난 시연을 다시 재생하지 않게). 시연 중 여부는 새 묶음이 오는지로.
 */
class RemoteFeed {

    /** 지금 휴대폰 시연 중인지 */
    var active = false; private set
    /** 지금(또는 마지막) 시연 세션: 같은 세션이 다시 이어지면 엔진이 주행을 새로 시작하지 않는다 */
    var session: String? = null; private set
    private var lastSeq = -1L
    private var lastBatchAt = 0L
    private var clockMs = 0L

    private val queue = ArrayDeque<RemoteSample>()
    /** 받은 묶음의 시료 간격 (초) */
    var sampleDt = 0.05; private set
    private var playing = false

    /**
     * 화면이 보이는 동안만 부른다 (앱: Activity STARTED, 웹: 화면). 취소되면 멈춘다.
     * 시연 중이 아니면 확인 간격을 2초 → 최대 10초로 늘려 쓸데없는 요청을 줄인다.
     */
    /** 설정에서 끄면 서버를 보지 않는다 */
    var enabled = true

    suspend fun run() {
        var idleMs = STATE_MS
        var sinceState = Long.MAX_VALUE / 2
        while (currentCoroutineContext().isActive) {
            if (!enabled) {
                if (active || session != null) stop()
                delay(IDLE_MAX_MS)
                continue
            }
            if (sinceState >= (if (session != null) STATE_MS else idleMs)) {
                sinceState = 0
                pollState()
                idleMs = if (session != null) STATE_MS else minOf(idleMs * 2, IDLE_MAX_MS)
            }
            if (session != null) pollBatches()
            delay(BATCH_MS)
            sinceState += BATCH_MS
            clockMs += BATCH_MS
            if (active && clockMs - lastBatchAt > STALE_MS) pause()
        }
    }

    /**
     * 엔진이 [dt] 초 동안 쓸 가속도 (앞뒤, 좌우 m/s²). 받은 시료를 순서대로 꺼낸다.
     * 1초쯤 쌓인 뒤에 재생을 시작하고(망 지연 흡수), 너무 밀리면 오래된 것을 버린다.
     */
    fun take(dt: Double): List<RemoteSample> {
        if (!playing && queue.size >= START_BUFFER) playing = true
        if (!playing) return emptyList()
        while (queue.size > MAX_BUFFER) queue.removeFirst()
        acc += dt
        val out = ArrayList<RemoteSample>()
        while (acc >= sampleDt && queue.isNotEmpty()) {
            out += queue.removeFirst()
            acc -= sampleDt
        }
        if (queue.isEmpty()) { playing = false; acc = 0.0 }
        return out
    }
    private var acc = 0.0

    /** 새 묶음이 끊김: 세션과 받은 순번은 두고 재생만 멈춘다 (다시 오면 이어 받는다) */
    private fun pause() {
        active = false
        queue.clear()
        playing = false
    }

    private fun stop() {
        active = false
        session = null
        lastSeq = -1
        queue.clear()
        playing = false
    }

    private suspend fun pollState() {
        // 실패는 넘긴다 (끝남은 [STALE_MS] 로 판단)
        val text = httpRequest("${FirebaseWeb.DOCS}/demo/live?key=${FirebaseWeb.API_KEY}") ?: return
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
            // 가장 최근 묶음 바로 앞부터 (지난 묶음을 처음부터 받지 않게). active 는 새 묶음이 와야 켠다
            lastSeq = latestSeq(s)?.let { it - 1 } ?: -1
        }
    }

    private suspend fun latestSeq(s: String): Long? {
        val body = """{"structuredQuery":{"from":[{"collectionId":"$s"}],"orderBy":[{"field":{"fieldPath":"seq"},"direction":"DESCENDING"}],"limit":1}}"""
        val text = httpRequest("${FirebaseWeb.DOCS}/demo/live:runQuery?key=${FirebaseWeb.API_KEY}", "POST", body) ?: return null
        return runCatching {
            Json.parseToJsonElement(text).jsonArray.firstNotNullOfOrNull { r ->
                r.jsonObject["document"]?.jsonObject?.get("fields")?.jsonObject?.get("seq")?.let { num(it) }?.toLong()
            }
        }.getOrNull()
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
            val docu = row.jsonObject["document"]?.jsonObject ?: continue
            val f = docu["fields"]?.jsonObject ?: continue
            val seq = f["seq"]?.let { num(it) }?.toLong() ?: continue
            if (seq <= lastSeq) continue
            lastSeq = seq
            // 지난 시연의 묶음 (서버 시각으로 만들어진 지 오래됨) 은 건너뛴다
            val made = docu["createTime"]?.jsonPrimitive?.content?.let { isoSec(it) }
            val read = row.jsonObject["readTime"]?.jsonPrimitive?.content?.let { isoSec(it) }
            if (made != null && read != null && read - made > FRESH_SEC) continue
            f["dt"]?.let { num(it) }?.let { if (it in 0.01..0.5) sampleDt = it }
            val ax = f["ax"]?.let { arr(it) } ?: continue
            val ay = f["ay"]?.let { arr(it) } ?: continue
            // 위아래는 새 휴대폰 페이지부터 (없으면 0)
            val az = f["az"]?.let { arr(it) }
            // 휴대폰 기울기 = 길 경사 (rad): 위쪽이 들림, 왼쪽이 들림
            val px = f["px"]?.let { num(it) } ?: 0.0
            val py = f["py"]?.let { num(it) } ?: 0.0
            // 손으로 세게 흔들면 수십 m/s² 가 나온다: 차가 낼 수 있는 가속(급제동 약 0.8g)까지만
            for (k in 0 until minOf(ax.size, ay.size)) {
                val z = az?.getOrNull(k) ?: 0.0
                queue.addLast(RemoteSample(ax[k].coerceIn(-MAX_ACC, MAX_ACC), ay[k].coerceIn(-MAX_ACC, MAX_ACC), z.coerceIn(-MAX_ACC, MAX_ACC), px.coerceIn(-MAX_GRADE, MAX_GRADE), py.coerceIn(-MAX_GRADE, MAX_GRADE)))
            }
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
        /** 받는 기울기 한도 (rad, 약 17도: 아주 가파른 길) */
        const val MAX_GRADE = 0.3
        const val STALE_MS = 10_000L
        /** 이보다 오래된 묶음은 지난 시연으로 보고 버린다 (초) */
        const val FRESH_SEC = 20.0
        const val START_BUFFER = 20
        const val MAX_BUFFER = 120

        /** "2026-10-30T09:51:10.123456Z" → 초 (UTC). 모르는 모양이면 null */
        fun isoSec(s: String): Double? {
            if (s.length < 20 || s[4] != '-' || s[10] != 'T') return null
            val y = s.substring(0, 4).toIntOrNull() ?: return null
            val mo = s.substring(5, 7).toIntOrNull() ?: return null
            val d = s.substring(8, 10).toIntOrNull() ?: return null
            val h = s.substring(11, 13).toIntOrNull() ?: return null
            val mi = s.substring(14, 16).toIntOrNull() ?: return null
            val sec = s.substring(17).trimEnd('Z').toDoubleOrNull() ?: return null
            // 그레고리력 날짜 → 1970-01-01 부터 날 수
            val yy = if (mo <= 2) y - 1 else y
            val era = yy / 400
            val yoe = yy - era * 400
            val doy = (153 * (mo + (if (mo > 2) -3 else 9)) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            val days = era * 146097L + doe - 719468
            return days * 86400.0 + h * 3600 + mi * 60 + sec
        }

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

/** 휴대폰에서 받은 시료 하나 (차 기준 m/s²): 앞뒤 + 가속, 좌우 + 왼쪽으로 도는 중, 위아래 + 위로 */
class RemoteSample(val ax: Double, val ay: Double, val az: Double = 0.0, val gradeX: Double = 0.0, val gradeY: Double = 0.0)
