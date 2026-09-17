package dev.studyflow.core.domain.materials

/**
 * Estimates a PDF's page count while its bytes stream past for hashing and copying (issue #37).
 *
 * ### Why an estimate, and why that is still worth computing
 *
 * A page count that is authoritative in every case means parsing the cross-reference table and
 * walking the `/Pages` tree — real work, on a second pass over the file, for a number the import
 * screen only ever uses as a hint ("12 pages"). Counting `/Type /Page` object dictionaries as they
 * scroll past in the same buffers already being hashed costs nothing extra and is right for the
 * overwhelming majority of PDFs a student imports. Malformed, linearised or incrementally-updated
 * files can throw it off; [pageCount] returns `null` rather than a confident wrong answer when it
 * saw nothing that looked like a page at all.
 *
 * ### Why it is a class, not a function
 *
 * The token this counts can straddle a chunk boundary, so a single-buffer function would
 * undercount whenever `/Type` lands at the end of one read and `/Page` at the start of the next.
 * [accept] keeps a short overlap between calls so a match is recognised exactly once, whichever
 * call it completes on, regardless of the caller's buffer size.
 */
public class PdfPageCounter {
    private val carry = StringBuilder()
    private var count = 0
    private var sawAnyBytes = false

    /**
     * Feeds the next [length] bytes of the file, in order, into the counter.
     *
     * Bytes are read as ISO-8859-1 (a lossless byte-to-char mapping) purely to search for an ASCII
     * token; no attempt is made to interpret the file as text.
     */
    public fun accept(
        buffer: ByteArray,
        length: Int,
    ) {
        if (length <= 0) return
        sawAnyBytes = true

        // Fixed before this call's window is built: a match wholly inside it was already visible —
        // and so already counted — the last time this many characters were the tail of the window.
        val previousCarryLength = carry.length
        val window = carry.toString() + String(buffer, 0, length, Charsets.ISO_8859_1)

        var searchFrom = 0
        while (true) {
            val typeIndex = window.indexOf(TYPE_TOKEN, searchFrom)
            if (typeIndex == -1) break
            val afterType = typeIndex + TYPE_TOKEN.length
            val pageIndex = skipWhitespace(window, afterType)
            if (window.startsWith(PAGE_TOKEN, pageIndex) && !isPagesToken(window, pageIndex)) {
                val matchEnd = pageIndex + PAGE_TOKEN.length
                // A match ending inside the carried prefix was already complete — and already
                // counted — before this call ever saw it.
                if (matchEnd > previousCarryLength) count += 1
            }
            searchFrom = afterType
        }

        carry.setLength(0)
        carry.append(window.takeLast(OVERLAP))
    }

    /** The running count, or `null` when nothing resembling a page object has been seen yet. */
    public fun pageCount(): Int? = count.takeIf { sawAnyBytes && it > 0 }

    private fun skipWhitespace(
        text: String,
        from: Int,
    ): Int {
        var index = from
        while (index < text.length && text[index].isWhitespace()) index += 1
        return index
    }

    private fun isPagesToken(
        text: String,
        pageIndex: Int,
    ): Boolean {
        val afterPage = pageIndex + PAGE_TOKEN.length
        return afterPage < text.length && text[afterPage] == 's'
    }

    private companion object {
        const val TYPE_TOKEN = "/Type"
        const val PAGE_TOKEN = "/Page"

        // Longer than "/Type" + a plausible run of whitespace + "/Pages", so no legitimate token can
        // be split further back than this from the end of a chunk.
        const val OVERLAP = 32
    }
}
