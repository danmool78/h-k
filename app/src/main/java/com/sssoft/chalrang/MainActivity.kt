package com.sssoft.chalrang

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.sssoft.chalrang.ui.ChalrangTheme
import com.sssoft.chalrang.ui.ChalrangViewModel
import com.sssoft.chalrang.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val viewModel: ChalrangViewModel by viewModels()

    // 위치 권한: 길 안내 출발점 (거절해도 내비가 위치를 보내면 그대로 쓴다)
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        // 처리 루프는 화면이 보이는 동안만 (멈추면 취소, 다시 보이면 이어서)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.imu.start()
                try {
                    viewModel.engine.run()
                } finally {
                    viewModel.imu.stop()
                }
            }
        }
        setContent {
            ChalrangTheme {
                MainScreen(viewModel.engine)
            }
        }
    }
}
