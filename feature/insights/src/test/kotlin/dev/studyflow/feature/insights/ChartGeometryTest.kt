package dev.studyflow.feature.insights

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ChartGeometry")
class ChartGeometryTest {
    @Test
    fun `a value equal to the maximum fills the plot`() {
        assertEquals(1f, ChartGeometry.fractionOf(value = 90, max = 90))
    }

    @Test
    fun `a value is drawn in proportion to the maximum`() {
        assertEquals(0.5f, ChartGeometry.fractionOf(value = 45, max = 90))
    }

    /** A minute of study next to a ten-hour day must still be visible, not rounded away. */
    @Test
    fun `a tiny non-zero value keeps a visible minimum`() {
        assertEquals(ChartGeometry.MIN_VISIBLE_FRACTION, ChartGeometry.fractionOf(value = 1, max = 600))
    }

    @Test
    fun `nothing studied draws nothing`() {
        assertEquals(0f, ChartGeometry.fractionOf(value = 0, max = 600))
    }

    @Test
    fun `an empty range has no maximum to divide by`() {
        assertEquals(0f, ChartGeometry.fractionOf(value = 10, max = 0))
    }

    @Test
    fun `a single bucket is plotted at full height rather than as a division by zero`() {
        assertEquals(listOf(1f), ChartGeometry.fractions(listOf(120)))
    }

    @Test
    fun `fractions are relative to the largest bucket`() {
        assertEquals(listOf(1f, 0.5f, 0f), ChartGeometry.fractions(listOf(60, 30, 0)))
    }

    @Test
    fun `a range with no buckets plots no points`() {
        assertEquals(emptyList<Float>(), ChartGeometry.fractions(emptyList()))
    }

    @Test
    fun `a month of daily buckets still fits as bars`() {
        assertFalse(ChartGeometry.usesAreaChart(ChartGeometry.MAX_BAR_BUCKETS))
    }

    @Test
    fun `a year of daily buckets is drawn as an area instead of unreadable bars`() {
        assertTrue(ChartGeometry.usesAreaChart(365))
    }
}
