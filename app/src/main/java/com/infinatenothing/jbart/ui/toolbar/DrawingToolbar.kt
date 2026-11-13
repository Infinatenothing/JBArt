package com.infinatenothing.jbart.ui.toolbar

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.infinatenothing.jbart.R
import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.ColorMath
import com.infinatenothing.jbart.model.ShapeKind
import com.infinatenothing.jbart.model.Tool
import kotlin.math.roundToInt

@Composable
fun DrawingToolbar(
    vertical: Boolean,
    anchorSettings: Boolean,
    selectedTool: Tool,
    canUndo: Boolean,
    canRedo: Boolean,
    brushSettings: BrushSettings,
    eraserSettings: BrushSettings,
    lineSnapEnabled: Boolean,
    onLineSnap: (Boolean) -> Unit,
    shapeKind: ShapeKind,
    shapeConstrain: Boolean,
    shapeFilled: Boolean,
    onShapeKind: (ShapeKind) -> Unit,
    onShapeConstrain: (Boolean) -> Unit,
    onShapeFilled: (Boolean) -> Unit,
    fillTolerance: Int,
    fillGapDp: Int,
    onFillTolerance: (Int) -> Unit,
    onFillGap: (Int) -> Unit,
    settingsTool: Tool?,
    onSelectTool: (Tool) -> Unit,
    onOpenSettings: (Tool) -> Unit,
    onDismissSettings: () -> Unit,
    onBrushSettings: (BrushSettings) -> Unit,
    onEraserSettings: (BrushSettings) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    strokeColor: Int,
    colorPopup: (@Composable () -> Unit)?,
    onOpenColor: () -> Unit,
    onDismissColor: () -> Unit,
    zoomScale: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onNewCanvas: () -> Unit,
    onOpenProject: () -> Unit,
    onSaveProject: () -> Unit,
    onSaveProjectAs: () -> Unit,
    onClearCanvas: () -> Unit,
    onBackgroundColor: () -> Unit,
    onExport: () -> Unit,
    onHideToolbar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasMenu by remember { mutableStateOf(false) }
    val items = listOf(
        Item(
            tag = "tool_brush",
            description = stringResource(R.string.tool_brush),
            icon = Icons.Filled.Brush,
            selected = selectedTool == Tool.BRUSH,
            enabled = true,
            settingsTool = Tool.BRUSH,
            onClick = {
                if (selectedTool == Tool.BRUSH) onOpenSettings(Tool.BRUSH) else onSelectTool(Tool.BRUSH)
            },
            onLongClick = { onOpenSettings(Tool.BRUSH) },
        ),
        Item(
            tag = "tool_eraser",
            description = stringResource(R.string.tool_eraser),
            icon = ImageVector.vectorResource(R.drawable.ic_ink_eraser),
            selected = selectedTool == Tool.ERASER,
            enabled = true,
            settingsTool = Tool.ERASER,
            onClick = {
                if (selectedTool == Tool.ERASER) onOpenSettings(Tool.ERASER) else onSelectTool(Tool.ERASER)
            },
            onLongClick = { onOpenSettings(Tool.ERASER) },
        ),
        Item(
            tag = "tool_line",
            description = stringResource(R.string.tool_line),
            icon = Icons.AutoMirrored.Filled.ShowChart,
            selected = selectedTool == Tool.LINE,
            enabled = true,
            onClick = { onSelectTool(Tool.LINE) },
        ),
        Item(
            tag = "tool_shape",
            description = stringResource(R.string.tool_shape),
            icon = Icons.Filled.CropSquare,
            selected = selectedTool == Tool.SHAPE,
            enabled = true,
            onClick = { onSelectTool(Tool.SHAPE) },
        ),
        Item(
            tag = "tool_fill",
            description = stringResource(R.string.tool_fill),
            icon = Icons.Filled.FormatColorFill,
            selected = selectedTool == Tool.FILL,
            enabled = true,
            settingsTool = Tool.FILL,
            onClick = {
                if (selectedTool == Tool.FILL) onOpenSettings(Tool.FILL) else onSelectTool(Tool.FILL)
            },
        ),
        Item(
            tag = "tool_color",
            description = stringResource(R.string.action_color),
            icon = Icons.Filled.Palette,
            selected = false,
            enabled = true,
            markColor = strokeColor,
            onClick = onOpenColor,
        ),
        Item(
            tag = "tool_undo",
            description = stringResource(R.string.action_undo),
            icon = Icons.AutoMirrored.Filled.Undo,
            selected = false,
            enabled = canUndo,
            onClick = onUndo,
        ),
        Item(
            tag = "tool_redo",
            description = stringResource(R.string.action_redo),
            icon = Icons.AutoMirrored.Filled.Redo,
            selected = false,
            enabled = canRedo,
            onClick = onRedo,
        ),
        Item(
            tag = "tool_canvas",
            description = stringResource(R.string.action_canvas),
            icon = Icons.AutoMirrored.Filled.InsertDriveFile,
            selected = canvasMenu,
            enabled = true,
            onClick = { canvasMenu = true },
        ),
        Item(
            tag = "tool_export",
            description = stringResource(R.string.action_export),
            icon = Icons.Filled.Save,
            selected = false,
            enabled = true,
            onClick = onExport,
        ),
        Item(
            tag = "tool_hide_toolbar",
            description = stringResource(R.string.toolbar_hide),
            icon = if (vertical) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.Filled.KeyboardArrowDown,
            selected = false,
            enabled = true,
            onClick = {
                canvasMenu = false
                onHideToolbar()
            },
        ),
    )
    SideEffect {
        Log.d("JBArtLayout", "toolbar composed vertical=$vertical items=${items.size}")
    }
    val placed = modifier
        .testTag("toolbar")
        .onGloballyPositioned { coords ->
            Log.d("JBArtLayout", "toolbar bounds=${coords.boundsInWindow()}")
        }
    val outline = MaterialTheme.colorScheme.outline
    val container = MaterialTheme.colorScheme.surfaceVariant
    val buttons = @Composable {
        items.forEach { item ->
            Box {
                ToolButton(item)
                if (item.tag == "tool_color" && colorPopup != null) {
                    ColorPopup(onDismiss = onDismissColor, content = colorPopup)
                }
                if (item.tag == "tool_canvas") {
                    CanvasMenu(
                        expanded = canvasMenu,
                        onDismiss = { canvasMenu = false },
                        onNew = {
                            canvasMenu = false
                            onNewCanvas()
                        },
                        onOpen = {
                            canvasMenu = false
                            onOpenProject()
                        },
                        onSave = {
                            canvasMenu = false
                            onSaveProject()
                        },
                        onSaveAs = {
                            canvasMenu = false
                            onSaveProjectAs()
                        },
                        onClear = {
                            canvasMenu = false
                            onClearCanvas()
                        },
                        onBackground = {
                            canvasMenu = false
                            onBackgroundColor()
                        },
                    )
                }
                if (anchorSettings && item.settingsTool != null && item.settingsTool == settingsTool) {
                    SettingsPopup(
                        tool = item.settingsTool,
                        brushSettings = brushSettings,
                        eraserSettings = eraserSettings,
                        onBrushSettings = onBrushSettings,
                        onEraserSettings = onEraserSettings,
                        fillTolerance = fillTolerance,
                        fillGapDp = fillGapDp,
                        onFillTolerance = onFillTolerance,
                        onFillGap = onFillGap,
                        onDismiss = onDismissSettings,
                    )
                }
            }
            if (item.tag == "tool_line" && selectedTool == Tool.LINE) {
                SnapToggle(enabled = lineSnapEnabled, onToggle = onLineSnap)
            }
            if (item.tag == "tool_shape" && selectedTool == Tool.SHAPE) {
                ShapeKindButton(ShapeKind.RECTANGLE, shapeKind, onShapeKind)
                ShapeKindButton(ShapeKind.ELLIPSE, shapeKind, onShapeKind)
                ShapeKindButton(ShapeKind.TRIANGLE, shapeKind, onShapeKind)
                ShapeKindButton(ShapeKind.ARROW, shapeKind, onShapeKind)
                ShapeFlagToggle(
                    tag = "shape_constrain_toggle",
                    description = stringResource(R.string.shape_constrain),
                    icon = Icons.Filled.AspectRatio,
                    enabled = shapeConstrain,
                    onToggle = onShapeConstrain,
                )
                ShapeFlagToggle(
                    tag = "shape_fill_toggle",
                    description = stringResource(R.string.shape_filled),
                    icon = Icons.Filled.FormatColorFill,
                    enabled = shapeFilled,
                    onToggle = onShapeFilled,
                )
            }
        }
    }
    if (vertical) {
        Row(placed.fillMaxHeight()) {
            NavigationRail(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                containerColor = container,
            ) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    buttons()
                }
                ZoomCluster(zoomScale, onZoomIn, onZoomOut, stacked = true)
            }
            VerticalDivider(modifier = Modifier.fillMaxHeight(), thickness = 1.dp, color = outline)
        }
    } else {
        Column(placed.fillMaxWidth()) {
            HorizontalDivider(thickness = 1.dp, color = outline)
            BottomAppBar(containerColor = container) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        buttons()
                    }
                    ZoomCluster(zoomScale, onZoomIn, onZoomOut, stacked = false)
                }
            }
        }
    }
}

