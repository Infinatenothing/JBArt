package com.infinatenothing.jbart.ui

import android.app.Activity
import android.app.ActivityManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.infinatenothing.jbart.model.CanvasLimits
import com.infinatenothing.jbart.DrawingViewModel
import com.infinatenothing.jbart.R
import com.infinatenothing.jbart.canvas.DrawingView
import com.infinatenothing.jbart.model.ColorTarget
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.ui.toolbar.BrushSettingsPanel
import com.infinatenothing.jbart.ui.toolbar.ColorPickerPanel
import com.infinatenothing.jbart.ui.toolbar.DrawingToolbar
import com.infinatenothing.jbart.ui.toolbar.FillSettingsPanel

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
    var colorPickerOpen by remember { mutableStateOf(false) }
    var canvasNew by remember { mutableStateOf(false) }
    var canvasClear by remember { mutableStateOf(false) }
    var canvasBackground by remember { mutableStateOf(false) }
    val memoryClassMb = remember {
        activity.getSystemService(ActivityManager::class.java)?.memoryClass ?: 192
    }
    val revision = model.revision
    val pending = model.pending
    val fillBusy = model.fillBusy
    val canUndo = !fillBusy && (pending != null || model.history.canUndo)
    val canRedo = !fillBusy && pending == null && model.history.canRedo
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
                view.strokeColor = model.colorSettings.stroke
                view.fillColor = model.colorSettings.fill
                view.shapeFilled = model.shapeFilled
                view.fillTolerance = model.fillTolerance
                view.fillGapDp = model.fillGapDp
                view.onFillBusy = { model.setFillRunning(it) }
                view.eyedropperTarget = if (model.colorTarget == ColorTarget.FILL) "fill" else "stroke"
                view.onEyedropperPick = { model.pickEyedropper(it) }
                view.onEyedropperCancel = { model.cancelEyedropper() }
                view.eyedropperActive = model.eyedropperActive
                view.onCommit = { model.commit(it) }
                view.onStagePending = { model.stagePending(it) }
                view.onUpdatePending = { model.updatePending(it) }
                view.onCommitPending = { model.commitPending() }
                view.onViewChanged = { scale, centerX, centerY ->
                    model.updateView(scale, centerX, centerY)
                }
                view.setViewState(
                    model.viewScale,
                    model.viewCenterX ?: Float.NaN,
                    model.viewCenterY ?: Float.NaN,
                )
                view.bindPending(pending)
                view.sync(model.document, revision)
                view.setFitNonce(model.fitNonce)
            },
        )
    }
    val colorPicker = @Composable { modifier: Modifier ->
        ColorPickerPanel(
            stroke = model.colorSettings.stroke,
            fill = model.colorSettings.fill,
            target = model.colorTarget,
            recent = model.colorSettings.recent,
            onTarget = { model.chooseColorTarget(it) },
            onStroke = { model.updateStrokeColor(it) },
            onFill = { model.updateFillColor(it) },
            onEyedropper = {
                colorPickerOpen = false
                model.startEyedropper()
            },
            modifier = modifier,
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
            fillTolerance = model.fillTolerance,
            fillGapDp = model.fillGapDp,
            onFillTolerance = { model.updateFillTolerance(it) },
            onFillGap = { model.updateFillGap(it) },
            settingsTool = settingsTool,
            onSelectTool = { tool ->
                canvasView[0]?.flushPendingEdit()
                if (model.eyedropperActive) canvasView[0]?.cancelEyedropperGesture()
                model.selectTool(tool)
                settingsTool = null
                colorPickerOpen = false
            },
            onOpenSettings = { tool ->
                canvasView[0]?.flushPendingEdit()
                model.commitPending()
                model.selectTool(tool)
                settingsTool = tool
                colorPickerOpen = false
            },
            onDismissSettings = { settingsTool = null },
            onBrushSettings = { model.updateBrush(it) },
            onEraserSettings = { model.updateEraser(it) },
            onUndo = { model.undo() },
            onRedo = {
                canvasView[0]?.flushPendingEdit()
                model.redo()
            },
            strokeColor = model.colorSettings.stroke,
            colorPopup = if (!compact && colorPickerOpen && !model.eyedropperActive) {
                { colorPicker(Modifier.fillMaxWidth()) }
            } else {
                null
            },
            onOpenColor = {
                if (model.eyedropperActive) {
                    canvasView[0]?.cancelEyedropperGesture() ?: model.cancelEyedropper()
                }
                settingsTool = null
                colorPickerOpen = true
            },
            onDismissColor = { colorPickerOpen = false },
            zoomScale = model.viewScale,
            onZoomIn = { canvasView[0]?.zoomIn() },
            onZoomOut = { canvasView[0]?.zoomOut() },
            onNewCanvas = {
                canvasView[0]?.abandonInput()
                settingsTool = null
                colorPickerOpen = false
                canvasBackground = false
                canvasClear = false
                canvasNew = true
            },
            onClearCanvas = {
                canvasView[0]?.abandonInput()
                settingsTool = null
                colorPickerOpen = false
                canvasBackground = false
                canvasNew = false
                canvasClear = true
            },
            onBackgroundColor = {
                settingsTool = null
                colorPickerOpen = false
                canvasNew = false
                canvasClear = false
                canvasBackground = true
            },
            modifier = modifier,
        )
    }
    val cancelEyedropper = {
        canvasView[0]?.cancelEyedropperGesture() ?: model.cancelEyedropper()
    }
    DisposableEffect(model) {
        model.cancelInFlightFill = { canvasView[0]?.cancelOutstandingFill() }
        onDispose {
            canvasView[0]?.cancelOutstandingFill()
            model.cancelInFlightFill = null
        }
    }
    if (model.eyedropperActive) {
        BackHandler(onBack = cancelEyedropper)
    }
    if (compact) {
        Scaffold(bottomBar = { toolbar(false, Modifier) }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                canvas(Modifier.fillMaxSize())
                if (model.eyedropperActive) EyedropperCancel(cancelEyedropper)
                if (fillBusy) FillBusy(Modifier.align(Alignment.TopCenter))
            }
        }
    } else {
        Scaffold { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                toolbar(true, Modifier.fillMaxHeight().width(80.dp).zIndex(1f))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    canvas(Modifier.fillMaxSize())
                    if (model.eyedropperActive) EyedropperCancel(cancelEyedropper)
                    if (fillBusy) FillBusy(Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
    val openTool = settingsTool
    if (compact && openTool == Tool.FILL) {
        ModalBottomSheet(
            onDismissRequest = { settingsTool = null },
            containerColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Black) },
        ) {
            FillSettingsPanel(
                tolerance = model.fillTolerance,
                gapDp = model.fillGapDp,
                onTolerance = { model.updateFillTolerance(it) },
                onGap = { model.updateFillGap(it) },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            )
        }
    }
    if (compact && openTool != null && openTool != Tool.FILL) {
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
    if (compact && colorPickerOpen && !model.eyedropperActive) {
        ModalBottomSheet(
            onDismissRequest = { colorPickerOpen = false },
            containerColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Black) },
        ) {
            colorPicker(Modifier.fillMaxWidth().padding(bottom = 24.dp))
        }
    }
    if (canvasNew) {
        NewCanvasDialog(
            viewWidth = canvasView[0]?.width ?: 0,
            viewHeight = canvasView[0]?.height ?: 0,
            memoryClassMb = memoryClassMb,
            recent = model.colorSettings.recent,
            onDismiss = { canvasNew = false },
            onCreate = { width, height, color ->
                canvasView[0]?.abandonInput()
                canvasNew = false
                model.newCanvas(width, height, color)
            },
        )
    }
    if (canvasClear) {
        AlertDialog(
            onDismissRequest = { canvasClear = false },
            containerColor = Color.White,
            titleContentColor = Color.Black,
            textContentColor = Color.Black,
            title = { Text(stringResource(R.string.canvas_clear_title)) },
            text = { Text(stringResource(R.string.canvas_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        canvasView[0]?.abandonInput()
                        canvasClear = false
                        model.clearCanvas()
                    },
                    modifier = Modifier.testTag("canvas_clear_confirm"),
                ) {
                    Text(stringResource(R.string.canvas_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { canvasClear = false }) {
                    Text(stringResource(R.string.canvas_cancel))
                }
            },
        )
    }
    if (canvasBackground) {
        Dialog(onDismissRequest = { canvasBackground = false }) {
            ColorPickerPanel(
                stroke = model.document.backgroundColor,
                fill = model.document.backgroundColor,
                target = ColorTarget.STROKE,
                recent = model.colorSettings.recent,
                onTarget = {},
                onStroke = {},
                onFill = {},
                onEyedropper = {},
                singleColor = model.document.backgroundColor,
                onSingleColor = { model.setBackgroundColor(it) },
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            )
        }
    }
}

@Composable
private fun NewCanvasDialog(
    viewWidth: Int,
    viewHeight: Int,
    memoryClassMb: Int,
    recent: List<Int>,
    onDismiss: () -> Unit,
    onCreate: (Int, Int, Int) -> Unit,
) {
    val choices = CanvasLimits.PRESETS.mapNotNull { preset ->
        val width = if (preset.screen) viewWidth else preset.width
        val height = if (preset.screen) viewHeight else preset.height
        if (preset.screen && (viewWidth <= 0 || viewHeight <= 0)) return@mapNotNull null
        if (!CanvasLimits.offered(width, height, memoryClassMb)) return@mapNotNull null
        Triple(preset, width, height)
    }
    var selected by remember { mutableIntStateOf(0) }
    var color by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    var picking by remember { mutableStateOf(false) }
    val index = selected.coerceIn(0, (choices.size - 1).coerceAtLeast(0))
    if (picking) {
        Dialog(onDismissRequest = { picking = false }) {
            ColorPickerPanel(
                stroke = color,
                fill = color,
                target = ColorTarget.STROKE,
                recent = recent,
                onTarget = {},
                onStroke = {},
                onFill = {},
                onEyedropper = {},
                singleColor = color,
                onSingleColor = { color = it },
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            )
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        titleContentColor = Color.Black,
        textContentColor = Color.Black,
        title = { Text(stringResource(R.string.canvas_new_title)) },
        text = {
            Column {
                Text(stringResource(R.string.canvas_new_body))
                Spacer(Modifier.height(12.dp))
                choices.forEachIndexed { itemIndex, (preset, width, height) ->
                    val label = presetLabel(preset, width, height)
                    Row(
                        modifier = Modifier
                            .testTag(if (preset.screen) "canvas_preset_screen" else "canvas_preset_${width}x$height")
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = itemIndex == index,
                                role = Role.RadioButton,
                                onClick = { selected = itemIndex },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = label, color = Color.Black)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { picking = true },
                    modifier = Modifier.testTag("canvas_new_background"),
                ) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .background(Color(color))
                            .border(1.dp, Color.Black),
                    )
                    Text(
                        text = stringResource(R.string.canvas_background),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val choice = choices.getOrNull(index) ?: return@TextButton
                    onCreate(choice.second, choice.third, color)
                },
                enabled = choices.isNotEmpty(),
                modifier = Modifier.testTag("canvas_new_confirm"),
            ) {
                Text(stringResource(R.string.canvas_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.canvas_cancel))
            }
        },
    )
}

@Composable
private fun presetLabel(preset: CanvasLimits.Preset, width: Int, height: Int): String {
    return when {
        preset.screen -> stringResource(R.string.canvas_preset_screen)
        width == 2048 && height == 1536 -> stringResource(R.string.canvas_preset_2048)
        width == 2560 && height == 1600 -> stringResource(R.string.canvas_preset_2560)
        width == 2048 && height == 2048 -> stringResource(R.string.canvas_preset_square)
        else -> "$width x $height"
    }
}

@Composable
private fun FillBusy(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(12.dp)
            .size(48.dp)
            .background(Color.White)
            .border(2.dp, Color.Black)
            .testTag("fill_busy"),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = Color.Black,
            strokeWidth = 3.dp,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun EyedropperCancel(onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .heightIn(min = 48.dp)
                .background(Color.White)
                .testTag("eyedropper_cancel"),
        ) {
            Text(text = stringResource(R.string.eyedropper_cancel), color = Color.Black)
        }
    }
}
