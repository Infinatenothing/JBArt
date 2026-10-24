package com.infinatenothing.jbart

import com.infinatenothing.jbart.model.BrushSettings
import com.infinatenothing.jbart.model.clampBrushSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class BrushSettingsTest {
    @Test
    fun defaultsAreBrushSizedAndOpaque() {
        val settings = BrushSettings()
        assertEquals(4f, settings.sizeDp, 0.0001f)
        assertEquals(1f, settings.opacity, 0.0001f)
        assertEquals(16f, BrushSettings.DEFAULT_ERASER_SIZE_DP, 0.0001f)
    }

    @Test
    fun clampPullsSizeAndOpacityIntoRange() {
        val low = clampBrushSettings(BrushSettings(sizeDp = -8f, opacity = 0f))
        assertEquals(BrushSettings.MIN_SIZE_DP, low.sizeDp, 0.0001f)
        assertEquals(BrushSettings.MIN_OPACITY, low.opacity, 0.0001f)

        val high = clampBrushSettings(BrushSettings(sizeDp = 200f, opacity = 4f))
        assertEquals(BrushSettings.MAX_SIZE_DP, high.sizeDp, 0.0001f)
        assertEquals(BrushSettings.MAX_OPACITY, high.opacity, 0.0001f)

        val inside = clampBrushSettings(BrushSettings(sizeDp = 16f, opacity = 0.3f))
        assertEquals(16f, inside.sizeDp, 0.0001f)
        assertEquals(0.3f, inside.opacity, 0.0001f)
    }
}
