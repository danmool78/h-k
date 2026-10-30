package com.sssoft.chalrang.engine

import com.sssoft.chalrang.core.ManeuverKind
import com.sssoft.chalrang.core.RoutePath
import com.sssoft.chalrang.core.RoutePoint
import com.sssoft.chalrang.core.TerrainGrid
import com.sssoft.chalrang.res.Res
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** 고를 수 있는 시연 길 */
enum class DemoRoute(val id: String, val label: String, val short: String) {
    DAEGWALLYEONG("daegwallyeong", "대관령 넘어가는 길", "대관령"),
    HANGYERYEONG("hangyeryeong", "한계령 (설악로)", "한계령"),
    SEOUL("seoul", "서울 도심 (광화문 → 동대문)", "서울 도심"),
    HIGHWAY("highway", "고속도로 (판교 → 경부 → 수원IC)", "고속도로"),
    /** 인터넷·지도 없이 만든 길 (도심 → 고속도로 → 산길) */
    WEEKEND("", "주말 나들이 (만든 길)", "주말 나들이");

    companion object {
        fun of(id: String): DemoRoute = entries.firstOrNull { it.id == id } ?: DAEGWALLYEONG
    }
}

/**
 * 실제 지역 시연 길: tools/make_demo_routes.py 로 OSRM(도로 선·속도·회전)과 공개 고도 타일(높이·주변 지형)에서
 * 미리 받아 앱에 넣어 둔 것 (차 안에서 인터넷이 없어도 된다).
 */
object DemoRoutes {
    private val cache = HashMap<DemoRoute, RoutePath>()

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(r: DemoRoute): RoutePath? {
        if (r == DemoRoute.WEEKEND) return null
        cache[r]?.let { return it }
        val text = runCatching { Res.readBytes("files/routes/${r.id}.json").decodeToString() }.getOrNull() ?: return null
        return runCatching { parse(text, r) }.getOrNull()?.also { cache[r] = it }
    }

    internal fun parse(text: String, r: DemoRoute): RoutePath {
        val o = Json.parseToJsonElement(text).jsonObject
        fun arr(k: String) = o[k]!!.jsonArray.let { a -> DoubleArray(a.size) { a[it].jsonPrimitive.double } }
        val turns = o["turns"]!!.jsonArray.mapNotNull { t ->
            val a = t.jsonArray
            val kind = runCatching { ManeuverKind.valueOf(a[2].jsonPrimitive.content) }.getOrNull() ?: return@mapNotNull null
            RoutePoint(kind, a[0].jsonPrimitive.double, a[1].jsonPrimitive.int)
        }
        val city = o["kind"]?.jsonPrimitive?.content == "city"
        // 도심은 교차로 회전마다 잠깐 선다 (신호)
        val stops = if (city) turns.filter { it.kind == ManeuverKind.TURN || it.kind == ManeuverKind.UTURN }.map { it.atM } else emptyList()
        // 도로 속도 한도: OSRM 구간 속도 (도심은 시속 50km 까지)
        val v = arr("v").let { a -> if (city) DoubleArray(a.size) { minOf(a[it], 50 / 3.6) } else a }
        return RoutePath.fromPolyline(
            arr("x"), arr("y"), arr("z"), extra = turns, etaSec = o["durationSec"]?.jsonPrimitive?.double,
            region = r.label, grid = grid(o["grid"]!!.jsonObject), vLimit = v, stops = stops,
            lat0 = o["lat0"]?.jsonPrimitive?.double, lng0 = o["lng0"]?.jsonPrimitive?.double,
        )
    }

    private fun grid(g: JsonObject): TerrainGrid {
        val h = g["h"] as JsonArray
        return TerrainGrid(
            g["x0"]!!.jsonPrimitive.double, g["y0"]!!.jsonPrimitive.double, g["step"]!!.jsonPrimitive.double,
            g["nx"]!!.jsonPrimitive.int, g["ny"]!!.jsonPrimitive.int, DoubleArray(h.size) { h[it].jsonPrimitive.double },
        )
    }
}
