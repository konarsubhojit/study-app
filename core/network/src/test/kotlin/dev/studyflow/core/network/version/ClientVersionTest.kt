package dev.studyflow.core.network.version

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Minimum supported client")
class ClientVersionTest {
    @Test
    fun `versions compare numerically, not alphabetically`() {
        assertTrue(ClientVersion(1, 9, 0) < ClientVersion(1, 10, 0))
        assertTrue(ClientVersion(2, 0, 0) > ClientVersion(1, 99, 99))
    }

    @Test
    fun `a partial or decorated version still parses`() {
        assertEquals(ClientVersion(1, 4, 0), ClientVersion.parseOrNull("1.4"))
        assertEquals(ClientVersion(2, 0, 0), ClientVersion.parseOrNull("2"))
        assertEquals(ClientVersion(1, 4, 2), ClientVersion.parseOrNull("1.4.2-beta01"))
        assertEquals(ClientVersion(1, 4, 2), ClientVersion.parseOrNull(" 1.4.2+build.9 "))
    }

    @Test
    fun `nonsense is null rather than a guess`() {
        assertNull(ClientVersion.parseOrNull(null))
        assertNull(ClientVersion.parseOrNull(""))
        assertNull(ClientVersion.parseOrNull("latest"))
        assertNull(ClientVersion.parseOrNull("1.4.2.3"))
    }

    @Test
    fun `an older build is asked to upgrade`() {
        assertTrue(
            MinimumClientPolicy.isUpgradeRequired(
                current = ClientVersion(1, 3, 9),
                minimumSupported = ClientVersion(1, 4, 0),
            ),
        )
    }

    @Test
    fun `the oldest supported build is not asked to upgrade`() {
        assertFalse(
            MinimumClientPolicy.isUpgradeRequired(
                current = ClientVersion(1, 4, 0),
                minimumSupported = ClientVersion(1, 4, 0),
            ),
        )
    }

    @Test
    fun `a server that says nothing never locks a user out`() {
        assertFalse(MinimumClientPolicy.isUpgradeRequired(ClientVersion(1, 0, 0), minimumSupported = null))
    }
}
