package com.infinatenothing.jbart.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infinatenothing.jbart.R
import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.Tool
import com.infinatenothing.jbart.model.clampBrushSettings
import kotlin.math.roundToInt

private val Ink = Color.Black
private val Paper = Color.White
private val TrackIdle = Color(0xFF4A4A4A)

@Composable
fun BrushSettingsPanel(
    tool: Tool,
    settings: BrushSettings,
    onSettings: (BrushSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clamped = clampBrushSettings(settings)
    val brush = tool == Tool.BRUSH
    val sliderColors = SliderDefaults.colors(
        thumbColor = Ink,
        activeTrackColor = Ink,
        inactiveTrackColor = TrackIdle,
        activeTickColor = Ink,
        inactiveTickColor = TrackIdle,
    )
    Column(
        modifier = modifier
            .testTag(if (brush) "brush_settings" else "eraser_settings")
            .background(Paper)
            .border(2.dp, Ink)
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(if (brush) R.string.settings_brush else R.string.settings_eraser),
            color = Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            val dotAlpha = if (brush) clamped.opacity else 1f
            Box(
                modifier = Modifier
                    .size(clamped.sizeDp.dp)
                    .clip(CircleShape)
                    .background(Ink.copy(alpha = dotAlpha)),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_size, clamped.sizeDp),
            color = Ink,
            fontSize = 16.sp,
        )
        Slider(
            value = clamped.sizeDp,
            onValueChange = { onSettings(clampBrushSettings(clamped.copy(sizeDp = it))) },
            valueRange = BrushSettings.MIN_SIZE_DP..BrushSettings.MAX_SIZE_DP,
            colors = sliderColors,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(if (brush) "brush_size_slider" else "eraser_size_slider"),
        )
        if (brush) {
            Text(
                text = stringResource(R.string.settings_opacity, (clamped.opacity * 100f).roundToInt()),
                color = Ink,
                fontSize = 16.sp,
            )
            Slider(
                value = clamped.opacity,
                onValueChange = { onSettings(clampBrushSettings(clamped.copy(opacity = it))) },
                valueRange = BrushSettings.MIN_OPACITY..BrushSettings.MAX_OPACITY,
                colors = sliderColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("brush_opacity_slider"),
            )
        }
    }
}
