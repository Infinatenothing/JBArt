package com.infinatenothing.jbart.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infinatenothing.jbart.R
import kotlin.math.roundToInt

private val Ink = Color.Black
private val Paper = Color.White
private val TrackIdle = Color(0xFF4A4A4A)

@Composable
fun FillSettingsPanel(
    tolerance: Int,
    gapDp: Int,
    onTolerance: (Int) -> Unit,
    onGap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shownTolerance = tolerance.coerceIn(0, 255)
    val shownGap = gapDp.coerceIn(0, 10)
    val sliderColors = SliderDefaults.colors(
        thumbColor = Ink,
        activeTrackColor = Ink,
        inactiveTrackColor = TrackIdle,
        activeTickColor = Ink,
        inactiveTickColor = TrackIdle,
    )
    Column(
        modifier = modifier
            .testTag("fill_settings")
            .background(Paper)
            .border(2.dp, Ink)
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_fill),
            color = Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.fill_tolerance, shownTolerance),
            color = Ink,
            fontSize = 16.sp,
        )
        Slider(
            value = shownTolerance.toFloat(),
            onValueChange = { onTolerance(it.roundToInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            colors = sliderColors,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("fill_tolerance_slider"),
        )
        Text(
            text = if (shownGap == 0) {
                stringResource(R.string.fill_gap_off)
            } else {
                stringResource(R.string.fill_gap_value, shownGap)
            },
            color = Ink,
            fontSize = 16.sp,
        )
        Slider(
            value = shownGap.toFloat(),
            onValueChange = { onGap(it.roundToInt().coerceIn(0, 10)) },
            valueRange = 0f..10f,
            steps = 9,
            colors = sliderColors,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("fill_gap_slider"),
        )
        Text(
            text = stringResource(R.string.fill_gap_hint),
            color = Ink,
            fontSize = 14.sp,
        )
    }
}
