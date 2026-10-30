package com.sssoft.chalrang.engine

import com.sssoft.chalrang.core.Terrain
import kotlin.math.max
import kotlin.math.min

/**
 * 도로 선을 못 받았을 때의 지형 추정: 지금 위치 → 목적지 직선 위 [POINTS] 곳의 높이를 고도 타일([TerrainTiles])에서 읽고,
 * 경로 길이 [routeM] 에 맞춰 늘려 [Terrain] 으로 쓴다 (실제 길은 직선보다 길고 굽어 있으므로 추정).
 */
object Elevation {
    const val POINTS = 100

    suspend fun along(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, routeM: Double): Terrain? {
        if (routeM < 1000) return null
        val m = TerrainTiles.mosaic(min(fromLat, toLat), min(fromLng, toLng), max(fromLat, toLat), max(fromLng, toLng), routeM / POINTS) ?: return null
        val h = DoubleArray(POINTS) { i ->
            val f = i / (POINTS - 1.0)
            m.height(fromLat + (toLat - fromLat) * f, fromLng + (toLng - fromLng) * f)
        }
        return Terrain(routeM / (POINTS - 1), h)
    }
}
