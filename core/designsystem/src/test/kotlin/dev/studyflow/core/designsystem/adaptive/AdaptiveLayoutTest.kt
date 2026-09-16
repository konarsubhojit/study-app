package dev.studyflow.core.designsystem.adaptive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("WindowWidthClass")
class WindowWidthClassTest {
    @ParameterizedTest(name = "{0}.dp is {1}")
    @CsvSource(
        "0, Compact",
        "411, Compact",
        "599, Compact",
        "600, Medium",
        "839, Medium",
        "840, Expanded",
        "1280, Expanded",
    )
    fun `widths are classified at the Material breakpoints`(
        widthDp: Int,
        expected: WindowWidthClass,
    ) {
        assertEquals(expected, WindowWidthClass.fromWidthDp(widthDp))
    }

    @Test
    fun `a nonsensical width is treated as the smallest class rather than crashing`() {
        assertEquals(WindowWidthClass.Compact, WindowWidthClass.fromWidthDp(-1))
    }
}

@DisplayName("ListDetailStrategy")
class ListDetailStrategyTest {
    @Test
    fun `an expanded window always shows both panes`() {
        assertEquals(
            ListDetailStrategy.ListAndDetail,
            ListDetailStrategy.of(WindowWidthClass.Expanded, hasSelection = false),
        )
        assertEquals(
            ListDetailStrategy.ListAndDetail,
            ListDetailStrategy.of(WindowWidthClass.Expanded, hasSelection = true),
        )
    }

    @ParameterizedTest(name = "{0} shows only the list until something is selected")
    @CsvSource("Compact", "Medium")
    fun `a narrow window shows one pane at a time`(widthClass: WindowWidthClass) {
        assertEquals(ListDetailStrategy.ListOnly, ListDetailStrategy.of(widthClass, hasSelection = false))
        assertEquals(ListDetailStrategy.DetailOnly, ListDetailStrategy.of(widthClass, hasSelection = true))
    }

    @Test
    fun `each layout reports the panes it renders`() {
        assertEquals(
            listOf(true, false, true),
            ListDetailStrategy.entries.map { it.showsList },
        )
        assertEquals(
            listOf(false, true, true),
            ListDetailStrategy.entries.map { it.showsDetail },
        )
    }
}
