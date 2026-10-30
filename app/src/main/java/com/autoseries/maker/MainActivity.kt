package com.autoseries.maker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFF6D3F),
                    onPrimary = Color.White,
                    background = Color(0xFF0E1116),
                    surface = Color(0xFF171C24),
                    surfaceVariant = Color(0xFF222936),
                    onSurface = Color(0xFFEDEFF3),
                )
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    BackHandler(enabled = vm.screen == Screen.SETUP || vm.screen == Screen.DONE) { vm.home() }
                    AppUi(vm)
                }
            }
        }
    }
}
