package com.sssoft.hk.vehicle

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

data class BatteryReading(val socPercent: Int, val capacityKwh: Double?)

/** 백그라운드(Gleo 호출 등)에서 배터리 상태를 한 번 읽는다. */
object BatteryReader {

    suspend fun read(context: Context, timeoutMs: Long = 1_500): BatteryReading? {
        val vehicle = Vehicle(context.applicationContext)
        return try {
            vehicle.initialize()
            val battery = vehicle.getEvBattery()
            val soc = CompletableDeferred<Int?>()
            val cap = CompletableDeferred<Double?>()
            battery.getEvBatteryStateOfCharge({ soc.complete(it) }, { soc.complete(null) })
            battery.getEvBatteryCapacity(
                { c -> cap.complete(c?.let { if (it > 1000f) it / 1000.0 else it.toDouble() }) },
                { cap.complete(null) },
            )
            withTimeoutOrNull(timeoutMs) {
                val s = soc.await() ?: return@withTimeoutOrNull null
                BatteryReading(s, withTimeoutOrNull(300) { cap.await() })
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { vehicle.release() }
        }
    }
}
