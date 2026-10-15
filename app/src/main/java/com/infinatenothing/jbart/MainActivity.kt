package com.infinatenothing.jbart

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.infinatenothing.jbart.ui.DrawingScreen
import com.infinatenothing.jbart.ui.theme.JBArtTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JBArtTheme {
                DrawingScreen()
            }
        }
    }
}
