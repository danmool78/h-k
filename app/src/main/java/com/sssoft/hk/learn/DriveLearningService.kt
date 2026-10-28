package com.sssoft.hk.learn

import ai.pleos.playground.vehicle.Vehicle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sssoft.hk.R
import com.sssoft.hk.model.TripContext
import com.sssoft.hk.navi.NaviSource
import com.sssoft.hk.model.ChargePlanResult
import com.sssoft.hk.server.ChargerApi
import com.sssoft.hk.trip.TripConditions
import com.sssoft.hk.trip.TripPlanner
import kotlin.math.roundToInt
import com.sssoft.hk.trip.TripPrefs
import com.sssoft.hk.vehicle.BatteryReader
import com.sssoft.hk.watch.ChargeTargetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 차가 켜져 있는 동안 차량 값을 [INTERVAL_MS] 마다 읽어 [DriveRecorder] 로 학습 단위를 만들고
 * 차량·운전자 프로필을 갱신한다. 원본 기록은 [ProfileStore] 에 최근 일정량만 남긴다.
 */
class DriveLearningService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var vehicle: Vehicle
    private lateinit var navi: NaviSource
    private lateinit var recorder: DriveRecorder
    private val api = ChargerApi(readTimeoutMs = 10_000)
    private val planner = TripPlanner(ChargerApi(readTimeoutMs = 15_000))
    private var lastWeatherMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        vehicle = Vehicle(this).also { runCatching { it.initialize() } }
        navi = NaviSource(this)
        recorder = DriveRecorder(ProfileStore.load(this).curbMassKg) { ProfileStore.load(this).capacityKwh }
        val ch = NotificationChannel(CHANNEL, "주행 학습", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_charge)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("이 차의 주행 패턴을 학습하고 있어요")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!running) {
            running = true
            scope.launch { loop() }
        }
        return START_STICKY
    }

    private suspend fun loop() {
        val naviOk = navi.connect()
        navi.onDestinationChanged = { scope.launch { runCatching { suggestForDestination() }.onFailure { Log.w(TAG, "목적지 추천 실패", it) } } }
        var lastPlanCheck = 0L
        while (scope.isActive) {
            runCatching { tick(naviOk, System.currentTimeMillis() - lastPlanCheck > PLAN_CHECK_MS) }
                .onSuccess { if (it) lastPlanCheck = System.currentTimeMillis() }
                .onFailure { Log.w(TAG, "학습 기록 실패", it) }
            delay(INTERVAL_MS)
        }
    }

    /** 반환: 이번에 내비 계획을 확인했는지 */
    private suspend fun tick(naviOk: Boolean, checkPlan: Boolean): Boolean {
        val r = BatteryReader.read(vehicle) ?: return false
        val odo = r.odometerKm ?: return false
        val profile = ProfileStore.load(this)
        val now = System.currentTimeMillis()
        // 현재 위치 날씨 (Open-Meteo) 를 주기적으로 받아 저장 → 학습 기록과 백그라운드 예측이 실제 날씨를 쓴다
        if (naviOk && now - lastWeatherMs > WEATHER_MS) {
            navi.currentLocation()?.let { here ->
                runCatching { api.routeWeather(listOf(here to 0.0)) }.onSuccess {
                    TripPrefs.setTempC(this, it.tempC.toFloat())
                    TripPrefs.setWeather(this, it.weather)
                    lastWeatherMs = now
                }
            }
        }
        val weather = TripPrefs.weather(this)

        // 주행 시작 즈음 내비 목적지로 이번 주행의 조건(유형·밤낮·목적지)과 예상 시간을 잡는다
        var context = recorder.plan?.context
        if (checkPlan && naviOk && recorder.plan == null) {
            val dest = navi.destination()
            val here = navi.currentLocation()
            if (dest != null) {
                val ctx = TripContext.of(dest.remainKm, now, here?.lat ?: dest.location.lat, here?.lng ?: dest.location.lng,
                    weather, dest.location.lat, dest.location.lng, r.mode)
                // 출발 시점 예측 기록: 내비 도착 예상 배터리 vs 안기다려(내비 + 학습된 차이)
                val soc = r.socPercent / 100.0
                val navSoc = navi.trip(dest)?.estimatedSocAtDest?.takeIf { it in 0..100 && it < r.socPercent }?.div(100.0)
                val oursSoc = navSoc?.let { soc - (soc - it) * kotlin.math.exp(profile.navLogBias) }
                val oursMin = dest.remainMin * kotlin.math.exp(profile.paceLog(ctx).first)
                recorder.plan = DriveRecorder.Plan(ctx, dest.remainMin, soc, navSoc, oursSoc, oursMin)
                context = ctx
            }
        }
        if (context == null) {
            context = TripContext.of(0.0, now, 37.5, 127.0, weather, null, null, r.mode)
        }
        // 충전 중이면 감시 대상 충전소의 출력을 쓴다
        ChargeTargetStore.get(this)?.let { recorder.chargerKw = it.maxKw ?: recorder.chargerKw }

        val sample = VehicleSample(
            timeMs = now, odometerKm = odo, batteryLevelWh = r.batteryLevelWh, soc = r.socPercent / 100.0,
            occupants = r.occupants, hvacOn = r.hvacOn, cabinSetC = r.cabinSetC,
            tireUnderinflation = profile.tireUnderinflation(r.tirePressureKpa),
            tempC = TripPrefs.tempC(this), weather = weather,
        )
        for (e in recorder.onSample(sample, context)) apply(e)
        return checkPlan
    }

    private var lastSuggestAt = 0L

    /**
     * 내비에 목적지가 들어오면 충전 추천을 알림으로 띄운다 (내비 플러그인 동작).
     * 정차 중이면 출발 시각 비교도 해서 15분 이상 줄어들 때만 한 줄 덧붙인다.
     */
    private suspend fun suggestForDestination() {
        val now = System.currentTimeMillis()
        if (now - lastSuggestAt < 20_000) return
        lastSuggestAt = now
        delay(2_000) // 내비가 경로를 계산할 시간
        val dest = navi.destination() ?: return
        val here = navi.currentLocation()
        val trip = navi.trip(dest)
        val r = BatteryReader.read(vehicle) ?: return
        val cond = TripConditions.from(
            r, ProfileStore.load(this), TripPrefs.tempC(this), TripPrefs.weather(this),
            arrivalReserveSoc = TripPrefs.arrivalReserve(this), meal = TripPrefs.meal(this),
        )
        val rec = planner.recommend(cond, here, dest, trip, forecast = true)
        val line1 = when (val p = rec.plan) {
            is ChargePlanResult.NoChargeNeeded -> "충전 없이 도착 · 도착 배터리 약 ${pct(rec.direct.socP50)}" +
                (rec.mealStop?.let { " · ${rec.meal?.label ?: "식사"}은 ${it.station.name}에서 (먹는 동안 ${pct(it.plan.chargeToSoc)}까지)" } ?: "")
            is ChargePlanResult.Infeasible -> "경로상 충전소로는 어려워요 · 출발 전 충전을 권합니다"
            is ChargePlanResult.Plan -> {
                val b = rec.best!!
                val base = rec.baseline
                val saved = rec.savedMin?.takeIf { it >= 3 }?.let {
                    if (base?.naviPick == true) " · 내비 추천 ${base.station.name} 대신 가면 ${it.roundToInt()}분 빠름"
                    else " · ${it.roundToInt()}분 빠름"
                } ?: ""
                val stops = if (p.stopCount > 1) rec.stopsText(p) else "${b.station.name}에서 ${p.chargeMinutesP50.roundToInt()}분 충전"
                "$stops → ${clock(now, p.minutesP50)} 도착$saved"
            }
        }
        var line2: String? = null
        if (!recorder.isDriving) {
            val opts = planner.departureOptions(cond, here, dest, trip, listOf(30, 60, 90, 120))
            val base = rec.totalMinutes?.first
            val best = opts.mapNotNull { (off, rc) -> rc.totalMinutes?.first?.let { off to it } }.minByOrNull { it.second }
            if (base != null && best != null && base - best.second >= 15) {
                line2 = "${best.first}분 뒤 출발하면 휴게소가 덜 붐벼 총 ${(base - best.second).roundToInt()}분 단축 " +
                    "(${clock(now + best.first * 60_000L, best.second)} 도착)"
            }
        }
        notifySuggestion("${dest.name} 가는 길", listOfNotNull(line1, line2).joinToString("\n"))
    }

    private fun notifySuggestion(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(SUGGEST_CHANNEL, "충전 추천", NotificationManager.IMPORTANCE_HIGH))
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, com.sssoft.hk.MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            SUGGEST_ID,
            NotificationCompat.Builder(this, SUGGEST_CHANNEL)
                .setSmallIcon(R.drawable.ic_charge)
                .setContentTitle(title)
                .setContentText(text.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun pct(x: Double) = "${(x.coerceAtLeast(0.0) * 100).roundToInt()}%"
    private fun clock(fromMs: Long, minutes: Double) =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(fromMs + (minutes * 60_000).toLong()))

    private fun apply(e: DriveRecorder.Event) {
        val record = JSONObject().put("t", System.currentTimeMillis())
        when (e) {
            is DriveRecorder.Event.Segment -> {
                ProfileStore.update(this) { it.learnSegment(e.segment).learnCapacity(e.deltaWh, e.deltaSoc) }
                val s = e.segment.energyInput
                record.put("type", "segment").put("km", s.distKm).put("h", s.hours).put("wh", e.segment.energyWh)
                    .put("tempC", s.tempC).put("massKg", s.massKg).put("accelWh", s.accelKineticWh)
                    .put("ctx", e.segment.context.toString())
            }
            is DriveRecorder.Event.TripEnd -> {
                val p = e.plan
                // 중간에 충전했으면 배터리 비교가 무의미하므로 시간만 학습
                val navSoc = p.navSocAtDest.takeIf { !e.chargedDuringTrip }
                ProfileStore.update(this) { prof ->
                    val paced = prof.learnPace(e.context, e.navEtaMin, e.actualDriveMin)
                    if (navSoc != null) paced.learnNavBias(p.socStart - navSoc, p.socStart - e.socEnd) else paced
                }
                if (navSoc != null && p.oursSoc != null && p.oursMin != null) {
                    PredictionLog.append(
                        this,
                        PredictionRecord(System.currentTimeMillis(), navSoc, p.oursSoc, e.socEnd, e.navEtaMin, p.oursMin, e.actualDriveMin),
                    )
                }
                record.put("type", "trip").put("navMin", e.navEtaMin).put("actualMin", e.actualDriveMin)
                    .put("ctx", e.context.toString())
            }
            is DriveRecorder.Event.Charge -> {
                ProfileStore.update(this) { it.learnCharge(e.session) }
                val c = e.session
                record.put("type", "charge").put("from", c.fromSoc).put("to", c.toSoc).put("min", c.minutes).put("kw", c.chargerKw)
            }
        }
        ProfileStore.appendLog(this, record)
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        navi.release()
        runCatching { vehicle.release() }
        super.onDestroy()
    }

    /** 차가 켜지면(부팅) 학습 시작. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) start(context)
        }
    }

    companion object {
        private const val TAG = "DriveLearning"
        private const val CHANNEL = "drive_learning"
        private const val NOTIF_ID = 1002
        private const val INTERVAL_MS = 10_000L
        private const val PLAN_CHECK_MS = 60_000L
        private const val WEATHER_MS = 10 * 60_000L
        private const val SUGGEST_CHANNEL = "trip_suggest"
        private const val SUGGEST_ID = 1003
        @Volatile private var running = false

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, DriveLearningService::class.java)) }
                .onFailure { Log.w(TAG, "학습 서비스 시작 실패", it) }
        }
    }
}
