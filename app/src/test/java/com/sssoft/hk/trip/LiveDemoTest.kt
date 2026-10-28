package com.sssoft.hk.trip

import com.sssoft.hk.server.ChargerApi
import com.sssoft.hk.ui.RangeViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.roundToInt

/** 실제 H-Y Server 로 데모 경로 추천을 계산. `LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*LiveDemoTest*'` */
class LiveDemoTest {

    @Test
    fun `서울-부산 데모 경로 추천`() = runBlocking {
        assumeTrue(System.getenv("LIVE") == "1")
        val cond = TripConditions(socPercent = 80, capacityKwh = null, tempC = 10.0)
        val rec = TripPlanner(ChargerApi(readTimeoutMs = 15_000)).recommend(cond, RangeViewModel.DEMO_ORIGIN, RangeViewModel.DEMO_DEST, trip = null)
        println("plan=${rec.plan::class.simpleName} checked=${rec.checkedStations} saved=${rec.savedMin?.roundToInt()}")
        println("baseline=${rec.baseline?.station?.name}")
        for (o in rec.options) {
            println(
                "  ${o.station.name} | ${o.atKm.roundToInt()}km | wait ${o.waitMin.roundToInt()}m | " +
                    "total ${o.plan.minutesP50.roundToInt()}m | free ${o.station.available}/${o.station.inService} | " +
                    "${o.station.maxKw.roundToInt()}kW | to ${(o.plan.chargeToSoc * 100).roundToInt()}%",
            )
        }
    }
}
