package com.infinatenothing.jbart.ui

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.infinatenothing.jbart.canvas.DrawingView
import com.infinatenothing.jbart.model.HistoryManager
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.ui.toolbar.DrawingToolbar

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun DrawingScreen() {
    val activity = LocalContext.current as Activity
    val compact = calculateWindowSizeClass(activity).widthSizeClass == WindowWidthSizeClass.Compact
    var selectedTool by remember { mutableStateOf(Tool.BRUSH) }
    var historyVersion by remember { mutableIntStateOf(0) }
    val history = remember {
        HistoryManager().also { manager -> manager.onChanged = { historyVersion++ } }
    }
    var drawingView by remember { mutableStateOf<DrawingView?>(null) }
    val canUndo = historyVersion >= 0 && history.canUndo
    val canRedo = historyVersion >= 0 && history.canRedo

    Scaffold { padding ->
        val canvas = @Composable { modifier: Modifier ->
            AndroidView(
                modifier = modifier,
                factory = { context -> DrawingView(context) },
                update = { view ->
                    view.tool = selectedTool
                    view.history = history
                    drawingView = view
                },
            )
        }
        val toolbar = @Composable { vertical: Boolean ->
            DrawingToolbar(
                vertical = vertical,
                selectedTool = selectedTool,
                canUndo = canUndo,
                canRedo = canRedo,
                onSelectTool = { selectedTool = it },
                onUndo = { drawingView?.undo() },
                onRedo = { drawingView?.redo() },
            )
        }
        if (compact) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                canvas(Modifier.weight(1f))
                toolbar(false)
            }
        } else {
            Row(Modifier.padding(padding).fillMaxSize()) {
                toolbar(true)
                canvas(Modifier.weight(1f))
            }
        }
    }
}
