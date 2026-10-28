package com.sssoft.hk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.lifecycleScope
import com.sssoft.hk.ui.RangeScreen
import com.sssoft.hk.ui.RangeViewModel
import com.sssoft.hk.vehicle.BatterySource
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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                RangeScreen(viewModel)
            }
        }
    }

    override fun onDestroy() {
        battery.stop()
        super.onDestroy()
    }
}
