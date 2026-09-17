package dev.studyflow.core.domain.materials

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PdfPageCounter")
class PdfPageCounterTest {
    @Test
    fun `counts every page object in a single feed`() {
        val counter = PdfPageCounter()

        counter.feed("1 0 obj<</Type/Page/Parent 2 0 R>>endobj 3 0 obj<</Type/Page/Parent 2 0 R>>endobj")

        assertEquals(2, counter.pageCount())
    }

    @Test
    fun `a Pages tree node is not itself counted as a page`() {
        val counter = PdfPageCounter()

        counter.feed("<</Type/Pages/Kids[3 0 R]/Count 1>> <</Type/Page/Parent 2 0 R>>")

        assertEquals(1, counter.pageCount())
    }

    @Test
    fun `tolerates the space PDF writers commonly put before the value`() {
        val counter = PdfPageCounter()

        counter.feed("<</Type /Page/Parent 2 0 R>>")

        assertEquals(1, counter.pageCount())
    }

    @Test
    fun `a token split across two chunks is still counted exactly once`() {
        val counter = PdfPageCounter()
        val whole = "<</Type/Page/Parent 2 0 R>>"
        val splitPoint = whole.indexOf("/Page") + 2

        counter.feed(whole.substring(0, splitPoint))
        counter.feed(whole.substring(splitPoint))

        assertEquals(1, counter.pageCount())
    }

    @Test
    fun `a page fully contained in one chunk is not recounted when the next chunk arrives`() {
        val counter = PdfPageCounter()

        counter.feed("<</Type/Page/Parent 2 0 R>>")
        counter.feed(" <</Type/Page/Parent 2 0 R>>")

        assertEquals(2, counter.pageCount())
    }

    @Test
    fun `byte-at-a-time feeding still finds every page`() {
        val counter = PdfPageCounter()
        val content = "<</Type/Page>> <</Type/Page>> <</Type/Page>>"

        content.forEach { char -> counter.accept(byteArrayOf(char.code.toByte()), 1) }

        assertEquals(3, counter.pageCount())
    }

    @Test
    fun `a file with no page objects reports no page count rather than zero`() {
        val counter = PdfPageCounter()

        counter.feed("%PDF-1.4 has no object dictionaries in this fragment")

        assertNull(counter.pageCount())
    }

    @Test
    fun `no bytes fed at all also reports no page count`() {
        assertNull(PdfPageCounter().pageCount())
    }

    private fun PdfPageCounter.feed(text: String) {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        accept(bytes, bytes.size)
    }
}
