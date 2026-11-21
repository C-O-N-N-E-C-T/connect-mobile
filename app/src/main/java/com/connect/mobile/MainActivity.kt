package com.connect.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.connect.mobile.ui.RescuerAuthRoute
import com.connect.mobile.ui.RescuerAuthViewModel
import com.connect.mobile.ui.theme.ConnectTheme

class MainActivity : ComponentActivity() {
    private val viewModel: RescuerAuthViewModel by viewModels {
        viewModelFactory { initializer { (application as ConnectApplication).container.newRescuerAuthViewModel() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ConnectTheme { RescuerAuthRoute(viewModel) }
        }
    }
}
