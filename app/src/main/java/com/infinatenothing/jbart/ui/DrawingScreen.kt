package com.infinatenothing.jbart.ui

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.infinatenothing.jbart.model.CanvasLimits
import com.infinatenothing.jbart.DrawingViewModel
import com.infinatenothing.jbart.ProjectEvent
import com.infinatenothing.jbart.session.ContentProjectFiles
import com.infinatenothing.jbart.session.ProjectArchive
import com.infinatenothing.jbart.R
import com.infinatenothing.jbart.canvas.DrawingView
import com.infinatenothing.jbart.canvas.ViewTransform
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
    var exportOpen by remember { mutableStateOf(false) }
    var exportBusy by remember { mutableStateOf(false) }
    // Project actions: NEW_ACTION or a uri to open. Saveable so a rotation during a picker keeps them.
    var unsavedPrompt by rememberSaveable { mutableStateOf<String?>(null) }
    var afterSaveAs by rememberSaveable { mutableStateOf<String?>(null) }
    var recentsOpen by rememberSaveable { mutableStateOf(false) }
    val projectFiles = remember { ContentProjectFiles(activity) }
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
                view.inputBlocked = model.projectBusy
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
    fun beginExport(share: Boolean) {
        exportOpen = false
        val view = canvasView[0]
        if (view == null) {
            Toast.makeText(activity, R.string.export_failed, Toast.LENGTH_SHORT).show()
            return
        }
        exportBusy = true
        view.startExport(
            format = model.exportFormat,
            quality = model.exportQuality,
            transparent = model.exportTransparent,
            share = share,
            onBusy = { exportBusy = it },
        )
    }
    val exportPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            beginExport(share = false)
        } else {
            Log.d("DrawingView", "export failed permission")
            Toast.makeText(activity, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    fun requestExport(share: Boolean) {
        val needsWrite = !share &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsWrite) {
            exportOpen = false
            exportPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            beginExport(share)
        }
    }
    fun closePanels() {
        canvasView[0]?.abandonInput()
        settingsTool = null
        colorPickerOpen = false
        canvasBackground = false
        canvasClear = false
        canvasNew = false
    }
    fun runProjectAction(action: String) {
        if (action == NEW_ACTION) canvasNew = true else model.openProject(action)
    }
    fun guardProjectAction(action: String) {
        recentsOpen = false
        if (model.projectDirty) unsavedPrompt = action else runProjectAction(action)
    }
    fun saveProjectTo(uri: String, fresh: Boolean, then: String?) {
        canvasView[0]?.flushPendingEdit()
        val thumb = canvasView[0]?.projectThumbnail()
        model.saveProject(uri, thumb, fresh) { ok -> if (ok && then != null) runProjectAction(then) }
    }
    val createProject = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ProjectArchive.MIME),
    ) { uri ->
        val then = afterSaveAs
        afterSaveAs = null
        if (uri == null) return@rememberLauncherForActivityResult
        projectFiles.keep(uri)
        saveProjectTo(uri.toString(), fresh = true, then = then)
    }
    val openProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        projectFiles.keep(uri)
        guardProjectAction(uri.toString())
    }
    fun saveProjectAs(then: String?) {
        if (model.projectBusy) return
        afterSaveAs = then
        createProject.launch(model.suggestedProjectName())
    }
    fun saveProject(then: String?) {
        val uri = model.projectUri
        if (uri == null) saveProjectAs(then) else saveProjectTo(uri, fresh = false, then = then)
    }
    fun browseProjects() {
        recentsOpen = false
        openProject.launch(arrayOf(ProjectArchive.MIME, "application/octet-stream"))
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
                closePanels()
                guardProjectAction(NEW_ACTION)
            },
            onOpenProject = {
                closePanels()
                if (!model.projectBusy) {
                    if (model.recentProjects.isEmpty()) browseProjects() else recentsOpen = true
                }
            },
            onSaveProject = {
                closePanels()
                saveProject(null)
            },
            onSaveProjectAs = {
                closePanels()
                saveProjectAs(null)
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
            onExport = {
                if (!exportBusy) {
                    settingsTool = null
                    colorPickerOpen = false
                    canvasNew = false
                    canvasClear = false
                    canvasBackground = false
                    exportOpen = true
                }
            },
            onHideToolbar = {
                val modalOpen = canvasNew || canvasClear || canvasBackground || exportOpen ||
                    unsavedPrompt != null || recentsOpen
                if (!modalOpen) {
                    settingsTool = null
                    colorPickerOpen = false
                    canvasView[0]?.refitOnToggle(0f)
                    model.updateToolbarHidden(true)
                }
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
    if (exportBusy || model.projectBusy) {
        BackHandler { }
    }
    val incoming = model.incomingProject
    LaunchedEffect(incoming) {
        if (incoming == null) return@LaunchedEffect
        model.consumeIncomingProject()
        closePanels()
        guardProjectAction(incoming)
    }
    val projectEvent = model.projectEvent
    LaunchedEffect(projectEvent) {
        val message = when (projectEvent ?: return@LaunchedEffect) {
            ProjectEvent.SAVED -> R.string.project_saved
            ProjectEvent.SAVE_FAILED -> R.string.project_save_failed
            ProjectEvent.OPEN_NEWER -> R.string.project_open_newer
            ProjectEvent.OPEN_FAILED -> R.string.project_open_failed
        }
        model.consumeProjectEvent()
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
    val projectLabel = @Composable { modifier: Modifier ->
        Text(
            text = model.projectName ?: stringResource(R.string.project_untitled),
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
                .padding(8.dp)
                .widthIn(max = 320.dp)
                .background(Color.White.copy(alpha = 0.8f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("project_name"),
        )
    }
    val hidden = model.toolbarHidden
    LaunchedEffect(hidden) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (hidden) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // Fade only: the canvas resizes once, when the fade-out ends or the fade-in starts.
    val fade = fadeIn(tween(TOOLBAR_FADE_MS)) to fadeOut(tween(TOOLBAR_FADE_MS))
    val canvasArea = @Composable { modifier: Modifier ->
        Box(modifier) {
            canvas(Modifier.fillMaxSize())
            if (model.eyedropperActive) EyedropperCancel(cancelEyedropper)
            if (fillBusy) FillBusy(Modifier.align(Alignment.TopCenter))
            if (exportBusy) FillBusy(Modifier.align(Alignment.TopCenter), tag = "export_busy")
            if (model.projectBusy) FillBusy(Modifier.align(Alignment.TopCenter), tag = "project_busy")
            if (!hidden) projectLabel(Modifier.align(Alignment.TopStart))
            AnimatedVisibility(
                visible = hidden,
                enter = fade.first,
                exit = fade.second,
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                ToolbarRestoreButton(onClick = {
                    canvasView[0]?.refitOnToggle(ViewTransform.FIT_MARGIN_DP)
                    model.updateToolbarHidden(false)
                })
            }
        }
    }
    if (compact) {
        Scaffold(
            bottomBar = {
                AnimatedVisibility(visible = !hidden, enter = fade.first, exit = fade.second) {
                    toolbar(false, Modifier)
                }
            },
        ) { padding ->
            canvasArea(Modifier.padding(padding).fillMaxSize())
        }
    } else {
        Scaffold { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                AnimatedVisibility(
                    visible = !hidden,
                    enter = fade.first,
                    exit = fade.second,
                    modifier = Modifier.zIndex(1f),
                ) {
                    toolbar(true, Modifier.fillMaxHeight().width(80.dp))
                }
                canvasArea(Modifier.weight(1f).fillMaxHeight())
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
    val prompt = unsavedPrompt
    if (prompt != null) {
        UnsavedChangesDialog(
            name = model.projectName ?: stringResource(R.string.project_untitled),
            onSave = {
                unsavedPrompt = null
                saveProject(then = prompt)
            },
            onDiscard = {
                unsavedPrompt = null
                runProjectAction(prompt)
            },
            onCancel = { unsavedPrompt = null },
        )
    }
    if (recentsOpen) {
        RecentProjectsDialog(
            recents = model.recentProjects,
            onOpen = { guardProjectAction(it) },
            onRemove = { model.removeRecentProject(it) },
            onBrowse = { browseProjects() },
            onDismiss = { recentsOpen = false },
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
    if (exportOpen) {
        ExportDialog(
            format = model.exportFormat,
            quality = model.exportQuality,
            transparent = model.exportTransparent,
            onFormat = { model.updateExportFormat(it) },
            onQuality = { model.updateExportQuality(it) },
            onTransparent = { model.updateExportTransparent(it) },
            onSave = { requestExport(share = false) },
            onShare = { requestExport(share = true) },
            onDismiss = { exportOpen = false },
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

private const val NEW_ACTION = "new"
private const val TOOLBAR_FADE_MS = 200

@Composable
private fun ToolbarRestoreButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val description = stringResource(R.string.toolbar_show)
    Box(
        modifier = Modifier
            .padding(16.dp)
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (pressed) 1f else 0.8f))
            .border(1.dp, Color.Black, CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("toolbar_restore"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.FullscreenExit, contentDescription = null, tint = Color.Black)
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
                        // The row's selectable() handles the tap; the radio only shows the choice.
                        RadioButton(selected = itemIndex == index, onClick = null)
                        Text(text = label, color = Color.Black, modifier = Modifier.padding(start = 12.dp))
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
private fun FillBusy(modifier: Modifier = Modifier, tag: String = "fill_busy") {
    Box(
        modifier = modifier
            .padding(12.dp)
            .size(48.dp)
            .background(Color.White)
            .border(2.dp, Color.Black)
            .testTag(tag),
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
