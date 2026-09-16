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
