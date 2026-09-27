package dev.studyflow.core.network

import dev.studyflow.core.network.model.StudyFlowJson
import dev.studyflow.core.network.model.SyncDeltaDto
import dev.studyflow.core.network.model.SyncSessionEventDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Holds the sync DTOs to the shape the deployed server actually sends.
 *
 * ADR 0017 has the server accept `uptimeMillis` and `bootId` on upload and then discard them: a
 * monotonic-clock reading and a boot identifier belong to the handset that produced them. Every
 * delta therefore arrives without those fields, and a client that required them would raise a
 * `SerializationException` — reported as the non-retryable [error.ApiError.Malformed] — the first
 * time a delta carried any events at all. Sync would then stop for good rather than visibly.
 */
@DisplayName("Sync wire shape")
class SyncWireShapeTest {
    @Test
    fun `a delta carrying events decodes without the device-local anchors`() {
        val serverDelta =
            """
            {"changes":[{"id":"sess-1","deviceId":"device-a","updatedAt":"2026-03-01T09:30:00Z",
             "startedAt":"2026-03-01T09:00:00Z","endedAt":"2026-03-01T09:25:00Z","status":"STOPPED",
             "countedMillis":1500000,
             "events":[{"id":"e1","sessionId":"sess-1","type":"STARTED","sequence":1,
              "wallClock":"2026-03-01T09:00:00Z"}]}],
             "nextCursor":"cursor-1","hasMore":false}
            """.trimIndent()

        val delta = StudyFlowJson.decodeFromString<SyncDeltaDto>(serverDelta)

        val event =
            delta.changes
                .single()
                .events
                .single()
        assertEquals("e1", event.id)
        assertNull(event.uptimeMillis, "the server never returns an uptime anchor")
        assertNull(event.bootId, "the server never returns a boot id")
    }

    @Test
    fun `an upload still carries the anchors the contract accepts`() {
        val event =
            SyncSessionEventDto(
                id = "e1",
                sessionId = "sess-1",
                type = "STARTED",
                sequence = 1,
                wallClockIso = "2026-03-01T09:00:00Z",
                uptimeMillis = 1_200,
                bootId = "boot-0",
            )

        val encoded = StudyFlowJson.encodeToString(event)

        assertEquals(true, encoded.contains("\"uptimeMillis\":1200"), encoded)
        assertEquals(true, encoded.contains("\"bootId\":\"boot-0\""), encoded)
    }
}
