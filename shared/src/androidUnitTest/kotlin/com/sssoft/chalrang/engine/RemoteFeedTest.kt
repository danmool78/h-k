package com.sssoft.chalrang.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFeedTest {

    /** Firestore REST runQuery 응답 모양 (휴대폰 페이지가 쓴 묶음 두 개 + 빈 줄) */
    private fun batch(seq: Int, ax: List<Double>, ay: List<Double>, az: List<Double>? = null, px: Double? = null): String {
        fun arr(v: List<Double>) = v.joinToString(",") { if (it % 1.0 == 0.0) """{"integerValue":"${it.toLong()}"}""" else """{"doubleValue":$it}""" }
        val extra = (az?.let { ""","az":{"arrayValue":{"values":[${arr(it)}]}}""" } ?: "") + (px?.let { ""","px":{"doubleValue":$it}""" } ?: "")
        return """{"document":{"name":"x","fields":{"seq":{"integerValue":"$seq"},"dt":{"doubleValue":0.05},""" +
            """"ax":{"arrayValue":{"values":[${arr(ax)}]}},"ay":{"arrayValue":{"values":[${arr(ay)}]}}$extra}},"readTime":"t"}"""
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
}
