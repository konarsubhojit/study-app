package dev.studyflow.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("MaterialKind")
class MaterialKindTest {
    @ParameterizedTest
    @CsvSource(
        "image/png,          photo.png,     IMAGE",
        "image/heic,         photo.heic,    IMAGE",
        "video/mp4,          lecture.mp4,   VIDEO",
        "audio/mpeg,         recording.mp3, AUDIO",
        "application/pdf,    paper.pdf,     PDF",
        "text/plain,         notes.txt,     TEXT",
        "text/csv,           marks.csv,     SPREADSHEET",
        "application/zip,    papers.zip,    ARCHIVE",
    )
    fun `a trustworthy mime type is used directly`(
        mime: String,
        name: String,
        expected: MaterialKind,
    ) {
        assertEquals(expected, MaterialKind.of(mime, name))
    }

    @ParameterizedTest
    @CsvSource(
        "slides.pptx,  PRESENTATION",
        "essay.docx,   DOCUMENT",
        "budget.xlsx,  SPREADSHEET",
        "archive.7z,   ARCHIVE",
        "scan.jpeg,    IMAGE",
        "paper.pdf,    PDF",
    )
    fun `a generic mime type falls back to the file extension`(
        name: String,
        expected: MaterialKind,
    ) {
        assertEquals(
            expected,
            MaterialKind.of("application/octet-stream", name),
            "content providers routinely report octet-stream for perfectly ordinary Office files",
        )
    }

    @Test
    fun `office mime types are recognised without an extension`() {
        val mime = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

        assertEquals(MaterialKind.PRESENTATION, MaterialKind.of(mime))
    }

    @Test
    fun `mime parameters are ignored`() {
        assertEquals(MaterialKind.TEXT, MaterialKind.of("text/plain; charset=utf-8", "notes.txt"))
    }

    @Test
    fun `an unknown file is classified as other rather than guessed at`() {
        assertEquals(MaterialKind.OTHER, MaterialKind.of("", "mystery.qqq"))
    }

    @Test
    fun `a missing mime type and no extension still classifies`() {
        assertEquals(MaterialKind.OTHER, MaterialKind.of("", "README"))
    }

    @Test
    fun `classification is case-insensitive`() {
        assertEquals(MaterialKind.IMAGE, MaterialKind.of("IMAGE/PNG", "PHOTO.PNG"))
    }
}
