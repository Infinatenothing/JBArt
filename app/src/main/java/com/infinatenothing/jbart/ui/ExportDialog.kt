package com.infinatenothing.jbart.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.infinatenothing.jbart.R
import com.infinatenothing.jbart.export.ExportFormat
import kotlin.math.roundToInt

@Composable
fun ExportDialog(
    format: ExportFormat,
    quality: Int,
    transparent: Boolean,
    onFormat: (ExportFormat) -> Unit,
    onQuality: (Int) -> Unit,
    onTransparent: (Boolean) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ink = Color.Black
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("export_dialog"),
        containerColor = Color.White,
        titleContentColor = ink,
        textContentColor = ink,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column {
                FormatRow(ExportFormat.PNG, format, R.string.export_format_png, "export_format_png", onFormat)
                FormatRow(ExportFormat.JPEG, format, R.string.export_format_jpeg, "export_format_jpeg", onFormat)
                FormatRow(ExportFormat.BMP, format, R.string.export_format_bmp, "export_format_bmp", onFormat)
                FormatRow(ExportFormat.GIF, format, R.string.export_format_gif, "export_format_gif", onFormat)
                FormatRow(ExportFormat.PDF, format, R.string.export_format_pdf, "export_format_pdf", onFormat)
                FormatRow(ExportFormat.SVG, format, R.string.export_format_svg, "export_format_svg", onFormat)
                if (format == ExportFormat.JPEG) {
                    Text(stringResource(R.string.export_quality, quality), color = ink)
                    Slider(
                        value = quality.toFloat(),
                        onValueChange = { onQuality(it.roundToInt().coerceIn(50, 100)) },
                        valueRange = 50f..100f,
                        steps = 49,
                        modifier = Modifier.testTag("export_quality_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = ink,
                            activeTrackColor = ink,
                            inactiveTrackColor = Color(0xFF4A4A4A),
                        ),
                    )
                }
                if (format.supportsTransparent) {
                    Row(
                        modifier = Modifier
                            .testTag("export_transparent")
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = transparent,
                                role = Role.Checkbox,
                                onValueChange = onTransparent,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = transparent,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = ink,
                                uncheckedColor = ink,
                                checkmarkColor = Color.White,
                            ),
                        )
                        Text(stringResource(R.string.export_transparent), color = ink)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onShare, modifier = Modifier.testTag("export_share")) {
                    Text(stringResource(R.string.export_share), color = ink)
                }
                TextButton(onClick = onSave, modifier = Modifier.testTag("export_save")) {
                    Text(stringResource(R.string.export_save), color = ink)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("export_cancel")) {
                Text(stringResource(R.string.export_cancel), color = ink)
            }
        },
    )
}

@Composable
private fun FormatRow(
    value: ExportFormat,
    selected: ExportFormat,
    label: Int,
    tag: String,
    onFormat: (ExportFormat) -> Unit,
) {
    Row(
        modifier = Modifier
            .testTag(tag)
            .heightIn(min = 48.dp)
            .selectable(
                selected = value == selected,
                role = Role.RadioButton,
                onClick = { onFormat(value) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = value == selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = Color.Black,
                unselectedColor = Color.Black,
            ),
        )
        Text(stringResource(label), color = Color.Black)
    }
}
