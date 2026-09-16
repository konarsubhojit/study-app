package dev.studyflow.core.common.logging

/** Scrubs data before it can leave the process through logs. */
public object LogSanitizer {
    public const val RELEASE_TAG: String = "StudyFlow"

    private val email = Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)
    private val unixPath = Regex("""(?<!\w)/(?:[^\s/]+/)*[^\s/]+""")
    private val windowsPath = Regex("""\b[A-Z]:\\(?:[^\s\\]+\\)*[^\s\\]+""", RegexOption.IGNORE_CASE)
    private val fileName = Regex("""\b[^\s/\\]+\.(?:csv|db|docx?|json|kt|md|mp3|mp4|pdf|png|txt|zip)\b""")

    public fun scrubDebugMessage(message: String): String =
        message
            .replace(email, "[email]")
            .replace(windowsPath, "[path]")
            .replace(unixPath, "[path]")
            .replace(fileName, "[file]")

    public fun scrubReleaseMessage(
        level: LogLevel,
        throwableType: String? = null,
    ): String {
        val safeThrowableType =
            throwableType
                ?.replace(unsafeThrowableTypeCharacter, "")
                ?.takeIf { it.isNotBlank() }

        return listOfNotNull(
            "level=${level.name}",
            safeThrowableType?.let { "throwable=$it" },
        ).joinToString(separator = " ")
    }

    private val unsafeThrowableTypeCharacter = Regex("""[^A-Za-z0-9_]""")
}
