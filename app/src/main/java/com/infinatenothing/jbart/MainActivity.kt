package com.infinatenothing.jbart

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.infinatenothing.jbart.session.ContentProjectFiles
import com.infinatenothing.jbart.ui.DrawingScreen
import com.infinatenothing.jbart.ui.theme.JBArtTheme

class MainActivity : ComponentActivity() {
    private val drawingViewModel: DrawingViewModel by viewModels {
        DrawingViewModel.factory(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity still holds the VIEW intent it was launched with; it was handled already.
        if (savedInstanceState == null) handleView(intent)
        setContent {
            JBArtTheme {
                DrawingScreen(drawingViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleView(intent)
    }

    override fun onStop() {
        drawingViewModel.onHostStop()
        super.onStop()
    }

    private fun handleView(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        ContentProjectFiles(this).keep(uri)
        drawingViewModel.offerIncomingProject(uri.toString())
    }
}
