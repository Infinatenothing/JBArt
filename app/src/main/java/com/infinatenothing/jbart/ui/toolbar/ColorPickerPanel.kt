package com.infinatenothing.jbart.ui.toolbar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infinatenothing.jbart.R
import com.infinatenothing.jbart.model.ColorMath
import com.infinatenothing.jbart.model.ColorSettings
import com.infinatenothing.jbart.model.ColorTarget
import kotlin.math.abs

@Composable
fun ColorPickerPanel(
    stroke: Int,
    fill: Int,
    target: ColorTarget,
    recent: List<Int>,
    onTarget: (ColorTarget) -> Unit,
    onStroke: (Int) -> Unit,
    onFill: (Int) -> Unit,
    onEyedropper: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = if (target == ColorTarget.FILL) fill else stroke
    var hsv by remember { mutableStateOf(hsvOf(active)) }
    LaunchedEffect(active, target) {
        val shown = ColorMath.hsvToArgb(hsv.h, hsv.s, hsv.v)
        if (!rgbClose(shown, active)) hsv = hsvOf(active)
    }
    val shown = ColorMath.hsvToArgb(hsv.h, hsv.s, hsv.v)
    val apply: (Int) -> Unit = { color ->
        if (target == ColorTarget.FILL) onFill(color) else onStroke(color)
    }
    val names = presetNames()
    Column(
        modifier = modifier
            .testTag("color_picker")
            .background(Color.White)
            .border(2.dp, Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.action_color),
            color = Color.Black,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TargetChip(
                tag = "color_target_stroke",
                label = stringResource(R.string.color_stroke),
                color = stroke,
                selected = target == ColorTarget.STROKE,
                onClick = { onTarget(ColorTarget.STROKE) },
                modifier = Modifier.weight(1f),
            )
            TargetChip(
                tag = "color_target_fill",
                label = stringResource(R.string.color_fill),
                color = fill,
                selected = target == ColorTarget.FILL,
                onClick = { onTarget(ColorTarget.FILL) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        ColorSettings.PALETTE.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { color ->
                    val index = ColorSettings.PALETTE.indexOf(color)
                    Swatch(
                        tag = "color_swatch_$index",
                        color = color,
                        label = names[index],
                        selected = color == active,
                        onClick = {
                            hsv = hsvOf(color)
                            apply(color)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SaturationValueSquare(
            hue = hsv.h,
            saturation = hsv.s,
            value = hsv.v,
            onChange = { saturation, value ->
                hsv = hsv.copy(s = saturation, v = value)
                apply(ColorMath.hsvToArgb(hsv.h, saturation, value))
            },
        )
        Spacer(Modifier.height(8.dp))
        HueSlider(
            hue = hsv.h,
            onChange = { hue ->
                hsv = hsv.copy(h = hue)
                apply(ColorMath.hsvToArgb(hue, hsv.s, hsv.v))
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val hex = ColorMath.toHex(shown)
            Box(
                modifier = Modifier
                    .testTag("color_preview")
                    .size(48.dp)
                    .border(2.dp, Color(ColorMath.contrastOn(shown)))
                    .background(Color(shown))
                    .semantics { contentDescription = hex },
            )
            Text(
                text = hex,
                modifier = Modifier.padding(start = 12.dp).testTag("color_hex"),
                color = Color.Black,
                fontSize = 16.sp,
            )
        }
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                recent.forEachIndexed { index, color ->
                    val hex = ColorMath.toHex(color)
                    Swatch(
                        tag = "color_recent_$index",
                        color = color,
                        label = hex,
                        selected = color == active,
                        onClick = {
                            hsv = hsvOf(color)
                            apply(color)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val eyedropper = stringResource(R.string.color_eyedropper)
        Row(
            modifier = Modifier
                .testTag("color_eyedropper")
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .border(1.dp, Color.Black)
                .semantics { contentDescription = eyedropper }
                .clickable(onClick = onEyedropper),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Colorize, contentDescription = null, tint = Color.Black)
            Text(
                text = eyedropper,
                modifier = Modifier.padding(start = 8.dp),
                color = Color.Black,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun presetNames(): List<String> {
    return listOf(
        stringResource(R.string.color_black),
        stringResource(R.string.color_white),
        stringResource(R.string.color_gray_dark),
        stringResource(R.string.color_gray),
        stringResource(R.string.color_gray_light),
        stringResource(R.string.color_red),
        stringResource(R.string.color_orange),
        stringResource(R.string.color_yellow),
        stringResource(R.string.color_green),
        stringResource(R.string.color_teal),
        stringResource(R.string.color_blue),
        stringResource(R.string.color_indigo),
        stringResource(R.string.color_purple),
        stringResource(R.string.color_pink),
        stringResource(R.string.color_brown),
        stringResource(R.string.color_tan),
    )
}

@Composable
private fun TargetChip(
    tag: String,
    label: String,
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .testTag(tag)
            .heightIn(min = 48.dp)
            .border(if (selected) 3.dp else 1.dp, Color(ColorMath.contrastOn(color)))
            .background(Color(color))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text = label, color = Color(ColorMath.contrastOn(color)), fontSize = 16.sp)
    }
}

@Composable
private fun Swatch(
    tag: String,
    color: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val mark = Color(ColorMath.contrastOn(color))
    Box(
        modifier = Modifier
            .testTag(tag)
            .size(48.dp)
            .semantics { contentDescription = label }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(4.dp)
            .border(if (selected) 3.dp else 1.dp, mark)
            .background(Color(color)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = mark, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SaturationValueSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    val hueColor = Color(ColorMath.hsvToArgb(hue, 1f, 1f))
    Box(
        modifier = Modifier
            .testTag("color_sv_square")
            .fillMaxWidth()
            .height(160.dp)
            .border(1.dp, Color.Black)
            .dragColor { offset, size ->
                val s = (offset.x / size.width).coerceIn(0f, 1f)
                val v = (1f - offset.y / size.height).coerceIn(0f, 1f)
                onChange(s, v)
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(hueColor)
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val center = Offset(saturation * size.width, (1f - value) * size.height)
            drawCircle(Color.White, radius = 8.dp.toPx(), center = center)
            drawCircle(Color.Black, radius = 8.dp.toPx(), center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        }
    }
}

@Composable
private fun HueSlider(hue: Float, onChange: (Float) -> Unit) {
    val stops = List(13) { index -> Color(ColorMath.hsvToArgb(index * 30f, 1f, 1f)) }
    Box(
        modifier = Modifier
            .testTag("color_hue_slider")
            .fillMaxWidth()
            .height(48.dp)
            .dragColor { offset, size ->
                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                onChange(fraction * 359.999f)
            },
    ) {
        Canvas(Modifier.matchParentSize().padding(vertical = 16.dp)) {
            drawRoundRect(Brush.horizontalGradient(stops))
            val x = (hue / 359.999f).coerceIn(0f, 1f) * size.width
            drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(x, size.height / 2f))
            drawCircle(
                Color.Black,
                radius = 10.dp.toPx(),
                center = Offset(x, size.height / 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun Modifier.dragColor(onPosition: (Offset, Size) -> Unit): Modifier {
    val current = rememberUpdatedState(onPosition)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            current.value(down.position, Size(size.width.toFloat(), size.height.toFloat()))
            drag(down.id) { change ->
                current.value(change.position, Size(size.width.toFloat(), size.height.toFloat()))
                change.consume()
            }
        }
    }
}

private data class Hsv(val h: Float, val s: Float, val v: Float)

private fun hsvOf(argb: Int): Hsv {
    val out = FloatArray(3)
    ColorMath.argbToHsv(argb, out)
    return Hsv(out[0], out[1], out[2])
}

private fun rgbClose(left: Int, right: Int): Boolean {
    return channelClose(left, right, 16) && channelClose(left, right, 8) && channelClose(left, right, 0)
}

private fun channelClose(left: Int, right: Int, shift: Int): Boolean {
    return abs(((left shr shift) and 0xFF) - ((right shr shift) and 0xFF)) <= 1
}
