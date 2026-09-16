package dev.studyflow.core.network

import dev.studyflow.core.network.MockBackend.json
import dev.studyflow.core.network.model.StudyFlowJson
import dev.studyflow.core.network.model.SubjectDto
import dev.studyflow.core.network.model.TaskDto
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The acceptance criterion of issue #63: a field the server adds after this build shipped must not
 * break it. These tests are the proof, and they fail the moment someone tightens the JSON
 * configuration.
 */
@DisplayName("Forward compatibility")
class ForwardCompatibilityTest {
    @Test
    fun `an unknown field added server-side is ignored`() {
        val futureSubject =
            """
            {"id":"s1","name":"Maths","colorHex":"#2E7D32","icon":"calculator","syllabusVersion":7}
            """.trimIndent()

        val subject = StudyFlowJson.decodeFromString<SubjectDto>(futureSubject)

        assertEquals(SubjectDto(id = "s1", name = "Maths", colorHex = "#2E7D32"), subject)
    }

    @Test
    fun `an unknown nested object added server-side is ignored`() {
        val futureTask =
            """
            {"id":"t1","subjectId":"s1","title":"Read chapter 4","completed":false,
             "reminder":{"kind":"smart","leadMinutes":15},"tags":["exam","week3"]}
            """.trimIndent()

        val task = StudyFlowJson.decodeFromString<TaskDto>(futureTask)

        assertEquals("Read chapter 4", task.title)
        assertNull(task.dueAtIso)
    }

    @Test
    fun `an older client still completes a call against a newer server`() =
        runTest {
            val api =
                MockBackend.api {
                    json("""[{"id":"s1","name":"Maths","archived":false,"stats":{"streakDays":4}}]""")
                }

            val result = api.subjects()

            assertEquals(listOf(SubjectDto(id = "s1", name = "Maths")), result.valueOrNull())
        }

    @Test
    fun `an optional field the client does not set is left out of the request body`() {
        val encoded = StudyFlowJson.encodeToString(TaskDto(id = "t1", subjectId = "s1", title = "Read"))

        assertEquals("""{"id":"t1","subjectId":"s1","title":"Read"}""", encoded)
    }
}
