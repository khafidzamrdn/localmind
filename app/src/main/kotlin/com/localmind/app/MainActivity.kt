package com.localmind.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.localmind.app.core.ui.theme.LocalMindTheme
import com.localmind.app.core.i18n.I18n
import com.localmind.app.navigation.MainScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val app = application as LocalMindApplication
            val factory = remember {
                viewModelFactory {
                    initializer { AppViewModel(app.container.settingsRepository) }
                }
            }
            val appViewModel: AppViewModel = viewModel(factory = factory)
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
            val language by appViewModel.language.collectAsStateWithLifecycle()

            LaunchedEffect(language) { I18n.update(language) }

            LocalMindTheme(themeMode = themeMode) {
                MainScreen()
            }
        }
    }
}
