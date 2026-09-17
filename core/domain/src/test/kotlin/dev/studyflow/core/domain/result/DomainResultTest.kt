package dev.studyflow.core.domain.result

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class DomainResultTest {
    @Test
    fun `maps network exceptions to a safe network error`() {
        assertEquals(DomainError.Network, IOException().toDomainError())
    }

    @Test
    fun `maps storage exceptions to a safe storage error`() {
        assertEquals(DomainError.Storage, StorageException().toDomainError())
    }

    @Test
    fun `maps permission exceptions to a safe permission error`() {
        assertEquals(DomainError.Permission, SecurityException().toDomainError())
    }

    @Test
    fun `maps validation exceptions to a safe validation error`() {
        assertEquals(DomainError.Validation, IllegalArgumentException().toDomainError())
    }
}
