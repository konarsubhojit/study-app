package dev.studyflow.core.designsystem.adaptive

/**
 * The Material 3 window width classes (issue #17).
 *
 * Layout decisions are taken from the width of the window, never from the device type: a phone in
 * landscape, a foldable that has just been opened and a freeform window on a tablet are all just
 * widths, and treating them as anything else is how "tablet layouts" end up broken in split screen.
 *
 * The breakpoints are the Material 3 ones, and [fromWidthDp] is pure so they can be asserted
 * without inflating a window.
 */
public enum class WindowWidthClass {
    /** Below 600.dp — a phone in portrait, or a narrow multi-window pane. */
    Compact,

    /** 600.dp to 839.dp — a large phone in landscape, a small tablet, an unfolded inner display. */
    Medium,

    /** 840.dp and above — a tablet or a desktop-sized window. */
    Expanded,
    ;

    public companion object {
        internal const val MEDIUM_MIN_WIDTH_DP = 600
        internal const val EXPANDED_MIN_WIDTH_DP = 840

        /** Classifies a window width, treating a nonsensical (negative) width as compact. */
        public fun fromWidthDp(widthDp: Int): WindowWidthClass =
            when {
                widthDp >= EXPANDED_MIN_WIDTH_DP -> Expanded
                widthDp >= MEDIUM_MIN_WIDTH_DP -> Medium
                else -> Compact
            }
    }
}
