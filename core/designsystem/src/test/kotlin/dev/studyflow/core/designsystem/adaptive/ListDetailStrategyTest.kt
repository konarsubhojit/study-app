package dev.studyflow.core.designsystem.adaptive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

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

    @ParameterizedTest(name = "{0} shows one pane at a time")
    @CsvSource("Compact", "Medium")
    fun `a narrow window shows one pane at a time`(widthClass: WindowWidthClass) {
        assertEquals(ListDetailStrategy.ListOnly, ListDetailStrategy.of(widthClass, hasSelection = false))
        assertEquals(ListDetailStrategy.DetailOnly, ListDetailStrategy.of(widthClass, hasSelection = true))
    }

    @Test
    fun `each layout reports the panes it renders`() {
        assertTrue(ListDetailStrategy.ListOnly.showsList)
        assertFalse(ListDetailStrategy.ListOnly.showsDetail)

        assertFalse(ListDetailStrategy.DetailOnly.showsList)
        assertTrue(ListDetailStrategy.DetailOnly.showsDetail)

        assertTrue(ListDetailStrategy.ListAndDetail.showsList)
        assertTrue(ListDetailStrategy.ListAndDetail.showsDetail)
    }
}