@Composable
private fun CanvasMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onClear: () -> Unit,
    onBackground: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.canvas_new)) },
            onClick = onNew,
            modifier = Modifier.testTag("canvas_new"),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.project_open)) },
            onClick = onOpen,
            modifier = Modifier.testTag("project_open"),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.project_save)) },
            onClick = onSave,
            modifier = Modifier.testTag("project_save"),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.project_save_as)) },
            onClick = onSaveAs,
            modifier = Modifier.testTag("project_save_as"),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.canvas_clear)) },
            onClick = onClear,
            modifier = Modifier.testTag("canvas_clear"),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.canvas_background)) },
            onClick = onBackground,
            modifier = Modifier.testTag("canvas_background"),
        )
    }
}

@Composable
private fun SettingsPopup(
    tool: Tool,
    brushSettings: BrushSettings,
    eraserSettings: BrushSettings,
    onBrushSettings: (BrushSettings) -> Unit,
    onEraserSettings: (BrushSettings) -> Unit,
    fillTolerance: Int,
    fillGapDp: Int,
    onFillTolerance: (Int) -> Unit,
    onFillGap: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val offsetX = with(density) { 56.dp.roundToPx() } * direction
    val eraser = tool == Tool.ERASER
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(offsetX, 0),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        if (tool == Tool.FILL) {
            FillSettingsPanel(
                tolerance = fillTolerance,
                gapDp = fillGapDp,
                onTolerance = onFillTolerance,
                onGap = onFillGap,
                modifier = Modifier.width(280.dp),
            )
        } else {
            BrushSettingsPanel(
                tool = tool,
                settings = if (eraser) eraserSettings else brushSettings,
                onSettings = if (eraser) onEraserSettings else onBrushSettings,
                modifier = Modifier.width(280.dp),
            )
        }
    }
}

