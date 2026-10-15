package com.infinatenothing.jbart.ui.toolbar

import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.infinatenothing.jbart.R
import com.infinatenothing.jbart.model.Tool

@Composable
fun DrawingToolbar(
    vertical: Boolean,
    selectedTool: Tool,
    canUndo: Boolean,
    canRedo: Boolean,
    onSelectTool: (Tool) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        Item("tool_brush", stringResource(R.string.tool_brush), Icons.Filled.Brush, selectedTool == Tool.BRUSH, true) {
            onSelectTool(Tool.BRUSH)
        },
        Item("tool_eraser", stringResource(R.string.tool_eraser), Icons.Outlined.CleaningServices, selectedTool == Tool.ERASER, true) {
            onSelectTool(Tool.ERASER)
        },
        Item("tool_line", stringResource(R.string.tool_line), Icons.AutoMirrored.Filled.ShowChart, selectedTool == Tool.LINE, true) {
            onSelectTool(Tool.LINE)
        },
        Item("tool_shape", stringResource(R.string.tool_shape), Icons.Filled.CropSquare, selectedTool == Tool.SHAPE, true) {
            onSelectTool(Tool.SHAPE)
        },
        Item("tool_fill", stringResource(R.string.tool_fill), Icons.Filled.FormatColorFill, selectedTool == Tool.FILL, true) {
            onSelectTool(Tool.FILL)
        },
        Item("tool_color", stringResource(R.string.action_color), Icons.Filled.Palette, false, true) {},
        Item("tool_undo", stringResource(R.string.action_undo), Icons.AutoMirrored.Filled.Undo, false, canUndo, onUndo),
        Item("tool_redo", stringResource(R.string.action_redo), Icons.AutoMirrored.Filled.Redo, false, canRedo, onRedo),
        Item("tool_export", stringResource(R.string.action_export), Icons.Filled.MoreVert, false, true) {},
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
    if (vertical) {
        Row(placed.fillMaxHeight()) {
            NavigationRail(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                containerColor = container,
            ) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    items.forEach { ToolButton(it) }
                }
            }
            VerticalDivider(modifier = Modifier.fillMaxHeight(), thickness = 1.dp, color = outline)
        }
    } else {
        Column(placed.fillMaxWidth()) {
            HorizontalDivider(thickness = 1.dp, color = outline)
            BottomAppBar(containerColor = container) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    items.forEach { ToolButton(it) }
                }
            }
        }
    }
}

private data class Item(
    val tag: String,
    val description: String,
    val icon: ImageVector,
    val selected: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun ToolButton(item: Item) {
    val buttonModifier = Modifier.testTag(item.tag)
    if (item.selected) {
        FilledIconButton(onClick = item.onClick, enabled = item.enabled, modifier = buttonModifier) {
            Icon(item.icon, contentDescription = item.description)
        }
    } else {
        IconButton(onClick = item.onClick, enabled = item.enabled, modifier = buttonModifier) {
            Icon(item.icon, contentDescription = item.description, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}
