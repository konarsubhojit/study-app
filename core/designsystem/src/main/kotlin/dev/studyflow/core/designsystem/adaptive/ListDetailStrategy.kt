package dev.studyflow.core.designsystem.adaptive

/**
 * How a list/detail screen is laid out for a given window (issue #17).
 *
 * Materials and tasks are both "pick one of these, then look at it", and on a wide window showing
 * the list and the detail side by side is the whole benefit of the extra space. Keeping the
 * decision in a pure function means both features share one answer, and that the awkward case —
 * a wide window with nothing selected — is decided once rather than per screen.
 */
public enum class ListDetailStrategy {
    /** One pane, showing the list. */
    ListOnly,

    /** One pane, showing the selected item; back returns to the list. */
    DetailOnly,

    /** Both panes side by side; the detail pane shows a placeholder when nothing is selected. */
    ListAndDetail,
    ;

    /** True when the layout has a list pane the user can pick from. */
    public val showsList: Boolean
        get() = this != DetailOnly

    /** True when the layout has a detail pane, even if nothing is selected yet. */
    public val showsDetail: Boolean
        get() = this != ListOnly

    public companion object {
        /**
         * Chooses the layout.
         *
         * Two panes need an expanded window: at medium width a side-by-side split leaves both panes
         * too narrow to read, so a medium window behaves like a phone.
         *
         * @param widthClass the width of the window the screen is in.
         * @param hasSelection whether an item is currently selected.
         */
        public fun of(
            widthClass: WindowWidthClass,
            hasSelection: Boolean,
        ): ListDetailStrategy =
            when {
                widthClass == WindowWidthClass.Expanded -> ListAndDetail
                hasSelection -> DetailOnly
                else -> ListOnly
            }
    }
}
