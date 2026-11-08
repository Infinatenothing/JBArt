package com.infinatenothing.jbart

import com.infinatenothing.jbart.canvas.ViewTransform
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewTransformTest {
    @Test
    fun identityRoundTripMatchesScreenPixels() {
        val transform = ViewTransform()
        val canvas = FloatArray(2)
        val screen = FloatArray(2)
        transform.toCanvas(40f, 70f, canvas)
        assertEquals(40f, canvas[0], 0f)
        assertEquals(70f, canvas[1], 0f)
        transform.toScreen(canvas[0], canvas[1], screen)
        assertEquals(40f, screen[0], 0f)
        assertEquals(70f, screen[1], 0f)
    }

    @Test
    fun scaledRoundTripAndZoomAroundKeepTheFocusFixed() {
        val transform = ViewTransform(scale = 2f, tx = 10f, ty = -4f)
        val canvas = FloatArray(2)
        val screen = FloatArray(2)
        transform.toCanvas(50f, 36f, canvas)
        assertEquals(20f, canvas[0], 0.01f)
        assertEquals(20f, canvas[1], 0.01f)
        transform.toScreen(canvas[0], canvas[1], screen)
        assertEquals(50f, screen[0], 0.01f)
        assertEquals(36f, screen[1], 0.01f)

        transform.zoomAround(50f, 36f, 1.5f)
        assertEquals(3f, transform.scale, 0.001f)
        transform.toScreen(canvas[0], canvas[1], screen)
        assertEquals(50f, screen[0], 0.01f)
        assertEquals(36f, screen[1], 0.01f)
    }

    @Test
    fun pinchMovesThePreviousFocusOntoTheNewFocus() {
        val transform = ViewTransform(scale = 2f, tx = 0f, ty = 0f)
        val canvas = FloatArray(2)
        transform.toCanvas(20f, 40f, canvas)
        transform.pinch(20f, 40f, 30f, 55f, 2f)
        assertEquals(4f, transform.scale, 0.001f)
        val screen = FloatArray(2)
        transform.toScreen(canvas[0], canvas[1], screen)
        assertEquals(30f, screen[0], 0.01f)
        assertEquals(55f, screen[1], 0.01f)
    }

    @Test
    fun zoomStopsAtQuarterAndEightTimes() {
        val transform = ViewTransform(scale = 8f, tx = 12f, ty = 8f)
        transform.zoomAround(4f, 6f, 2f)
        assertEquals(ViewTransform.MAX_SCALE, transform.scale, 0f)
        assertEquals(12f, transform.tx, 0.01f)
        assertEquals(8f, transform.ty, 0.01f)

        transform.scale = ViewTransform.MIN_SCALE
        transform.tx = 3f
        transform.ty = 4f
        transform.zoomAround(10f, 10f, 0.5f)
        assertEquals(ViewTransform.MIN_SCALE, transform.scale, 0f)
        assertEquals(3f, transform.tx, 0.01f)
        assertEquals(4f, transform.ty, 0.01f)

        transform.scale = 7f
        transform.zoomAround(0f, 0f, ViewTransform.STEP)
        assertEquals(ViewTransform.MAX_SCALE, transform.scale, 0.001f)
    }

    @Test
    fun fitCentersPortraitAndLandscapeInsideTheMargin() {
        val landscape = ViewTransform()
        landscape.fit(1000f, 500f, 400f, 800f, 16f)
        assertEquals(368f / 1000f, landscape.scale, 0.0001f)
        assertEquals((400f - 1000f * landscape.scale) / 2f, landscape.tx, 0.01f)
        assertEquals((800f - 500f * landscape.scale) / 2f, landscape.ty, 0.01f)

        val portrait = ViewTransform()
        portrait.fit(500f, 1000f, 400f, 800f, 16f)
        assertEquals(368f / 500f, portrait.scale, 0.0001f)
        assertEquals((400f - 500f * portrait.scale) / 2f, portrait.tx, 0.01f)
        assertEquals((800f - 1000f * portrait.scale) / 2f, portrait.ty, 0.01f)
        assertEquals(74, portrait.zoomPercent())
    }

    @Test
    fun fitClampsToTheZoomRange() {
        val tiny = ViewTransform()
        tiny.fit(10f, 10f, 800f, 800f, 16f)
        assertEquals(ViewTransform.MAX_SCALE, tiny.scale, 0f)

        val huge = ViewTransform()
        huge.fit(10000f, 10000f, 200f, 200f, 16f)
        assertEquals(ViewTransform.MIN_SCALE, huge.scale, 0f)
    }

    @Test
    fun clampKeepsAQuarterOrTheMinimumLengthOnScreen() {
        val transform = ViewTransform(scale = 2f, tx = 5000f, ty = -4000f)
        transform.clamp(400f, 300f, 200f, 200f, 48f)
        val visibleW = overlap(transform.tx, 400f * 2f, 200f)
        val visibleH = overlap(transform.ty, 300f * 2f, 200f)
        assertEquals(200f, visibleW, 0.01f)
        assertEquals(150f, visibleH, 0.01f)

        val small = ViewTransform(scale = 0.5f, tx = -80f, ty = 90f)
        small.clamp(100f, 80f, 200f, 180f, 48f)
        assertEquals(48f, overlap(small.tx, 50f, 200f), 0.01f)
        assertEquals(40f, overlap(small.ty, 40f, 180f), 0.01f)
    }

    @Test
    fun centerOnRoundTripsTheCanvasPoint() {
        val transform = ViewTransform(scale = 2.5f)
        transform.centerOn(80f, 40f, 300f, 200f)
        assertEquals(80f, transform.centerX(300f), 0.01f)
        assertEquals(40f, transform.centerY(200f), 0.01f)
        val screen = FloatArray(2)
        transform.toScreen(80f, 40f, screen)
        assertEquals(150f, screen[0], 0.01f)
        assertEquals(100f, screen[1], 0.01f)
    }

    private fun overlap(translate: Float, screenSpan: Float, viewSpan: Float): Float {
        val start = if (translate > 0f) translate else 0f
        val end = if (translate + screenSpan < viewSpan) translate + screenSpan else viewSpan
        return (end - start).coerceAtLeast(0f)
    }
}