@Composable
private fun ColorPopup(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val offsetX = with(density) { 56.dp.roundToPx() } * direction
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(offsetX, 0),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Box(Modifier.width(320.dp)) {
            content()
        }
    }
}

private data class Item(
    val tag: String,
    val description: String,
    val icon: ImageVector,
    val selected: Boolean,
    val enabled: Boolean,
    val settingsTool: Tool? = null,
    val markColor: Int? = null,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

@Composable
private fun SnapToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val background = if (enabled) MaterialTheme.colorScheme.primary else Color.Transparent
    val tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .testTag("line_snap_toggle")
            .size(48.dp)
            .clip(CircleShape)
            .background(background)
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Straighten,
            contentDescription = stringResource(R.string.snap_angle),
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun ShapeKindButton(
    kind: ShapeKind,
    selectedKind: ShapeKind,
    onSelect: (ShapeKind) -> Unit,
) {
    val selected = kind == selectedKind
    val (tag, description, icon) = when (kind) {
        ShapeKind.RECTANGLE -> Triple(
            "shape_kind_rectangle",
            stringResource(R.string.shape_rectangle),
            Icons.Filled.CropSquare,
        )
        ShapeKind.ELLIPSE -> Triple(
            "shape_kind_ellipse",
            stringResource(R.string.shape_ellipse),
            Icons.Outlined.Circle,
        )
        ShapeKind.TRIANGLE -> Triple(
            "shape_kind_triangle",
            stringResource(R.string.shape_triangle),
            Icons.Filled.ChangeHistory,
        )
        ShapeKind.ARROW -> Triple(
            "shape_kind_arrow",
            stringResource(R.string.shape_arrow),
            Icons.Filled.NorthEast,
        )
    }
    val background = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .testTag(tag)
            .size(48.dp)
            .clip(CircleShape)
            .background(background)
            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(kind) }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ShapeFlagToggle(
    tag: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val background = if (enabled) MaterialTheme.colorScheme.primary else Color.Transparent
    val tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .testTag(tag)
            .size(48.dp)
            .clip(CircleShape)
            .background(background)
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ZoomCluster(
    scale: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    stacked: Boolean,
) {
    val percent = (scale * 100f).roundToInt()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.zoom_percent, percent),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("zoom_label"),
        )
        if (stacked) {
            ZoomSymbol("+", stringResource(R.string.zoom_in), "zoom_in", onZoomIn)
            ZoomSymbol("-", stringResource(R.string.zoom_out), "zoom_out", onZoomOut)
        } else {
            Row {
                ZoomSymbol("+", stringResource(R.string.zoom_in), "zoom_in", onZoomIn)
                ZoomSymbol("-", stringResource(R.string.zoom_out), "zoom_out", onZoomOut)
            }
        }
    }
}

@Composable
private fun ZoomSymbol(
    symbol: String,
    description: String,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .testTag(tag)
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun ToolButton(item: Item) {
    val background = if (item.selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val tint = when {
        !item.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        item.selected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .testTag(item.tag)
            .size(48.dp)
            .clip(CircleShape)
            .background(background)
            .combinedClickable(
                enabled = item.enabled,
                onClick = item.onClick,
                onLongClick = item.onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(item.icon, contentDescription = item.description, tint = tint, modifier = Modifier.size(24.dp))
        val mark = item.markColor
        if (mark != null) {
            val edge = Color(ColorMath.contrastOn(mark))
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .size(14.dp)
                    .border(1.dp, edge, CircleShape)
                    .background(Color(mark), CircleShape),
            )
        }
    }
}
