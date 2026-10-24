package com.infinatenothing.jbart.ui.toolbar

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.infinatenothing.jbart.R
import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.Tool

@Composable
fun DrawingToolbar(
    vertical: Boolean,
    anchorSettings: Boolean,
    selectedTool: Tool,
    canUndo: Boolean,
    canRedo: Boolean,
    brushSettings: BrushSettings,
    eraserSettings: BrushSettings,
    settingsTool: Tool?,
    onSelectTool: (Tool) -> Unit,
    onOpenSettings: (Tool) -> Unit,
    onDismissSettings: () -> Unit,
    onBrushSettings: (BrushSettings) -> Unit,
    onEraserSettings: (BrushSettings) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            icon = Icons.Outlined.CleaningServices,
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
            onClick = { onSelectTool(Tool.FILL) },
        ),
        Item(
            tag = "tool_color",
            description = stringResource(R.string.action_color),
            icon = Icons.Filled.Palette,
            selected = false,
            enabled = true,
            onClick = {},
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
            tag = "tool_export",
            description = stringResource(R.string.action_export),
            icon = Icons.Filled.MoreVert,
            selected = false,
            enabled = true,
            onClick = {},
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
                if (anchorSettings && item.settingsTool != null && item.settingsTool == settingsTool) {
                    SettingsPopup(
                        tool = item.settingsTool,
                        brushSettings = brushSettings,
                        eraserSettings = eraserSettings,
                        onBrushSettings = onBrushSettings,
                        onEraserSettings = onEraserSettings,
                        onDismiss = onDismissSettings,
                    )
                }
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
            }
            VerticalDivider(modifier = Modifier.fillMaxHeight(), thickness = 1.dp, color = outline)
        }
    } else {
        Column(placed.fillMaxWidth()) {
            HorizontalDivider(thickness = 1.dp, color = outline)
            BottomAppBar(containerColor = container) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    buttons()
                }
            }
        }
    }
}

@Composable
private fun SettingsPopup(
    tool: Tool,
    brushSettings: BrushSettings,
    eraserSettings: BrushSettings,
    onBrushSettings: (BrushSettings) -> Unit,
    onEraserSettings: (BrushSettings) -> Unit,
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
        BrushSettingsPanel(
            tool = tool,
            settings = if (eraser) eraserSettings else brushSettings,
            onSettings = if (eraser) onEraserSettings else onBrushSettings,
            modifier = Modifier.width(280.dp),
        )
    }
}

private data class Item(
    val tag: String,
    val description: String,
    val icon: ImageVector,
    val selected: Boolean,
    val enabled: Boolean,
    val settingsTool: Tool? = null,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

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
    }
}
