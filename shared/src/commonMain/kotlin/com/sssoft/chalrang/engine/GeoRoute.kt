package com.sssoft.chalrang.engine

import com.sssoft.chalrang.core.TerrainGrid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 실제 도로 경로 (출발점 기준 m: x 동, y 북) 와 높이, 주변 지형 */
class GeoRoute(
    val name: String?,
    val xs: DoubleArray,
    val ys: DoubleArray,
    /** 경로를 따라 잰 높이 (m), [xs] 와 같은 점마다 */
    val zs: DoubleArray,
    val grid: TerrainGrid?,
    val durationSec: Double?,
)

/**
 * 실제 지역의 길: OSRM(오픈스트리트맵 길찾기, 키 없음) 으로 도로 경로 선을 받고,
 * Open-Meteo 고도(Copernicus DEM 90m) 로 경로 높이와 주변 지형 격자를 잰다.
 * 경로마다 한 번만 부른다 (길찾기 1번 + 고도 몇 번).
 */
object GeoFetch {
    private const val OSRM = "https://router.project-osrm.org/route/v1/driving"
    private const val ELEV = "https://api.open-meteo.com/v1/elevation"
    /** 고도 API 한 번에 잴 수 있는 곳 */
    private const val BATCH = 100
    /** 경로 높이를 재는 최대 곳 수 */
    private const val ROUTE_SAMPLES = 200
    /** 주변 지형: 경로 둘레 여유 (m), 격자 칸 수 한도 */
    private const val MARGIN = 1_500.0
    private const val GRID_MAX = 34

    suspend fun route(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, name: String? = null, grid: Boolean = true): GeoRoute? {
        val text = httpRequest("$OSRM/${r6(fromLng)},${r6(fromLat)};${r6(toLng)},${r6(toLat)}?overview=full&geometries=geojson") ?: return null
        val (coords, dur) = parseOsrm(text) ?: return null
        if (coords.size < 2) return null
        val lat0 = coords[0].first
        val lng0 = coords[0].second
        val kx = 111_320.0 * cos(lat0 * PI / 180)
        val ky = 110_540.0
        val xs = DoubleArray(coords.size) { (coords[it].second - lng0) * kx }
        val ys = DoubleArray(coords.size) { (coords[it].first - lat0) * ky }
        // 경로 높이: 거리로 고르게 나눈 곳에서 재고 점마다 보간
        val cum = DoubleArray(coords.size)
        for (i in 1 until coords.size) cum[i] = cum[i - 1] + sqrt((xs[i] - xs[i - 1]).let { it * it } + (ys[i] - ys[i - 1]).let { it * it })
        val total = cum.last()
        val n = min(ROUTE_SAMPLES, max(2, (total / 40).toInt()))
        val sLat = DoubleArray(n)
        val sLng = DoubleArray(n)
        var j = 0
        for (k in 0 until n) {
            val d = total * k / (n - 1)
            while (j < cum.size - 2 && cum[j + 1] < d) j++
            val t = if (cum[j + 1] > cum[j]) (d - cum[j]) / (cum[j + 1] - cum[j]) else 0.0
            sLat[k] = coords[j].first + (coords[j + 1].first - coords[j].first) * t
            sLng[k] = coords[j].second + (coords[j + 1].second - coords[j].second) * t
        }
        val sh = heights(sLat, sLng) ?: return null
        val zs = DoubleArray(coords.size) { i ->
            val f = (cum[i] / total * (n - 1)).coerceIn(0.0, (n - 1).toDouble())
            val a = f.toInt()
            val b = min(a + 1, n - 1)
            sh[a] + (sh[b] - sh[a]) * (f - a)
        }
        // 주변 지형 격자
        val g = if (!grid) null else {
            val minX = xs.min() - MARGIN
            val maxX = xs.max() + MARGIN
            val minY = ys.min() - MARGIN
            val maxY = ys.max() + MARGIN
            val step = max(200.0, max(maxX - minX, maxY - minY) / GRID_MAX)
            val nx = ((maxX - minX) / step).toInt() + 1
            val ny = ((maxY - minY) / step).toInt() + 1
            val gLat = DoubleArray(nx * ny) { lat0 + (minY + (it / nx) * step) / ky }
            val gLng = DoubleArray(nx * ny) { lng0 + (minX + (it % nx) * step) / kx }
            heights(gLat, gLng)?.let { TerrainGrid(minX, minY, step, nx, ny, it) }
        }
        return GeoRoute(name, xs, ys, zs, g, dur)
    }

    /** 고도를 [BATCH] 곳씩 나눠 잰다. 하나라도 실패하면 null */
    private suspend fun heights(lat: DoubleArray, lng: DoubleArray): DoubleArray? {
        val out = DoubleArray(lat.size)
        var i = 0
        while (i < lat.size) {
            val end = min(lat.size, i + BATCH)
            val la = StringBuilder()
            val lo = StringBuilder()
            for (k in i until end) {
                if (k > i) { la.append(','); lo.append(',') }
                la.append(r5(lat[k])); lo.append(r5(lng[k]))
            }
            val text = httpRequest("$ELEV?latitude=$la&longitude=$lo") ?: return null
            val arr = runCatching { Json.parseToJsonElement(text).jsonObject["elevation"]?.jsonArray }.getOrNull() ?: return null
            if (arr.size != end - i) return null
            for (k in i until end) out[k] = arr[k - i].jsonPrimitive.doubleOrNull ?: 0.0
            i = end
        }
        return out
    }

    /** OSRM 응답 → (위도, 경도) 목록과 예상 시간 */
    internal fun parseOsrm(text: String): Pair<List<Pair<Double, Double>>, Double?>? = runCatching {
        val route = Json.parseToJsonElement(text).jsonObject["routes"]!!.jsonArray[0].jsonObject
        val coords = route["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { c ->
            val p = c.jsonArray
            p[1].jsonPrimitive.doubleOrNull!! to p[0].jsonPrimitive.doubleOrNull!!
        }
        coords to route["duration"]?.jsonPrimitive?.doubleOrNull
    }.getOrNull()

    private fun r5(v: Double): String = ((v * 100_000).toLong() / 100_000.0).toString()
    private fun r6(v: Double): String = ((v * 1_000_000).toLong() / 1_000_000.0).toString()
}
