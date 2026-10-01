package dev.studyflow.feature.settings

import dev.studyflow.core.domain.sync.SyncStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Instant

@DisplayName("SyncStatusCard copy")
class SyncStatusCardTest {
    @Test
    fun `a queue no run has ever touched says sync has not run, not merely that it has not synced`() {
        val neverRan = SyncStatusUiState(SyncStatus(pendingCount = 2)).lastSuccessSummary()
        val nothingToSend = SyncStatusUiState(SyncStatus()).lastSuccessSummary()

        assertEquals("Sync has not run on this device yet, so nothing has been sent.", neverRan)
        assertNotEquals(nothingToSend, neverRan, "the two states must not read the same")
    }

    @Test
    fun `an attempt that never succeeded is told apart from one that never happened`() {
        val summary =
            SyncStatusUiState(SyncStatus(pendingCount = 2, lastError = "offline", lastAttemptAt = AT))
                .lastSuccessSummary()

        assertEquals("This device has not synced successfully yet.", summary)
    }

    @Test
    fun `a device that has synced says when`() {
        val summary =
            SyncStatusUiState(SyncStatus(pendingCount = 2, lastSuccessAt = AT, lastAttemptAt = AT))
                .lastSuccessSummary()

        assertTrue(summary.startsWith("Last successful sync: "), summary)
    }

    @Test
    fun `a device with nothing to send and no attempt keeps the neutral copy`() {
        assertEquals("This device has not synced yet.", SyncStatusUiState(SyncStatus()).lastSuccessSummary())
    }

    @Test
    fun `the Wi-Fi-only switch says what each choice costs`() {
        assertTrue("mobile data" in wifiOnlyUploadsSummary(wifiOnly = true))
        assertTrue("mobile data" in wifiOnlyUploadsSummary(wifiOnly = false))
        assertNotEquals(wifiOnlyUploadsSummary(true), wifiOnlyUploadsSummary(false))
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-03-01T09:00:00Z")
    }
}
