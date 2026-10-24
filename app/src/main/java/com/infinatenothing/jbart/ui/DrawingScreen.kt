package com.infinatenothing.jbart.ui

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.infinatenothing.jbart.canvas.DrawingView
import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.HistoryManager
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.model.clampBrushSettings
import com.infinatenothing.jbart.ui.toolbar.BrushSettingsPanel
import com.infinatenothing.jbart.ui.toolbar.DrawingToolbar

private val BrushSettingsSaver = listSaver<BrushSettings, Float>(
    save = { listOf(it.sizeDp, it.opacity) },
    restore = { clampBrushSettings(BrushSettings(sizeDp = it[0], opacity = it[1])) },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
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
    var settingsTool by remember { mutableStateOf<Tool?>(null) }
    var brushSettings by rememberSaveable(stateSaver = BrushSettingsSaver) { mutableStateOf(BrushSettings()) }
    var eraserSettings by rememberSaveable(stateSaver = BrushSettingsSaver) {
        mutableStateOf(BrushSettings(sizeDp = BrushSettings.DEFAULT_ERASER_SIZE_DP))
    }
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
                view.brushSettings = brushSettings
                view.eraserSettings = eraserSettings
            },
        )
    }
    val toolbar = @Composable { vertical: Boolean, modifier: Modifier ->
        DrawingToolbar(
            vertical = vertical,
            anchorSettings = !compact,
            selectedTool = selectedTool,
            canUndo = canUndo,
            canRedo = canRedo,
            brushSettings = brushSettings,
            eraserSettings = eraserSettings,
            settingsTool = settingsTool,
            onSelectTool = { tool ->
                selectedTool = tool
                settingsTool = null
            },
            onOpenSettings = { tool ->
                selectedTool = tool
                settingsTool = tool
            },
            onDismissSettings = { settingsTool = null },
            onBrushSettings = { brushSettings = clampBrushSettings(it) },
            onEraserSettings = { eraserSettings = clampBrushSettings(it) },
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
    val openTool = settingsTool
    if (compact && openTool != null) {
        val eraser = openTool == Tool.ERASER
        ModalBottomSheet(
            onDismissRequest = { settingsTool = null },
            containerColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Black) },
        ) {
            BrushSettingsPanel(
                tool = openTool,
                settings = if (eraser) eraserSettings else brushSettings,
                onSettings = { updated ->
                    if (eraser) eraserSettings = clampBrushSettings(updated) else brushSettings = clampBrushSettings(updated)
                },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            )
        }
    }
}
