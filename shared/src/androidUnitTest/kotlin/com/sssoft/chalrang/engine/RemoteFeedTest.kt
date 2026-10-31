package com.sssoft.chalrang.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFeedTest {

    /** Firestore REST runQuery 응답 모양 (휴대폰 페이지가 쓴 묶음 두 개 + 빈 줄) */
    private fun batch(seq: Int, ax: List<Double>, ay: List<Double>, az: List<Double>? = null, px: Double? = null, made: String? = null, read: String = "t"): String {
        fun arr(v: List<Double>) = v.joinToString(",") { if (it % 1.0 == 0.0) """{"integerValue":"${it.toLong()}"}""" else """{"doubleValue":$it}""" }
        val extra = (az?.let { ""","az":{"arrayValue":{"values":[${arr(it)}]}}""" } ?: "") + (px?.let { ""","px":{"doubleValue":$it}""" } ?: "")
        val ct = made?.let { ""","createTime":"$it"""" } ?: ""
        return """{"document":{"name":"x"$ct,"fields":{"seq":{"integerValue":"$seq"},"dt":{"doubleValue":0.05},""" +
            """"ax":{"arrayValue":{"values":[${arr(ax)}]}},"ay":{"arrayValue":{"values":[${arr(ay)}]}}$extra}},"readTime":"$read"}"""
    }

    @Test
    fun `위아래·기울기를 받고, 너무 센 값은 자른다`() {
        val feed = RemoteFeed()
        val ten = List(10) { 0.0 }
        feed.accept("[" + batch(1, List(10) { 30.0 }, ten, List(10) { 1.5 }, px = 0.05) + "," + batch(2, ten, ten) + "]")
        val got = feed.take(1.0)
        assertEquals(RemoteFeed.MAX_ACC, got[0].ax, 1e-9)
        assertEquals(1.5, got[0].az, 1e-9)
        assertEquals(0.05, got[0].gradeX, 1e-9)
        assertEquals("예전 페이지(위아래 없음)는 0", 0.0, got[15].az, 1e-9)
    }

    @Test
    fun `묶음을 읽어 1초 쌓인 뒤 순서대로 내준다`() {
        val feed = RemoteFeed()
        val ten = List(10) { it * 0.1 }
        feed.accept("[" + batch(1, ten, ten.map { -it }) + "," + batch(2, ten.map { it + 1 }, ten) + """,{"readTime":"t"}]""")
        assertTrue(feed.active)
        // 20개(1초) 쌓였으니 재생 시작: 0.1초 동안 2개
        val first = feed.take(0.1)
        assertEquals(2, first.size)
        assertEquals(0.0, first[0].ax, 1e-9)
        assertEquals(-0.1, first[1].ay, 1e-9)
        // 이미 받은 순번은 다시 받지 않는다
        feed.accept("[" + batch(2, ten, ten) + "]")
        val rest = feed.take(10.0)
        assertEquals(18, rest.size)
        assertEquals(1.9, rest.last().ax, 1e-9)
    }

    @Test
    fun `덜 쌓였으면 기다린다, 깨진 응답은 무시`() {
        val feed = RemoteFeed()
        feed.accept("[" + batch(1, listOf(1.0, 2.0), listOf(0.0, 0.0)) + "]")
        assertTrue(feed.take(0.5).isEmpty())
        feed.accept("{oops")
        feed.accept("""[{"readTime":"t"}]""")
        assertTrue(feed.take(0.5).isEmpty())
    }

    @Test
    fun `지난 시연의 오래된 묶음은 재생하지 않는다`() {
        val feed = RemoteFeed()
        val ten = List(10) { 1.0 }
        val now = "2026-10-31T01:00:00.000Z"
        feed.accept("[" + (1..3).joinToString(",") { batch(it, ten, ten, made = "2026-10-30T09:51:1$it.5Z", read = now) } + "]")
        assertTrue("어제 묶음은 버림", !feed.active)
        assertTrue(feed.take(1.0).isEmpty())
        feed.accept("[" + (4..5).joinToString(",") { batch(it, ten, ten, made = "2026-10-31T00:59:59.123456Z", read = now) } + "]")
        assertTrue("방금 묶음은 받음", feed.active)
        assertEquals(20, feed.take(2.0).size)
    }

    @Test
    fun `시각 읽기`() {
        assertEquals(0.0, RemoteFeed.isoSec("1970-01-01T00:00:00Z")!!, 1e-9)
        assertEquals(1_793_352_000.5, RemoteFeed.isoSec("2026-10-30T09:20:00.5Z")!!, 1e-6)
        assertEquals(null, RemoteFeed.isoSec("t"))
    }
}
