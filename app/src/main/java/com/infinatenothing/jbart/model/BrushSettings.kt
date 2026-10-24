package com.infinatenothing.jbart.model

data class BrushSettings(
    val sizeDp: Float = DEFAULT_SIZE_DP,
    val opacity: Float = DEFAULT_OPACITY,
) {
    companion object {
        const val MIN_SIZE_DP = 1f
        const val MAX_SIZE_DP = 64f
        const val MIN_OPACITY = 0.1f
        const val MAX_OPACITY = 1f
        const val DEFAULT_SIZE_DP = 4f
        const val DEFAULT_OPACITY = 1f
        const val DEFAULT_ERASER_SIZE_DP = 16f
    }
}

/** Clamps size to 1–64 dp and opacity to 0.1–1. Eraser opacity is stored but not used when drawing. */
fun clampBrushSettings(settings: BrushSettings): BrushSettings {
    return BrushSettings(
        sizeDp = settings.sizeDp.coerceIn(BrushSettings.MIN_SIZE_DP, BrushSettings.MAX_SIZE_DP),
        opacity = settings.opacity.coerceIn(BrushSettings.MIN_OPACITY, BrushSettings.MAX_OPACITY),
    )
}
