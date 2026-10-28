package com.sssoft.hk.server

import com.sssoft.hk.BuildConfig
import com.sssoft.hk.model.Weather
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
    /** 고속도로 휴게소 등 경로에서 바로 들어가는 곳 (우회 시간 짧음) */
    val highway: Boolean = false,
) {
    val inService: Int get() = total - outOfService
}

data class RouteWeather(val tempC: Double, val weather: Weather)

/** 도로 경로상 충전소 하나. */
data class RouteStation(
    val view: StationView,
    /** 출발부터 경로를 따라 이 충전소 옆까지 (km) */
    val atKm: Double,
    val offRouteKm: Double,
    /** 들렀다 돌아오는 시간 평균 (분) */
    val detourMin: Double,
    /** 출발 기준 도착 예정 (분) */
    val etaMin: Double,
    /** 진행 방향 고속도로 휴게소 */
    val restArea: Boolean,
)

/** 실제 도로 경로(OSRM)와 경로를 따라 있는 충전소 후보. */
data class RouteChargers(
    val distanceKm: Double,
    val durationMin: Double,
    /** 약 2km 간격 경로 좌표 */
    val path: List<LatLng>,
    val stations: List<RouteStation>,
) {
    /** 좌표 간격이 고르지 않으므로(굽은 구간이 촘촘) 누적 거리로 찾는다 */
    private val cumKm: DoubleArray by lazy {
        val c = DoubleArray(path.size)
        for (i in 1 until path.size) {
            val a = path[i - 1]
            val b = path[i]
            c[i] = c[i - 1] + kotlin.math.hypot((b.lat - a.lat) * 111.0, (b.lng - a.lng) * 111.0 * kotlin.math.cos(Math.toRadians(a.lat)))
        }
        c
    }

    /** 경로 위 거리 비율 [r] (0~1) 지점 */
    fun pointAt(r: Double): LatLng {
        val target = cumKm.last() * r.coerceIn(0.0, 1.0)
        val i = cumKm.indexOfFirst { it >= target }.coerceAtLeast(0)
        return path[i]
    }
}

/** H-Y Server 충전소·날씨 조회 API. */
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

    /** 실제 도로 경로와 경로상 충전소 후보 (서버가 OSRM 경로를 따라 찾음). */
    suspend fun routeChargers(origin: LatLng, dest: LatLng, departOffsetMin: Double = 0.0): RouteChargers {
        val body = request(
            "POST", "/route/chargers",
            JSONObject()
                .put("origin", JSONObject().put("lat", origin.lat).put("lng", origin.lng))
                .put("dest", JSONObject().put("lat", dest.lat).put("lng", dest.lng))
                .put("departOffsetMin", departOffsetMin),
        )
        val r = body.getJSONObject("route")
        val p = r.getJSONArray("path")
        val c = body.getJSONArray("chargers")
        return RouteChargers(
            distanceKm = r.getDouble("distanceKm"),
            durationMin = r.getDouble("durationMin"),
            path = List(p.length()) { p.getJSONArray(it).let { xy -> LatLng(xy.getDouble(0), xy.getDouble(1)) } },
            stations = List(c.length()) { i ->
                val o = c.getJSONObject(i)
                RouteStation(
                    parse(o), o.getDouble("atKm"), o.getDouble("offRouteKm"), o.getDouble("detourMin"),
                    o.getDouble("etaMin"), o.optBoolean("restArea", false),
                )
            },
        )
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

    /** 경로 지점별 (위치, 도착까지 분) 의 날씨 → 경로 대표 기온·날씨. Open-Meteo (서버 경유). */
    suspend fun routeWeather(points: List<Pair<LatLng, Double>>): RouteWeather {
        val arr = JSONArray()
        for ((p, eta) in points) arr.put(JSONObject().put("lat", p.lat).put("lng", p.lng).put("etaMin", eta.toInt()))
        val r = request("POST", "/weather/route", JSONObject().put("points", arr)).getJSONObject("route")
        val kind = runCatching { Weather.valueOf(r.getString("kind")) }.getOrDefault(Weather.CLEAR)
        return RouteWeather(r.getDouble("tempC"), kind)
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
        highway = o.optBoolean("highway", false),
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
