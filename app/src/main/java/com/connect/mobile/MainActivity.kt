package com.connect.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.connect.mobile.ui.RescuerAuthRoute
import com.connect.mobile.ui.RescuerAuthViewModel
import com.connect.mobile.ui.theme.ConnectTheme

/** OSP-73: BiometricPrompt yalnızca FragmentActivity üzerinde çalışır (ComponentActivity'nin alt sınıfı). */
class MainActivity : FragmentActivity() {
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
