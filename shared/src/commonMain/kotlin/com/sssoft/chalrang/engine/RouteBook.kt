package com.sssoft.chalrang.engine

import com.sssoft.chalrang.core.TerrainGrid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong

/** 저장해 둔 길 하나 (목록에 적는 것: 어디서 어디로, 즐겨찾기인지, 언제 썼는지) */
data class SavedRoute(
    /** 저장 이름 (길 자료를 이 이름으로 따로 둔다) */
    val key: String,
    /** 목적지 이름 (내비) */
    val name: String?,
    val startLat: Double,
    val startLng: Double,
    val destLat: Double,
    val destLng: Double,
    val favorite: Boolean,
    /** 쓴 순서 (클수록 최근) */
    val used: Long,
)

/**
 * 즐겨찾기와 최근 길: 분석한 길(도로 선·높이·지형)과 위험 구간 지형을 기기에 저장해 두고,
 * 같은 출발지(약 [START_M] 안)에서 같은 목적지(약 [DEST_M] 안)로 가면 인터넷 없이 바로 쓴다.
 * 즐겨찾기는 [FAVORITES] 곳까지 계속 두고, 나머지는 최근 [RECENT] 곳만 남긴다.
 */
object RouteBook {
    const val RECENT = 5
    const val FAVORITES = 10
    const val START_M = 1_000.0
    const val DEST_M = 300.0

    /** 두 위경도 사이 거리 (m, 가까운 거리용) */
    fun distM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val kx = 111_320.0 * cos((lat1 + lat2) / 2 * PI / 180)
        return hypot((lng2 - lng1) * kx, (lat2 - lat1) * 110_540.0)
    }

    /** 지금 출발지·목적지에 맞는 저장된 길 (가장 최근 것) */
    fun find(list: List<SavedRoute>, hereLat: Double, hereLng: Double, destLat: Double, destLng: Double): SavedRoute? =
        list.filter {
            distM(it.destLat, it.destLng, destLat, destLng) <= DEST_M && distM(it.startLat, it.startLng, hereLat, hereLng) <= START_M
        }.maxByOrNull { it.used }

    /**
     * [r] 을 방금 쓴 것으로 넣는다 (같은 key 는 바꿔 넣음). 즐겨찾기가 아닌 것은 최근 [RECENT] 곳만,
     * 즐겨찾기도 [FAVORITES] 곳까지만 남긴다. 돌려주는 것: 새 목록, 지워야 할 길 자료 key.
     */
    fun touch(list: List<SavedRoute>, r: SavedRoute): Pair<List<SavedRoute>, List<String>> {
        val next = (list.maxOfOrNull { it.used } ?: 0) + 1
        val all = list.filter { it.key != r.key } + r.copy(used = next)
        return prune(all)
    }

    /** 즐겨찾기 켜고 끄기 */
    fun setFavorite(list: List<SavedRoute>, key: String, on: Boolean): Pair<List<SavedRoute>, List<String>> =
        prune(list.map { if (it.key == key) it.copy(favorite = on) else it })

    private fun prune(all: List<SavedRoute>): Pair<List<SavedRoute>, List<String>> {
        val fav = all.filter { it.favorite }.sortedByDescending { it.used }.take(FAVORITES)
        val recent = all.filter { !it.favorite }.sortedByDescending { it.used }.take(RECENT)
        val keep = (fav + recent).sortedBy { it.used }
        val keepKeys = keep.map { it.key }.toSet()
        return keep to all.map { it.key }.filter { it !in keepKeys }
    }

    /** 새 key: 목적지 위경도로 (같은 목적지라도 출발지가 다르면 다른 길이라 출발지도) */
    fun keyOf(startLat: Double, startLng: Double, destLat: Double, destLng: Double): String =
        "r" + listOf(startLat, startLng, destLat, destLng).joinToString("_") { ((it * 1000).roundToLong()).toString() }

    // ---- 목록 (줄마다 탭으로) ----

    fun encodeList(list: List<SavedRoute>): String = list.joinToString("\n") {
        listOf(it.key, (it.name ?: "").replace('\t', ' ').replace('\n', ' '), it.startLat, it.startLng, it.destLat, it.destLng, if (it.favorite) 1 else 0, it.used)
            .joinToString("\t")
    }

    fun decodeList(text: String?): List<SavedRoute> = text.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val p = line.split('\t')
        runCatching {
            SavedRoute(p[0], p[1].ifEmpty { null }, p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), p[6] == "1", p[7].toLong())
        }.getOrNull()
    }

    // ---- 길 자료 (도로 선·높이·지형 + 위험 구간 지형) ----

    class Payload(val route: GeoRoute, val details: Map<String, TerrainGrid>)

    fun encode(route: GeoRoute, details: Map<String, TerrainGrid>): String = buildJsonObject {
        route.name?.let { put("name", it) }
        put("lat0", route.lat0)
        put("lng0", route.lng0)
        route.durationSec?.let { put("dur", it) }
        put("x", nums(route.xs))
        put("y", nums(route.ys))
        put("z", nums(route.zs))
        route.grid?.let { put("grid", gridJson(it)) }
        put("details", JsonObject(details.mapValues { gridJson(it.value) }))
    }.toString()

    fun decode(text: String?): Payload? = text?.let {
        runCatching {
            val o = Json.parseToJsonElement(it).jsonObject
            fun arr(k: String) = o[k]!!.jsonArray.let { a -> DoubleArray(a.size) { i -> a[i].jsonPrimitive.double } }
            val route = GeoRoute(
                name = o["name"]?.jsonPrimitive?.content,
                xs = arr("x"), ys = arr("y"), zs = arr("z"),
                grid = o["grid"]?.jsonObject?.let(::gridOf),
                durationSec = o["dur"]?.jsonPrimitive?.doubleOrNull,
                lat0 = o["lat0"]!!.jsonPrimitive.double,
                lng0 = o["lng0"]!!.jsonPrimitive.double,
            )
            val details = o["details"]?.jsonObject?.mapValues { (_, v) -> gridOf(v.jsonObject) }.orEmpty()
            Payload(route, details)
        }.getOrNull()
    }

    /** 0.1m 까지만 (글이 짧게) */
    private fun nums(a: DoubleArray) = JsonArray(a.map { JsonPrimitive((it * 10).roundToLong() / 10.0) })

    private fun gridJson(g: TerrainGrid) = buildJsonObject {
        put("x0", g.x0); put("y0", g.y0); put("step", g.step); put("nx", g.nx); put("ny", g.ny)
        put("h", nums(g.h))
    }

    private fun gridOf(o: JsonObject): TerrainGrid {
        val h = o["h"]!!.jsonArray
        return TerrainGrid(
            o["x0"]!!.jsonPrimitive.double, o["y0"]!!.jsonPrimitive.double, o["step"]!!.jsonPrimitive.double,
            o["nx"]!!.jsonPrimitive.int, o["ny"]!!.jsonPrimitive.int, DoubleArray(h.size) { h[it].jsonPrimitive.double },
        )
    }
}
