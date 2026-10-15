package com.infinatenothing.jbart.ui

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.infinatenothing.jbart.canvas.DrawingView
import com.infinatenothing.jbart.model.HistoryManager
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.ui.toolbar.DrawingToolbar

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun DrawingScreen() {
    val activity = LocalContext.current as Activity
    val widthClass = calculateWindowSizeClass(activity).widthSizeClass
    val compact = widthClass == WindowWidthSizeClass.Compact
    val widthDp = LocalConfiguration.current.screenWidthDp
    val heightDp = LocalConfiguration.current.screenHeightDp
    LaunchedEffect(widthClass) {
        Log.d("JBArtLayout", "widthClass=$widthClass widthDp=$widthDp heightDp=$heightDp")
    }
    var selectedTool by remember { mutableStateOf(Tool.BRUSH) }
    var historyVersion by remember { mutableIntStateOf(0) }
    val history = remember {
        HistoryManager().also { manager -> manager.onChanged = { historyVersion++ } }
    }
    var drawingView by remember { mutableStateOf<DrawingView?>(null) }
    val canUndo = historyVersion >= 0 && history.canUndo
    val canRedo = historyVersion >= 0 && history.canRedo

    val canvas = @Composable { modifier: Modifier ->
        AndroidView(
            modifier = modifier
                .clipToBounds()
                .onGloballyPositioned { coords ->
                    Log.d("JBArtLayout", "canvas bounds=${coords.boundsInWindow()}")
                },
            factory = { context -> DrawingView(context).also { drawingView = it } },
            update = { view ->
                view.tool = selectedTool
                view.history = history
            },
        )
    }
    val toolbar = @Composable { vertical: Boolean, modifier: Modifier ->
        DrawingToolbar(
            vertical = vertical,
            selectedTool = selectedTool,
            canUndo = canUndo,
            canRedo = canRedo,
            onSelectTool = { selectedTool = it },
            onUndo = { drawingView?.undo() },
            onRedo = { drawingView?.redo() },
            modifier = modifier,
        )
    }
    if (compact) {
        Scaffold(bottomBar = { toolbar(false, Modifier) }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                canvas(Modifier.fillMaxSize())
            }
        }
    } else {
        Scaffold { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                toolbar(true, Modifier.fillMaxHeight().width(80.dp).zIndex(1f))
                canvas(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}
