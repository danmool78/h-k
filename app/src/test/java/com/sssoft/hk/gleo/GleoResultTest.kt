package com.sssoft.hk.gleo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GleoResultTest {

    @Test
    fun `콜백 JSON 은 Callback Schema 형식`() {
        val json = JSONObject(
            GleoResult.success("CHECK_ARRIVAL_CHANCE", "The chance is 92 percent.", "arrivalChancePercent" to 92).toJson(),
        )
        assertEquals("CHECK_ARRIVAL_CHANCE", json.getString("functionName"))
        assertEquals("success", json.getString("status"))
        assertEquals("The chance is 92 percent.", json.getString("message"))
        // data 는 key-value 객체 배열을 담은 문자열
        val data = JSONArray(json.getString("data"))
        assertEquals(92, data.getJSONObject(0).getInt("arrivalChancePercent"))
    }

    @Test
    fun `data 가 없으면 필드 생략`() {
        val json = JSONObject(GleoResult.asking("RECOMMEND_CHARGING_STOP", "Please set a destination.").toJson())
        assertEquals("asking", json.getString("status"))
        assertFalse(json.has("data"))
    }
}
