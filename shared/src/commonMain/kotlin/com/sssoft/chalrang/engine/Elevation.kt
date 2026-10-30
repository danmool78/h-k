package com.sssoft.chalrang.engine

import com.sssoft.chalrang.core.Terrain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 가는 길 지형: Open-Meteo 고도 API (Copernicus DEM 90m, 키 없음, 한 번에 100곳).
 * 내비가 경로 선을 주지 않아 지금 위치 → 목적지 직선 위 [POINTS] 곳의 높이를 재고,
 * 경로 길이 [routeM] 에 맞춰 늘려 [Terrain] 으로 쓴다 (실제 길은 직선보다 길고 굽어 있으므로 추정).
 * 경로마다 한 번만 부른다.
 */
object Elevation {
    const val POINTS = 100
    private const val API = "https://api.open-meteo.com/v1/elevation"

    suspend fun along(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, routeM: Double): Terrain? {
        if (routeM < 1000) return null
        val lats = StringBuilder()
        val lngs = StringBuilder()
        for (i in 0 until POINTS) {
            val f = i / (POINTS - 1.0)
            if (i > 0) { lats.append(','); lngs.append(',') }
            lats.append(round5(fromLat + (toLat - fromLat) * f))
            lngs.append(round5(fromLng + (toLng - fromLng) * f))
        }
        val text = httpRequest("$API?latitude=$lats&longitude=$lngs") ?: return null
        return parse(text, routeM)
    }

    internal fun parse(text: String, routeM: Double): Terrain? {
        val arr = runCatching { Json.parseToJsonElement(text).jsonObject["elevation"]?.jsonArray }.getOrNull() ?: return null
        val h = arr.mapNotNull { it.jsonPrimitive.doubleOrNull }
        if (h.size < 2) return null
        return Terrain(routeM / (h.size - 1), h.toDoubleArray())
    }

    private fun round5(v: Double): String = ((v * 100_000).toLong() / 100_000.0).toString()
}
