package dev.studyflow.core.common.logging

/** Scrubs data before it can leave the process through logs. */
public object LogSanitizer {
    public const val RELEASE_TAG: String = "StudyFlow"
    public const val RELEASE_MESSAGE: String = "[redacted]"

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

    @Suppress("UnusedParameter")
    public fun scrubReleaseTag(tag: String): String = RELEASE_TAG

    @Suppress("UnusedParameter")
    public fun scrubReleaseMessage(message: String): String = RELEASE_MESSAGE
}
