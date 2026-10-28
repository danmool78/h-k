package com.sssoft.hk.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sssoft.hk.MainActivity
import com.sssoft.hk.R
import com.sssoft.hk.learn.ProfileStore
import com.sssoft.hk.navi.LatLng
import com.sssoft.hk.navi.NaviSource
import com.sssoft.hk.server.ChargerApi
import com.sssoft.hk.server.StationView
import com.sssoft.hk.trip.TripConditions
import com.sssoft.hk.trip.TripPlanner
import com.sssoft.hk.trip.TripPrefs
import com.sssoft.hk.vehicle.BatteryReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 주행 중 충전 경유지 감시. 경유지로 정한 충전소가 도착 전에 차거나 고장 나면
 * 경로를 다시 계산하고, 더 빠른 곳이 있으면 음성 안내 후 [SWITCH_DELAY_MS] 뒤 경유지를 바꾼다 (취소 가능).
 */
class ChargeWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private lateinit var navi: NaviSource
    private val api = ChargerApi(readTimeoutMs = 15_000)
    private val planner = TripPlanner(api)
    private var tts: TextToSpeech? = null
    @Volatile private var keepRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        navi = NaviSource(this)
        navi.onWaypointArrived = { stopWatching() }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.KOREAN
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_KEEP -> {
                keepRequested = true
                return START_STICKY
            }
            ACTION_STOP -> {
                stopWatching()
                return START_NOT_STICKY
            }
        }
        val target = ChargeTargetStore.get(this) ?: run { stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification("${target.name}까지 충전기 상태를 확인하고 있어요", null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (loop?.isActive != true) loop = scope.launch { watchLoop() }
        return START_STICKY
    }

    private suspend fun watchLoop() {
        if (!navi.connect()) Log.w(TAG, "내비 연결 실패, 위치 없이 감시")
        while (scope.isActive) {
            val target = ChargeTargetStore.get(this) ?: break
            if (System.currentTimeMillis() - target.setAt > MAX_WATCH_MS) break
            runCatching { check(target) }.onFailure { Log.w(TAG, "감시 실패", it) }
            delay(INTERVAL_MS)
        }
        stopWatching()
    }

    private suspend fun check(target: ChargeTarget) {
        val here = navi.currentLocation()
        val etaMin = here?.let { distanceKm(it, target.location) / AVG_SPEED_KMH * 60 } ?: 30.0
        val view = api.match(listOf(target.location to etaMin), radiusKm = 0.3).firstOrNull()

        if (!ReplanPolicy.targetDegraded(view, etaMin)) {
            val wait = TripPlanner.expectedWaitMin(view!!, etaMin).roundToInt()
            update("${target.name} · 빈 충전기 ${view.available}/${view.inService} · 도착 시 대기 약 ${wait}분")
            return
        }

        val reading = BatteryReader.read(this) ?: return
        val dest = navi.destination() ?: return
        val trip = navi.trip(dest)
        val cond = TripConditions.from(reading, ProfileStore.load(this), TripPrefs.tempC(this), TripPrefs.weather(this))
        val rec = planner.recommend(planner.withRouteWeather(cond, here, dest, trip?.etaMin ?: dest.remainMin), here, dest, trip)

        when (val d = ReplanPolicy.decide(target.stationId, rec)) {
            ReplanPolicy.Decision.Keep ->
                update("${target.name} 혼잡 예상 · 더 나은 충전소가 없어 유지합니다")
            is ReplanPolicy.Decision.Switch -> switchTo(target, d, view)
        }
    }

    private suspend fun switchTo(old: ChargeTarget, d: ReplanPolicy.Decision.Switch, oldView: StationView?) {
        val to = d.to.station
        val reason = when {
            oldView == null || oldView.inService == 0 -> "충전기를 사용할 수 없습니다"
            oldView.available == 0 -> "충전기가 모두 사용 중입니다"
            else -> "도착할 때 대기가 길어질 것 같습니다"
        }
        val gain = d.savedMin?.let { " ${it.roundToInt()}분 빨리 도착합니다." } ?: " 더 안전하게 도착할 수 있습니다."
        val text = "${old.name} $reason. ${to.name}로 바꾸면$gain ${SWITCH_DELAY_MS / 1000}초 뒤 경유지를 변경합니다."

        keepRequested = false
        speak(text)
        update(text, keepAction = true)
        delay(SWITCH_DELAY_MS)
        if (keepRequested) {
            update("${old.name} 경유지를 유지합니다")
            // 같은 상황에서 곧바로 다시 묻지 않도록 기준 시각만 갱신
            ChargeTargetStore.set(this, old.copy(setAt = System.currentTimeMillis()))
            return
        }
        navi.removeWaypointNear(old.location)
        navi.addChargingWaypoint(to.name, to.location)
        ChargeTargetStore.set(this, ChargeTarget(to.id, to.name, to.location, System.currentTimeMillis(), to.maxKw))
        speak("경유지를 ${to.name}로 변경했습니다.")
        update("${to.name}로 경유지를 변경했어요")
    }

    private fun stopWatching() {
        ChargeTargetStore.clear(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        navi.release()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "watch")
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "충전 경유지 감시", NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun update(text: String, keepAction: Boolean = false) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text, keepAction))
    }

    private fun notification(text: String, keepAction: Boolean?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_charge)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(keepAction != true)
            .apply {
                if (keepAction == true) addAction(0, "지금 경유지 유지", serviceIntent(ACTION_KEEP, 1))
                addAction(0, "감시 끄기", serviceIntent(ACTION_STOP, 2))
            }
            .build()
    }

    private fun serviceIntent(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, ChargeWatchService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "ChargeWatch"
        private const val CHANNEL = "charge_watch"
        private const val NOTIF_ID = 1001
        private const val ACTION_KEEP = "com.sssoft.hk.watch.KEEP"
        private const val ACTION_STOP = "com.sssoft.hk.watch.STOP"
        private const val INTERVAL_MS = 2 * 60_000L
        private const val SWITCH_DELAY_MS = 10_000L
        private const val MAX_WATCH_MS = 6 * 3600_000L
        private const val AVG_SPEED_KMH = 80.0

        /** 충전소를 경유지로 정했을 때 호출. 감시 대상 저장 후 서비스 시작. */
        fun start(context: Context, stationId: String, name: String, location: LatLng, maxKw: Double? = null) {
            ChargeTargetStore.set(context, ChargeTarget(stationId, name, location, System.currentTimeMillis(), maxKw))
            try {
                context.startForegroundService(Intent(context, ChargeWatchService::class.java))
            } catch (e: Exception) {
                // 백그라운드(예: Gleo 브로드캐스트)에서 시작이 막히면 앱을 열 때 이어서 시작
                Log.w(TAG, "감시 서비스 시작 실패, 앱 실행 시 재시도", e)
            }
        }

        /** 저장된 감시 대상이 있으면 서비스를 이어서 시작 (앱 실행 시). */
        fun resumeIfNeeded(context: Context) {
            if (ChargeTargetStore.get(context) != null) {
                runCatching { context.startForegroundService(Intent(context, ChargeWatchService::class.java)) }
            }
        }

        private fun distanceKm(a: LatLng, b: LatLng): Double {
            val r = 6371.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
            return 2 * r * asin(sqrt(h))
        }
    }
}
