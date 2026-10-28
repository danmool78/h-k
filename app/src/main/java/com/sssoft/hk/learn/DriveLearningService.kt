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
        val weather = TripPrefs.weather(this)

        // 주행 시작 즈음 내비 목적지로 이번 주행의 조건(유형·밤낮·목적지)과 예상 시간을 잡는다
        var context = recorder.plan?.context
        if (checkPlan && naviOk && recorder.plan == null) {
            val dest = navi.destination()
            val here = navi.currentLocation()
            if (dest != null) {
                val ctx = TripContext.of(dest.remainKm, now, here?.lat ?: dest.location.lat, here?.lng ?: dest.location.lng,
                    weather, dest.location.lat, dest.location.lng, r.mode)
                recorder.plan = DriveRecorder.Plan(ctx, dest.remainMin)
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
                ProfileStore.update(this) { it.learnPace(e.context, e.navEtaMin, e.actualDriveMin) }
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
        @Volatile private var running = false

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, DriveLearningService::class.java)) }
                .onFailure { Log.w(TAG, "학습 서비스 시작 실패", it) }
        }
    }
}
