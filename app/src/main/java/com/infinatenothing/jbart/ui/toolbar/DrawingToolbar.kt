package com.infinatenothing.jbart.ui.toolbar

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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
        Item(stringResource(R.string.tool_brush), Icons.Filled.Brush, selectedTool == Tool.BRUSH, true) {
            onSelectTool(Tool.BRUSH)
        },
        Item(stringResource(R.string.tool_eraser), Icons.Outlined.CleaningServices, selectedTool == Tool.ERASER, true) {
            onSelectTool(Tool.ERASER)
        },
        Item(stringResource(R.string.tool_line), Icons.AutoMirrored.Filled.ShowChart, selectedTool == Tool.LINE, true) {
            onSelectTool(Tool.LINE)
        },
        Item(stringResource(R.string.tool_shape), Icons.Filled.CropSquare, selectedTool == Tool.SHAPE, true) {
            onSelectTool(Tool.SHAPE)
        },
        Item(stringResource(R.string.tool_fill), Icons.Filled.FormatColorFill, selectedTool == Tool.FILL, true) {
            onSelectTool(Tool.FILL)
        },
        Item(stringResource(R.string.action_color), Icons.Filled.Palette, false, true) {},
        Item(stringResource(R.string.action_undo), Icons.AutoMirrored.Filled.Undo, false, canUndo, onUndo),
        Item(stringResource(R.string.action_redo), Icons.AutoMirrored.Filled.Redo, false, canRedo, onRedo),
        Item(stringResource(R.string.action_export), Icons.Filled.MoreVert, false, true) {},
    )
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        if (vertical) {
            Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                items.forEach { ToolButton(it) }
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                items.forEach { ToolButton(it) }
            }
        }
    }
}

private data class Item(
    val description: String,
    val icon: ImageVector,
    val selected: Boolean,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun ToolButton(item: Item) {
    val icon = @Composable { Icon(item.icon, contentDescription = item.description) }
    if (item.selected) {
        FilledIconButton(onClick = item.onClick, enabled = item.enabled, content = icon)
    } else {
        IconButton(onClick = item.onClick, enabled = item.enabled, content = icon)
    }
}
