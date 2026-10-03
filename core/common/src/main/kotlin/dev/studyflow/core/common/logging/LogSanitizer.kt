package dev.studyflow.core.common.logging

/** Scrubs data before it can leave the process through logs. */
public object LogSanitizer {
    public const val RELEASE_TAG: String = "StudyFlow"

    private val email = Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)
    private val unixPath = Regex("""\B/(?:storage|sdcard|data|cache|mnt|system|vendor)(?:/[^\s/;:,]+)*""")
    private val windowsPath = Regex("""\b[A-Z]:\\(?:[^\s\\]+\\)*[^\s\\]+""", RegexOption.IGNORE_CASE)
    private val fileName = Regex("""\b[^\s/\\]+\.(?:csv|db|docx?|json|kt|md|mp3|mp4|pdf|png|txt|zip)\b""")
    private val url = Regex("""\b(?:https?|ftp)://[^\s]+""", RegexOption.IGNORE_CASE)
    private val headerValue =
        Regex("""(?:^|[\s;])[A-Z][A-Z0-9-]*:[^\r\n]*""", RegexOption.IGNORE_CASE)
    private val tokenValue =
        Regex(
            """\b(access[_-]?token|refresh[_-]?token|id[_-]?token|jwt|token)\s*[:=]\s*[^\s,;]+""",
            RegexOption.IGNORE_CASE,
        )
    private val jwt = Regex("""\b[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b""")
    private val query = Regex("""\?[^\s,;)]+""")
    private val lineBreak = Regex("""[\r\n\t]+""")

    public fun scrubDebugMessage(message: String): String =
        message
            .replace(email, "[email]")
            .replace(windowsPath, "[path]")
            .replace(unixPath, "[path]")
            .replace(fileName, "[file]")

    /**
     * Scrubs a developer-authored failure message before it is admitted to a structured diagnostic.
     *
     * Unlike ordinary free text, the resulting message survives release logging, so credentials and
     * signed URLs are removed before the existing PII scrubbers run.
     */
    public fun sanitizeDiagnosticFailureMessage(message: String): SanitizedDiagnosticMessage {
        val safeMessage =
            message
                .replace(lineBreak, " ")
                .replace(url, "[url]")
                .replace(headerValue, " [headers redacted]")
                .replace(tokenValue, "[token redacted]")
                .replace(jwt, "[token]")
                .replace(query, "[query]")
                .let(::scrubDebugMessage)
        return SanitizedDiagnosticMessage(safeMessage)
    }

    /**
     * A throwable's class name with everything but letters, digits and underscores removed.
     *
     * A class name is written in this app's source, not by its user, but an anonymous or synthetic
     * name can carry a file extension that looks like a document; stripping the punctuation is
     * what keeps `Bad/FileException.kt` from reading as a file name.
     */
    public fun sanitizeThrowableType(throwableType: String?): String? =
        throwableType
            ?.replace(unsafeThrowableTypeCharacter, "")
            ?.takeIf { it.isNotBlank() }

    public fun scrubReleaseMessage(
        level: LogLevel,
        throwableType: String? = null,
    ): String {
        val safeThrowableType = sanitizeThrowableType(throwableType)

        return listOfNotNull(
            "level=${level.name}",
            safeThrowableType?.let { "throwable=$it" },
        ).joinToString(separator = " ")
    }

    private val unsafeThrowableTypeCharacter = Regex("""[^A-Za-z0-9_]""")
}

/** A failure message that has passed [LogSanitizer.sanitizeDiagnosticFailureMessage]. */
public class SanitizedDiagnosticMessage internal constructor(
    internal val value: String,
)
