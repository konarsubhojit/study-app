package dev.studyflow.feature.insights

/**
 * The geometry behind the insights charts (issue #168).
 *
 * These are plain functions rather than inline arithmetic in the composables because the way a
 * chart degrades is a correctness question, not a styling one: a single bucket must still read as
 * a bar rather than a full-width slab, and a bucket worth one minute next to one worth ten hours
 * must still be visible. Both are assertions a unit test can make; neither is something a
 * screenshot diff explains.
 */
internal object ChartGeometry {
    /**
     * The smallest fraction of the plot a non-zero value is drawn at, so "almost nothing" is
     * distinguishable from "nothing at all".
     */
    const val MIN_VISIBLE_FRACTION: Float = 0.04f

    /**
     * Bars thinner than a few device-independent pixels stop reading as bars, so past this many
     * buckets the trend is drawn as an area instead. Thirty-one covers a month of daily buckets,
     * which is the densest bar chart that still fits a phone.
     */
    const val MAX_BAR_BUCKETS: Int = 31

    /** The fraction of the plot [value] occupies, clamped so a non-zero value is never invisible. */
    fun fractionOf(
        value: Long,
        max: Long,
    ): Float =
        when {
            value <= 0L || max <= 0L -> 0f
            else -> (value.toFloat() / max.toFloat()).coerceIn(MIN_VISIBLE_FRACTION, 1f)
        }

    /** Whether [bucketCount] buckets are too dense to draw as bars. */
    fun usesAreaChart(bucketCount: Int): Boolean = bucketCount > MAX_BAR_BUCKETS

    /**
     * [values] as fractions of their own maximum, in order, for an area or line plot.
     *
     * A single point has nothing to be relative to, so it is plotted at the top of the plot and
     * the value label underneath carries the number itself.
     */
    fun fractions(values: List<Long>): List<Float> {
        val max = values.maxOrNull()?.coerceAtLeast(1L) ?: return emptyList()
        return values.map { fractionOf(it, max) }
    }
}
