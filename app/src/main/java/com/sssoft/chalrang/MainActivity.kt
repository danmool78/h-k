package com.sssoft.chalrang

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.sssoft.chalrang.ui.ChalrangTheme
import com.sssoft.chalrang.ui.ChalrangViewModel
import com.sssoft.chalrang.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val viewModel: ChalrangViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChalrangTheme {
                MainScreen(viewModel.engine)
            }
        }
    }
}
