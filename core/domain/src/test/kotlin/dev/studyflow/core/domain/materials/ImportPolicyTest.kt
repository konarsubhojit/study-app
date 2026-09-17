package dev.studyflow.core.domain.materials

import dev.studyflow.core.model.MaterialKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ImportPolicy")
class ImportPolicyTest {
    private val mib = 1024L * 1024L

    @Test
    fun `a file within its kind's ceiling is allowed`() {
        val verdict =
            ImportPolicy.evaluate(
                kind = MaterialKind.IMAGE,
                mimeType = "image/png",
                fileName = "photo.png",
                sizeBytes = 10 * mib,
            )

        assertEquals(ImportVerdict.Allowed, verdict)
    }

    @Test
    fun `a video is judged against the video ceiling, not the default`() {
        val justUnderVideoCeiling = ImportLimits.DEFAULT.maxBytesFor(MaterialKind.VIDEO) - 1

        val verdict =
            ImportPolicy.evaluate(
                kind = MaterialKind.VIDEO,
                mimeType = "video/mp4",
                fileName = "lecture.mp4",
                sizeBytes = justUnderVideoCeiling,
            )

        assertEquals(ImportVerdict.Allowed, verdict)
    }

    @Test
    fun `a file over its kind's ceiling is rejected as too large`() {
        val overImageCeiling = ImportLimits.DEFAULT.maxBytesFor(MaterialKind.IMAGE) + 1

        val verdict =
            ImportPolicy.evaluate(
                kind = MaterialKind.IMAGE,
                mimeType = "image/png",
                fileName = "scan.png",
                sizeBytes = overImageCeiling,
            )

        assertEquals(ImportVerdict.Rejected(ImportRejectionReason.FILE_TOO_LARGE), verdict)
    }

    @Test
    fun `an unconfigured kind falls back to the default ceiling`() {
        val overDefault = ImportLimits.DEFAULT.defaultMaxBytes + 1

        val verdict =
            ImportPolicy.evaluate(
                kind = MaterialKind.OTHER,
                mimeType = "application/octet-stream",
                fileName = "mystery.qqq",
                sizeBytes = overDefault,
            )

        assertEquals(ImportVerdict.Rejected(ImportRejectionReason.FILE_TOO_LARGE), verdict)
    }

    @Test
    fun `an installable package is blocked by MIME type regardless of size`() {
        val verdict =
            ImportPolicy.evaluate(
                kind = MaterialKind.OTHER,
                mimeType = "application/vnd.android.package-archive",
                fileName = "definitely-not-malware.apk",
                sizeBytes = 1,
            )

        assertEquals(ImportVerdict.Rejected(ImportRejectionReason.BLOCKED_TYPE), verdict)
    }

    @Test
    fun `a blocked extension is caught even when the MIME type looks harmless`() {
        val verdict =
            ImportPolicy.evaluate(
                kind = MaterialKind.OTHER,
                mimeType = "application/octet-stream",
                fileName = "totally-a-document.exe",
                sizeBytes = 1,
            )

        assertEquals(ImportVerdict.Rejected(ImportRejectionReason.BLOCKED_TYPE), verdict)
    }

    @Test
    fun `a zero-byte file is allowed — the user chose to keep it`() {
        val verdict =
            ImportPolicy.evaluate(
                kind = MaterialKind.TEXT,
                mimeType = "text/plain",
                fileName = "placeholder.txt",
                sizeBytes = 0,
            )

        assertEquals(ImportVerdict.Allowed, verdict)
    }

    @Test
    fun `mime type matching ignores case and parameters`() {
        val verdict =
            ImportPolicy.evaluate(
                kind = MaterialKind.OTHER,
                mimeType = "Application/Vnd.Android.Package-Archive; charset=binary",
                fileName = "app.apk",
                sizeBytes = 1,
            )

        assertEquals(ImportVerdict.Rejected(ImportRejectionReason.BLOCKED_TYPE), verdict)
    }
}
