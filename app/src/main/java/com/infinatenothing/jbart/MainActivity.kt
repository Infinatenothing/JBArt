package com.infinatenothing.jbart

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.infinatenothing.jbart.ui.DrawingScreen
import com.infinatenothing.jbart.ui.theme.JBArtTheme

class MainActivity : ComponentActivity() {
    private val drawingViewModel: DrawingViewModel by viewModels {
        DrawingViewModel.factory(filesDir)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JBArtTheme {
                DrawingScreen(drawingViewModel)
            }
        }
    }

    override fun onStop() {
        drawingViewModel.onHostStop()
        super.onStop()
    }
}
