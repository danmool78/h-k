package com.sssoft.hk.server

import com.sssoft.hk.BuildConfig
import com.sssoft.hk.navi.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class StationView(
    val id: String,
    val name: String,
    val location: LatLng,
    val distanceKm: Double,
    val maxKw: Double,
    val total: Int,
    val available: Int,
    val charging: Int,
    val outOfService: Int,
    /** 현재 기준 예상 대기(분). 판단 불가면 null. */
    val waitMin: Double?,
    /** 도착 시각 기준 과거 평균 사용률 (0~1). 표본 부족이면 null. */
    val forecastBusy: Double?,
) {
    val inService: Int get() = total - outOfService
}

/** H-Y Server 충전소 조회 API. */
class ChargerApi(
    private val baseUrl: String = BuildConfig.SERVER_URL,
    /** Gleo 호출은 7초 제한이 있어 짧게, 화면은 서버 콜드 스타트를 기다릴 수 있게 길게 */
    private val readTimeoutMs: Int = 4_000,
) {

    suspend fun nearby(at: LatLng, radiusKm: Double = 5.0, limit: Int = 20, etaMin: Double? = null): List<StationView> {
        val q = "lat=${at.lat}&lng=${at.lng}&radiusKm=$radiusKm&limit=$limit" + (etaMin?.let { "&etaMin=${it.toInt()}" } ?: "")
        val body = request("GET", "/chargers/nearby?$q", null)
        return body.getJSONArray("stations").let { a -> List(a.length()) { parse(a.getJSONObject(it)) } }
    }

    /** 각 지점에 가장 가까운 충전소 (반경 밖이면 null). */
    suspend fun match(points: List<Pair<LatLng, Double?>>, radiusKm: Double = 0.3): List<StationView?> {
        val arr = JSONArray()
        for ((p, eta) in points) {
            arr.put(JSONObject().put("lat", p.lat).put("lng", p.lng).apply { eta?.let { put("etaMin", it.toInt()) } })
        }
        val body = request("POST", "/chargers/match", JSONObject().put("points", arr).put("radiusKm", radiusKm))
        val a = body.getJSONArray("matches")
        return List(a.length()) { i -> if (a.isNull(i)) null else parse(a.getJSONObject(i)) }
    }

    private fun parse(o: JSONObject) = StationView(
        id = o.getString("id"),
        name = o.getString("name"),
        location = LatLng(o.getDouble("lat"), o.getDouble("lng")),
        distanceKm = o.getDouble("distanceKm"),
        maxKw = o.getDouble("maxKw"),
        total = o.getInt("total"),
        available = o.getInt("available"),
        charging = o.getInt("charging"),
        outOfService = o.getInt("outOfService"),
        waitMin = if (o.isNull("waitMin")) null else o.getDouble("waitMin"),
        forecastBusy = o.optJSONObject("forecast")?.getDouble("busyRatio"),
    )

    private suspend fun request(method: String, path: String, json: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 3_000
            conn.readTimeout = readTimeoutMs
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
            if (code !in 200..299) error("서버 $code: ${text.take(200)}")
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
