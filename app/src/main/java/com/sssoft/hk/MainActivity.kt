package com.sssoft.hk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.lifecycleScope
import com.sssoft.hk.ui.RangeScreen
import com.sssoft.hk.learn.DriveLearningService
import com.sssoft.hk.trip.TripPrefs
import com.sssoft.hk.ui.RangeViewModel
import com.sssoft.hk.vehicle.BatterySource
import com.sssoft.hk.watch.ChargeWatchService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: RangeViewModel by viewModels()
    private lateinit var battery: BatterySource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        battery = BatterySource(this)
        battery.start()
        lifecycleScope.launch {
            battery.state.collect(viewModel::onBattery)
        }
        lifecycleScope.launch {
            // Gleo 음성 호출에서 쓸 기온 (차량 SDK에 외부 기온 없음)
            viewModel.ui.map { it.input.tempC to it.input.weather }.distinctUntilChanged().collect { (t, w) ->
                TripPrefs.setTempC(this@MainActivity, t)
                TripPrefs.setWeather(this@MainActivity, w)
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                RangeScreen(viewModel)
            }
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        // 백그라운드(Gleo)에서 시작이 막혔던 경유지 감시를 이어서 시작
        ChargeWatchService.resumeIfNeeded(this)
        DriveLearningService.start(this)
    }

    override fun onDestroy() {
        battery.stop()
        super.onDestroy()
    }
}
