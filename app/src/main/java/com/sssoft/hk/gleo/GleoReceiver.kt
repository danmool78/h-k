package com.sssoft.hk.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sssoft.hk.learn.ProfileStore
import com.sssoft.hk.model.ChargePlanResult
import com.sssoft.hk.navi.NaviSource
import com.sssoft.hk.server.ChargerApi
import com.sssoft.hk.trip.TripConditions
import com.sssoft.hk.trip.TripPlanner
import com.sssoft.hk.trip.TripPrefs
import com.sssoft.hk.vehicle.BatteryReader
import com.sssoft.hk.watch.ChargeTargetStore
import com.sssoft.hk.watch.ChargeWatchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Gleo AI 음성 호출 수신 (schema.json 의 component = "service" → sendBroadcast).
 * Gleo는 7초 후 액션을 취소하므로 6.5초 안에 콜백을 보낸다.
 */
class GleoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val fn = intent.action ?: return
        if (fn !in FUNCTIONS) return
        val params = runCatching { JSONObject(intent.getStringExtra(EXTRA_PARAM) ?: "{}") }.getOrDefault(JSONObject())
        val app = context.applicationContext
        val done = goAsync()

        scope.launch {
            val result = try {
                withTimeoutOrNull(BUDGET_MS) { handle(app, fn, params) }
                    ?: GleoResult.fail(fn, "The request timed out while checking the vehicle and charger data.")
            } catch (e: Exception) {
                Log.w(TAG, "$fn 실패", e)
                GleoResult.fail(fn, "Could not complete the request because of an internal error.")
            }
            GleoResult.send(app, result)
            done.finish()
        }
    }

    private suspend fun handle(context: Context, fn: String, params: JSONObject): GleoResult {
        val reading = BatteryReader.read(context)
            ?: return GleoResult.fail(fn, "Could not read the battery level from the vehicle.")
        val cond = TripConditions.from(reading, ProfileStore.load(context), TripPrefs.tempC(context), TripPrefs.weather(context), arrivalReserveSoc = TripPrefs.arrivalReserve(context))

        val navi = NaviSource(context)
        try {
            if (!navi.connect()) return GleoResult.fail(fn, "Could not connect to the navigation.")
            return when (fn) {
                CHECK_ARRIVAL_CHANCE -> checkArrival(navi, cond)
                RECOMMEND_CHARGING_STOP -> recommend(context, navi, cond, params.optBoolean("addAsWaypoint", false))
                else -> findNearby(navi, params.optDouble("minPowerKw", 0.0))
            }
        } finally {
            navi.release()
        }
    }

    private suspend fun checkArrival(navi: NaviSource, c: TripConditions): GleoResult {
        val dest = navi.destination()
            ?: return GleoResult.asking(CHECK_ARRIVAL_CHANCE, "No destination is set. Please set a destination in the navigation first.")
        val here = navi.currentLocation()
        val a = planner.arrival(planner.withRouteWeather(c, here, dest, dest.remainMin), here, dest)
        val p = pct(a.probability)
        val socAtArrival = pct(a.socP50.coerceAtLeast(0.0))
        val advice = when {
            a.probability >= 0.95 -> "You can reach it without charging."
            a.probability >= 0.7 -> "It is possible but risky, so a charging stop is recommended."
            else -> "You are unlikely to reach it without charging."
        }
        return GleoResult.success(
            CHECK_ARRIVAL_CHANCE,
            "The chance of reaching ${dest.name} is $p percent with about $socAtArrival percent battery left. $advice",
            "destination" to dest.name, "remainKm" to dest.remainKm.roundToInt(),
            "arrivalChancePercent" to p, "batteryAtArrivalPercent" to socAtArrival,
            "currentBatteryPercent" to c.socPercent,
        )
    }

    private suspend fun recommend(context: Context, navi: NaviSource, c: TripConditions, addAsWaypoint: Boolean): GleoResult {
        // 주행 중 "바꿀까요?" 제안에 "바꿔줘"로 답한 경우
        if (addAsWaypoint && ChargeWatchService.hasPendingOffer()) {
            ChargeWatchService.acceptOffer(context)
            return GleoResult.success(RECOMMEND_CHARGING_STOP, "Switched the charging stop to the suggested station.", "switched" to true)
        }
        val dest = navi.destination()
            ?: return GleoResult.asking(RECOMMEND_CHARGING_STOP, "No destination is set. Please set a destination in the navigation first.")
        val origin = navi.currentLocation()
        val trip = navi.trip(dest)
        val r = planner.recommend(c, origin, dest, trip, forecast = true)

        return when (val plan = r.plan) {
            is ChargePlanResult.NoChargeNeeded -> GleoResult.success(
                RECOMMEND_CHARGING_STOP,
                "No charging stop is needed. The chance of reaching ${dest.name} is ${pct(plan.probability)} percent.",
                "arrivalChancePercent" to pct(plan.probability),
            )
            is ChargePlanResult.Infeasible -> GleoResult.success(
                RECOMMEND_CHARGING_STOP,
                "No single charging stop on this route gives a safe arrival. ${r.checkedStations} charging stations were checked.",
                "checkedStations" to r.checkedStations,
            )
            is ChargePlanResult.Plan -> {
                val best = r.best!!
                val s = best.station
                if (addAsWaypoint) {
                    ChargeTargetStore.get(context)?.let { old -> navi.removeWaypointNear(old.location) }
                    navi.addChargingWaypoint(s.name, s.location)
                    ChargeWatchService.start(context, s.id, s.name, s.location, s.maxKw)
                }
                val wait = best.waitMin.roundToInt()
                val saved = r.savedMin?.roundToInt()?.takeIf { it > 0 }
                val savedText = saved?.let { " This saves about $it minutes compared with the navigation's suggestion." } ?: ""
                val added = if (addAsWaypoint) " It has been added as a waypoint." else ""
                GleoResult.success(
                    RECOMMEND_CHARGING_STOP,
                    "Stop at ${s.name} and charge to ${pct(plan.chargeToSoc)} percent. " +
                        "Expected waiting time on arrival is about $wait minutes, and the trip takes about ${plan.minutesP50.roundToInt()} minutes.$savedText$added",
                    "charger" to s.name, "chargeToPercent" to pct(plan.chargeToSoc),
                    "freeChargersNow" to s.available, "totalChargers" to s.inService, "maxPowerKw" to s.maxKw.roundToInt(),
                    "expectedWaitMin" to wait, "savedMinutes" to (saved ?: 0), "arrivalChancePercent" to pct(plan.probability),
                    "tripMinutes" to plan.minutesP50.roundToInt(), "addedAsWaypoint" to addAsWaypoint,
                )
            }
        }
    }

    private suspend fun findNearby(navi: NaviSource, minPowerKw: Double): GleoResult {
        val here = navi.currentLocation()
            ?: return GleoResult.fail(FIND_NEARBY_CHARGER, "Could not get the current location from the navigation.")
        val best = ChargerApi().nearby(here, radiusKm = 5.0, limit = 30)
            .filter { it.maxKw >= minPowerKw && it.inService > 0 }
            .sortedWith(compareBy({ it.available == 0 }, { it.waitMin ?: 99.0 }, { it.distanceKm }))
            .take(3)
        if (best.isEmpty()) {
            return GleoResult.success(FIND_NEARBY_CHARGER, "There are no working fast chargers within 5 kilometers.")
        }
        val top = best.first()
        val status = if (top.available > 0) "${top.available} of ${top.inService} chargers are free"
        else "all chargers are busy, with about ${(top.waitMin ?: 15.0).roundToInt()} minutes of waiting"
        return GleoResult.success(
            FIND_NEARBY_CHARGER,
            "The best nearby option is ${top.name}, ${"%.1f".format(top.distanceKm)} kilometers away, where $status.",
            *best.mapIndexed { i, s ->
                "option${i + 1}" to "${s.name} | ${"%.1f".format(s.distanceKm)}km | free ${s.available}/${s.inService} | ${s.maxKw.roundToInt()}kW"
            }.toTypedArray(),
        )
    }

    companion object {
        private const val TAG = "GleoReceiver"
        private const val EXTRA_PARAM = "ACTION_PARAM"
        private const val BUDGET_MS = 6_500L

        const val CHECK_ARRIVAL_CHANCE = "CHECK_ARRIVAL_CHANCE"
        const val RECOMMEND_CHARGING_STOP = "RECOMMEND_CHARGING_STOP"
        const val FIND_NEARBY_CHARGER = "FIND_NEARBY_CHARGER"
        private val FUNCTIONS = setOf(CHECK_ARRIVAL_CHANCE, RECOMMEND_CHARGING_STOP, FIND_NEARBY_CHARGER)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val planner = TripPlanner()

        private fun pct(x: Double) = (x * 100).roundToInt()
    }
}
