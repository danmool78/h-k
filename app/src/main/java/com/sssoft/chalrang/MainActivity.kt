package com.sssoft.chalrang

import android.os.Bundle
import androidx.activity.ComponentActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
