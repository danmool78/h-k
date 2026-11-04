package com.sssoft.chalrang.engine

import com.sssoft.chalrang.core.TerrainGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteBookTest {

    private fun entry(i: Int, fav: Boolean = false) =
        SavedRoute("k$i", "목적지$i", 37.5, 127.0, 37.5 + i * 0.1, 127.0, fav, 0)

    @Test
    fun `최근 길은 5곳만, 즐겨찾기는 계속 남는다`() {
        var list = emptyList<SavedRoute>()
        val removedAll = ArrayList<String>()
        // 1번을 즐겨찾기로
        list = RouteBook.touch(list, entry(1)).first
        list = RouteBook.setFavorite(list, "k1", true).first
        for (i in 2..9) {
            val (l, removed) = RouteBook.touch(list, entry(i))
            list = l
            removedAll += removed
        }
        assertTrue(list.any { it.key == "k1" && it.favorite })
        assertEquals(RouteBook.RECENT, list.count { !it.favorite })
        // 가장 오래된 최근 길(2,3,4)이 지워졌다
        assertEquals(listOf("k2", "k3", "k4"), removedAll)
        // 즐겨찾기를 끄면 그것도 최근 길 규칙을 따른다 (가장 오래돼서 지워짐)
        val (l2, removed2) = RouteBook.setFavorite(list, "k1", false)
        assertEquals(listOf("k1"), removed2)
        assertEquals(RouteBook.RECENT, l2.size)
    }

    @Test
    fun `같은 출발지 근처에서 같은 목적지로 가면 찾는다`() {
        val list = listOf(entry(1), entry(2))
        // 출발지 500m 옆, 목적지 100m 옆
        assertEquals("k2", RouteBook.find(list, 37.5045, 127.0, 37.7009, 127.0)?.key)
        // 출발지가 3km 떨어지면 다른 길
        assertNull(RouteBook.find(list, 37.527, 127.0, 37.7, 127.0))
    }

    @Test
    fun `목록과 길 자료를 글로 저장했다 되읽는다`() {
        val list = listOf(entry(1, fav = true), entry(2))
        assertEquals(list, RouteBook.decodeList(RouteBook.encodeList(list)))

        val grid = TerrainGrid(-100.0, -50.0, 40.0, 3, 2, doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.25))
        val route = GeoRoute("할머니 댁", doubleArrayOf(0.0, 10.0), doubleArrayOf(0.0, 5.0), doubleArrayOf(100.0, 101.5), grid, 600.0, 37.5, 127.0)
        val p = RouteBook.decode(RouteBook.encode(route, mapOf("1000-8000" to grid)))
        assertNotNull(p)
        p!!
        assertEquals("할머니 댁", p.route.name)
        assertEquals(101.5, p.route.zs[1], 1e-9)
        assertEquals(600.0, p.route.durationSec!!, 1e-9)
        assertEquals(3, p.details["1000-8000"]!!.nx)
        assertEquals(6.3, p.details["1000-8000"]!!.h[5], 1e-9)
    }
}
