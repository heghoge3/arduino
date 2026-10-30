package com.vabugeode.app.ui

import com.vabugeode.app.ui.components.ChartViewport
import com.vabugeode.app.ui.components.chartWindowPoints
import com.vabugeode.app.ui.components.chartTimeStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartViewportTest {
    @Test
    fun zoomKeepsTimeUnderFingersAndPanStopsAtEdges() {
        val v = ChartViewport().transform(4.0, 0.75, 0.0, 1.0 / 64)
        assertEquals(0.75, v.start + 0.75 * v.span, 0.000001)
        assertEquals(0.25, v.span, 0.000001)
        assertEquals(0.0, v.transform(1.0, 0.5, 100.0, 1.0 / 64).start, 0.0)
        assertEquals(0.75, v.transform(1.0, 0.5, -100.0, 1.0 / 64).start, 0.0)
        assertEquals(ChartViewport(), v.transform(0.01, 0.5, 0.0, 1.0 / 64))
        assertEquals(1.0 / 64, v.transform(1000.0, 0.5, 0.0, 1.0 / 64).span, 0.0)
    }

    @Test
    fun visiblePointsKeepBoundaryNeighboursAndDetails() {
        val points = (0L..100L).map { it * 1000 to it.toDouble() }
        val window = chartWindowPoints(points, 40_000, 45_000)
        assertEquals(39_000L, window.first().first)
        assertEquals(46_000L, window.last().first)
        assertEquals(8, window.size)
        assertEquals(listOf(points[45], points[46]), chartWindowPoints(points, 45_100, 45_900))
    }

    @Test
    fun timeTicksBecomeFinerWhenZooming() {
        assertTrue(chartTimeStep(60_000) < chartTimeStep(3_600_000))
        val v = ChartViewport()
        assertEquals(v, v.transform(2.0, Double.NaN, 0.0, 0.01))
    }
}
