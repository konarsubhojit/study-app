package dev.studyflow.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class StartupInstrumentedTest {
    @get:Rule val composeRule: ComposeContentTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstScreenComposesAfterApplicationStartup() {
        composeRule.onNodeWithText("Today").assertIsDisplayed()
    }
}
