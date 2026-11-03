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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.infinatenothing.jbart.DrawingViewModel
import com.infinatenothing.jbart.canvas.DrawingView
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.ui.toolbar.BrushSettingsPanel
import com.infinatenothing.jbart.ui.toolbar.DrawingToolbar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun DrawingScreen(model: DrawingViewModel) {
    val activity = LocalContext.current as Activity
    val widthClass = calculateWindowSizeClass(activity).widthSizeClass
    val compact = widthClass == WindowWidthSizeClass.Compact
    val widthDp = LocalConfiguration.current.screenWidthDp
    val heightDp = LocalConfiguration.current.screenHeightDp
    LaunchedEffect(widthClass) {
        Log.d("JBArtLayout", "widthClass=$widthClass widthDp=$widthDp heightDp=$heightDp")
    }
    var settingsTool by remember { mutableStateOf<Tool?>(null) }
    val revision = model.revision
    val pending = model.pending
    val canUndo = pending != null || model.history.canUndo
    val canRedo = pending == null && model.history.canRedo
    val selectedTool = model.tool
    val brushSettings = model.brushSettings
    val eraserSettings = model.eraserSettings

    val canvasView = remember { arrayOfNulls<DrawingView>(1) }
    val canvas = @Composable { modifier: Modifier ->
        AndroidView(
            modifier = modifier
                .testTag("canvas")
                .clipToBounds()
                .onGloballyPositioned { coords ->
                    Log.d("JBArtLayout", "canvas bounds=${coords.boundsInWindow()}")
                },
            factory = { context -> DrawingView(context).also { canvasView[0] = it } },
            update = { view ->
                view.tool = selectedTool
                view.brushSettings = brushSettings
                view.eraserSettings = eraserSettings
                view.lineSnapEnabled = model.lineSnapEnabled
                view.shapeKind = model.shapeKind
                view.shapeConstrain = model.shapeConstrain
                view.shapeFilled = model.shapeFilled
                view.onCommit = { model.commit(it) }
                view.onStagePending = { model.stagePending(it) }
                view.onUpdatePending = { model.updatePending(it) }
                view.onCommitPending = { model.commitPending() }
                view.bindPending(pending)
                view.sync(model.document, revision)
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
            lineSnapEnabled = model.lineSnapEnabled,
            onLineSnap = { model.setLineSnap(it) },
            shapeKind = model.shapeKind,
            shapeConstrain = model.shapeConstrain,
            shapeFilled = model.shapeFilled,
            onShapeKind = { model.updateShapeKind(it) },
            onShapeConstrain = { model.updateShapeConstrain(it) },
            onShapeFilled = { model.updateShapeFilled(it) },
            settingsTool = settingsTool,
            onSelectTool = { tool ->
                canvasView[0]?.flushPendingEdit()
                model.selectTool(tool)
                settingsTool = null
            },
            onOpenSettings = { tool ->
                canvasView[0]?.flushPendingEdit()
                model.commitPending()
                model.selectTool(tool)
                settingsTool = tool
            },
            onDismissSettings = { settingsTool = null },
            onBrushSettings = { model.updateBrush(it) },
            onEraserSettings = { model.updateEraser(it) },
            onUndo = { model.undo() },
            onRedo = {
                canvasView[0]?.flushPendingEdit()
                model.redo()
            },
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
                    if (eraser) model.updateEraser(updated) else model.updateBrush(updated)
                },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            )
        }
    }
}
