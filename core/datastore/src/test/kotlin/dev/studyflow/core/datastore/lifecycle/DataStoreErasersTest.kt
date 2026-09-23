package dev.studyflow.core.datastore.lifecycle

import androidx.datastore.core.DataStoreFactory
import dev.studyflow.core.datastore.ActiveTimer
import dev.studyflow.core.datastore.ActiveTimerAnchorSerializer
import dev.studyflow.core.datastore.ActiveTimerStore
import dev.studyflow.core.datastore.UserSettingsSerializer
import dev.studyflow.core.datastore.UserSettingsStore
import dev.studyflow.core.datastore.proto.Theme
import dev.studyflow.core.network.auth.AuthState
import dev.studyflow.core.network.auth.AuthTokens
import dev.studyflow.core.network.auth.InMemoryTokenStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The preference-shaped half of an account deletion (issue #78), against real `DataStore` files.
 *
 * "Deleted" has to mean the bytes on disk, not a flag in memory, so each eraser is checked by
 * reading the store back rather than by trusting the call returned.
 */
@DisplayName("DataStore erasure")
class DataStoreErasersTest {
    @Test
    fun `erasing settings restores the defaults a fresh install would have`(
        @TempDir directory: File,
    ) = runTest {
        val store = settingsStore(directory)
        store.update { theme = Theme.THEME_DARK }

        SettingsEraser(store).erase()

        assertEquals(Theme.THEME_SYSTEM, store.data.first().theme)
    }

    @Test
    fun `erasing the active timer leaves no anchor to resume from`(
        @TempDir directory: File,
    ) = runTest {
        val store = timerStore(directory)
        store.set(
            ActiveTimer(
                sessionId = "session-1",
                wallClockEpochMillis = 1_772_355_600_000,
                uptimeMillis = 60_000,
                bootId = "boot-0",
            ),
        )

        ActiveTimerEraser(store).erase()

        assertNull(store.activeTimer.first(), "a cleared anchor must not restore a running timer")
    }

    @Test
    fun `erasing the credentials signs the device out`() =
        runTest {
            val tokens = InMemoryTokenStore(AuthTokens("access", "refresh"))

            TokenEraser(tokens).erase()

            assertNull(tokens.tokens())
            assertEquals(AuthState.LocalOnly, tokens.authState.value)
        }

    @Test
    fun `erasing a store that is already empty succeeds`(
        @TempDir directory: File,
    ) = runTest {
        val settings = settingsStore(directory)

        SettingsEraser(settings).erase()
        SettingsEraser(settings).erase()

        assertEquals(Theme.THEME_SYSTEM, settings.data.first().theme)
    }

    private fun settingsStore(directory: File) =
        UserSettingsStore(
            DataStoreFactory.create(serializer = UserSettingsSerializer) { File(directory, "user_settings.pb") },
        )

    private fun timerStore(directory: File) =
        ActiveTimerStore(
            DataStoreFactory.create(serializer = ActiveTimerAnchorSerializer) { File(directory, "active_timer.pb") },
        )
}
