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
    /** 출발점 위경도 (x, y 의 기준) */
    val lat0: Double,
    val lng0: Double,
)

/**
 * 실제 지역의 길: OSRM(오픈스트리트맵 길찾기, 키 없음) 으로 도로 경로 선을 받고,
 * 공개 고도 타일([TerrainTiles]) 로 경로 높이와 주변 지형 격자를 읽는다.
 * 경로마다 한 번만 부른다 (길찾기 1번 + 타일 몇 장 동시에).
 */
object GeoFetch {
    private const val OSRM = "https://router.project-osrm.org/route/v1/driving"
    /** 주변 지형: 경로 둘레 여유 (m), 격자 칸 수 한도 */
    private const val MARGIN = 3_000.0
    private const val GRID_MAX = 64
    private const val MIN_STEP = 150.0

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
        // 높이: 경로 둘레를 덮는 고도 타일을 동시에 받아 그 안에서 읽는다 (점마다 묻지 않음)
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE; var minLng = Double.MAX_VALUE; var maxLng = -Double.MAX_VALUE
        for ((la, lo) in coords) { minLat = min(minLat, la); maxLat = max(maxLat, la); minLng = min(minLng, lo); maxLng = max(maxLng, lo) }
        val padLat = MARGIN / ky
        val padLng = MARGIN / kx
        val span = max((maxLat - minLat) * ky, (maxLng - minLng) * kx) + 2 * MARGIN
        val step = max(MIN_STEP, span / GRID_MAX)
        val mosaic = TerrainTiles.mosaic(minLat - padLat, minLng - padLng, maxLat + padLat, maxLng + padLng, step) ?: return null
        val zs = DoubleArray(coords.size) { mosaic.height(coords[it].first, coords[it].second) }
        val g = if (!grid) null else {
            val minX = xs.min() - MARGIN
            val minY = ys.min() - MARGIN
            val nx = ((xs.max() + MARGIN - minX) / step).toInt() + 1
            val ny = ((ys.max() + MARGIN - minY) / step).toInt() + 1
            val h = DoubleArray(nx * ny) { k -> mosaic.height(lat0 + (minY + (k / nx) * step) / ky, lng0 + (minX + (k % nx) * step) / kx) }
            TerrainGrid(minX, minY, step, nx, ny, h)
        }
        return GeoRoute(name, xs, ys, zs, g, dur, lat0, lng0)
    }

    /**
     * 위험 구간처럼 가까이 볼 곳의 자세한 지형: 출발점 기준 ([lat0], [lng0]) x·y 범위 둘레 [margin] m 를
     * [step] m 간격(칸 수는 [maxCells] 까지)으로, 더 자세한 줌의 타일에서.
     */
    suspend fun detail(lat0: Double, lng0: Double, minX: Double, minY: Double, maxX: Double, maxY: Double, margin: Double = 800.0, step: Double = 40.0, maxCells: Int = 88, maxTiles: Int = 9): TerrainGrid? {
        val kx = 111_320.0 * cos(lat0 * PI / 180)
        val ky = 110_540.0
        val x0 = minX - margin
        val y0 = minY - margin
        val st = max(step, max(maxX - minX, maxY - minY).plus(2 * margin) / maxCells)
        val nx = ((maxX + margin - x0) / st).toInt() + 1
        val ny = ((maxY + margin - y0) / st).toInt() + 1
        val m = TerrainTiles.mosaic(lat0 + y0 / ky, lng0 + x0 / kx, lat0 + (y0 + ny * st) / ky, lng0 + (x0 + nx * st) / kx, st, maxTiles = maxTiles) ?: return null
        val h = DoubleArray(nx * ny) { k -> m.height(lat0 + (y0 + (k / nx) * st) / ky, lng0 + (x0 + (k % nx) * st) / kx) }
        return TerrainGrid(x0, y0, st, nx, ny, h)
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
