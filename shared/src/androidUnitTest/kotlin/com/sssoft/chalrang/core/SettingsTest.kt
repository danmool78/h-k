package com.sssoft.chalrang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    @Test
    fun `설정 저장 글을 되읽고, 모르는 값은 기본으로`() {
        val s = AppSettings(voice = false, windowPercent = 40, terrain = false, habitFill = false)
        assertEquals(s, AppSettings.decode(s.encode()))
        assertEquals(AppSettings(), AppSettings.decode(null))
        assertEquals(AppSettings(chime = false), AppSettings.decode("chime=false\nwhat=1\nvoice=maybe"))
    }

    @Test
    fun `설정을 끄면 환기·창문·주의 안내를 하지 않는다`() {
        val p = CarePolicy().apply { ventilate = false; fewerAlerts = true }
        val setup = CupSetup.choose(mapOf(Seat.REAR_LEFT to SeatState(true, true)), listOf(Child("a", "민지", Seat.REAR_LEFT)))
        val out = p.decide(CareInput(nowSec = 10.0, setup = setup, dose = 0.8, spillStarted = true, speedKmh = 40.0))
        assertFalse(out.any { it is CareAction.FreshAir || it is CareAction.AskWindow })
        assertFalse(out.any { it is CareAction.Say })
        assertTrue(out.contains(CareAction.Chime))
    }

    @Test
    fun `물을 더 채우고 지키면 도전 성공`() {
        val base = TripReport(600.0, 8.0, 90, TripGrade.CALM, 0, 0.1, 5.0, 0.2, 1.7, 0.9, emptyMap(), 0.0, "", null, emptyList())
        val won = base.copy(startWater = 0.85, endWater = 0.84, habitWater = 0.75)
        val lost = base.copy(startWater = 0.85, endWater = 0.70, habitWater = 0.75)
        assertTrue(won.challengeWon)
        assertTrue(lost.challenge && !lost.challengeWon)
        assertFalse(base.challenge)
        assertTrue(won.spoken(null).contains("도전 성공"))
    }
}
