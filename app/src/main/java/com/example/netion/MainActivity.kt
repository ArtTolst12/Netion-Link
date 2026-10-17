package com.example.netion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Позволяем контенту заходить под системные бары —
        // цвет баров устанавливается в NetionTheme через SideEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MainApp()
        }
    }
}