package com.sssoft.chalrang.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** 지형 타일 한 장 (Terrarium PNG, 256×256) → 높이 (m, 왼쪽 위부터 줄마다). 실패하면 null. 앱: BitmapFactory, 웹: 캔버스 */
expect suspend fun fetchTerrarium(z: Int, x: Int, y: Int): ShortArray?

/**
 * 공개 고도 타일 (AWS Terrain Tiles, Mapzen Terrarium: 높이 = R·256 + G + B/256 − 32768, 키 없음).
 * 점마다 묻지 않고 그림 몇 장을 동시에 받아 그 안에서 읽는다: 200km 길도 1~2초.
 * 필요한 간격에 맞춰 줌을 고르고(가까이 볼 위험 구간은 더 자세히), 받은 타일은 기억해 둔다 (최근 [KEEP] 장, 장당 128KB).
 */
object TerrainTiles {
    const val URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"
    private const val KEEP = 40
    private val cache = HashMap<Long, ShortArray>()
    private val order = ArrayDeque<Long>()

    private fun key(z: Int, x: Int, y: Int) = (z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()

    /** 위경도 → 그 줌의 화소 좌표 (웹 메르카토르) */
    fun px(lng: Double, z: Int) = (lng + 180) / 360 * (1 shl z) * 256

    fun py(lat: Double, z: Int): Double {
        val r = lat * PI / 180
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z) * 256
    }

    /** 그 줌에서 화소 하나의 땅 크기 (m) */
    fun pixelM(lat: Double, z: Int) = 156_543.03 * cos(lat * PI / 180) / (1 shl z)

    /** 받은 타일들로 높이를 읽는다 */
    class Mosaic(private val z: Int, private val tiles: Map<Long, ShortArray>) {
        private fun at(x: Int, y: Int): Double {
            val t = tiles[key(z, floor(x / 256.0).toInt(), floor(y / 256.0).toInt())] ?: return 0.0
            return t[(y and 255) * 256 + (x and 255)].toDouble()
        }

        fun height(lat: Double, lng: Double): Double {
            val x = px(lng, z) - 0.5
            val y = py(lat, z) - 0.5
            val x0 = floor(x).toInt()
            val y0 = floor(y).toInt()
            val fx = x - x0
            val fy = y - y0
            return (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) + (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy
        }
    }

    /**
     * 위경도 범위를 덮는 타일을 동시에 받는다. 줌은 [stepM] 간격이 보일 만큼, 그래도 [maxTiles] 장을 넘지 않게.
     * 한 장이라도 못 받으면 null.
     */
    suspend fun mosaic(minLat: Double, minLng: Double, maxLat: Double, maxLng: Double, stepM: Double, maxTiles: Int = 12): Mosaic? {
        val lat = (minLat + maxLat) / 2
        var z = 14
        while (z > 6 && pixelM(lat, z) < stepM * 0.5) z--
        fun tiles(z: Int): List<Pair<Int, Int>> {
            val x0 = floor(px(minLng, z) / 256).toInt()
            val x1 = floor(px(maxLng, z) / 256).toInt()
            val y0 = floor(py(maxLat, z) / 256).toInt()
            val y1 = floor(py(minLat, z) / 256).toInt()
            return (y0..y1).flatMap { y -> (x0..x1).map { x -> x to y } }
        }
        var need = tiles(z)
        while (need.size > maxTiles && z > 6) { z--; need = tiles(z) }
        val got = coroutineScope {
            need.map { (x, y) -> async { key(z, x, y) to tile(z, x, y) } }.awaitAll()
        }
        if (got.any { it.second == null }) return null
        return Mosaic(z, got.associate { it.first to it.second!! })
    }

    private suspend fun tile(z: Int, x: Int, y: Int): ShortArray? {
        val k = key(z, x, y)
        cache[k]?.let { return it }
        val t = fetchTerrarium(z, x, y) ?: return null
        cache[k] = t
        order.addLast(k)
        while (order.size > KEEP) cache.remove(order.removeFirst())
        return t
    }
}
