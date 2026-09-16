package dev.studyflow.core.domain.materials

import dev.studyflow.core.model.ContentHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("UploadPlanner")
class UploadPlannerTest {
    private val hash = ContentHash("a".repeat(64))
    private val mib = 1024L * 1024L

    @Test
    fun `an empty file still produces one part`() {
        val plan = UploadPlanner.plan(totalBytes = 0, contentHash = hash)

        assertEquals(1, plan.parts.size)
        assertEquals(0L, plan.parts.single().size)
    }

    @Test
    fun `a small file fits in a single part`() {
        val plan = UploadPlanner.plan(totalBytes = 3 * mib, contentHash = hash)

        assertEquals(1, plan.parts.size)
        assertEquals(3 * mib, plan.parts.single().size)
    }

    @Test
    fun `parts tile the file exactly with no gaps or overlaps`() {
        val total = 100 * mib
        val plan = UploadPlanner.plan(totalBytes = total, contentHash = hash)

        assertEquals(0L, plan.parts.first().offset)
        assertEquals(total, plan.parts.last().endExclusive)
        plan.parts.zipWithNext { current, next ->
            assertEquals(
                current.endExclusive,
                next.offset,
                "part ${next.number} must start where ${current.number} ends",
            )
        }
        assertEquals(total, plan.parts.sumOf { it.size })
    }

    @Test
    fun `part numbers are contiguous and one-based`() {
        val plan = UploadPlanner.plan(totalBytes = 100 * mib, contentHash = hash)

        assertEquals((1..plan.parts.size).toList(), plan.parts.map { it.number })
    }

    @Test
    fun `a very large file scales the part size to stay within the part limit`() {
        val limits = MultipartLimits.S3_COMPATIBLE
        val total = 400L * 1024 * mib // 400 GiB

        val plan = UploadPlanner.plan(totalBytes = total, contentHash = hash, limits = limits)

        assertTrue(plan.parts.size <= limits.maxParts, "used ${plan.parts.size} parts")
        assertTrue(
            plan.parts.first().size > limits.minPartSizeBytes,
            "part size should grow rather than exceed the part count limit",
        )
    }

    @Test
    fun `every part except the last respects the minimum part size`() {
        val limits = MultipartLimits.S3_COMPATIBLE
        val plan = UploadPlanner.plan(totalBytes = 50 * mib, contentHash = hash, limits = limits)

        plan.parts.dropLast(1).forEach {
            assertTrue(it.size >= limits.minPartSizeBytes, "part ${it.number} is only ${it.size} bytes")
        }
    }

    @Nested
    @DisplayName("resuming")
    inner class Resuming {
        @Test
        fun `only the missing parts are resent`() {
            val plan = UploadPlanner.plan(totalBytes = 100 * mib, contentHash = hash)
            val completed = setOf(1, 2, 4)

            val remaining = plan.remaining(completed)

            assertFalse(remaining.any { it.number in completed })
            assertEquals(plan.parts.size - completed.size, remaining.size)
        }

        @Test
        fun `progress counts only acknowledged bytes`() {
            val plan = UploadPlanner.plan(totalBytes = 100 * mib, contentHash = hash)

            val uploaded = plan.uploadedBytes(setOf(1, 2))

            assertEquals(plan.parts.take(2).sumOf { it.size }, uploaded)
        }

        @Test
        fun `an upload is complete only when every part is acknowledged`() {
            val plan = UploadPlanner.plan(totalBytes = 100 * mib, contentHash = hash)
            val allButOne = plan.parts.map { it.number }.toSet() - plan.parts.last().number

            assertFalse(plan.isComplete(allButOne))
            assertTrue(plan.isComplete(plan.parts.map { it.number }.toSet()))
        }

        @Test
        fun `planning is deterministic, so a resumed upload agrees with the original`() {
            val first = UploadPlanner.plan(totalBytes = 123_456_789, contentHash = hash)
            val second = UploadPlanner.plan(totalBytes = 123_456_789, contentHash = hash)

            assertEquals(first, second)
        }
    }
}

@DisplayName("ArchiveSafety")
class ArchiveSafetyTest {
    @Nested
    @DisplayName("zip slip")
    inner class ZipSlip {
        @ParameterizedTest
        @ValueSource(
            strings = [
                "../evil.txt",
                "../../etc/passwd",
                "notes/../../../escape.txt",
                "/absolute/path.txt",
                "windows\\style\\..\\..\\..\\escape.txt",
                "..\\escape.txt",
                "C:/Windows/System32/drivers/etc/hosts",
                "",
                "   ",
            ],
        )
        fun `escaping entry names are rejected`(name: String) {
            assertNull(ArchiveSafety.safeRelativePath(name), "'$name' should not resolve")
        }

        @Test
        fun `a null byte in the name is rejected`() {
            assertNull(ArchiveSafety.safeRelativePath("notes\u0000.txt"))
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "notes.txt",
                "lectures/week-1/slides.pdf",
                "./notes.txt",
                "a/b/../c.txt",
                "folder with spaces/file (1).pdf",
                "unicode/ノート.md",
            ],
        )
        fun `well-behaved entry names are accepted`(name: String) {
            assertTrue(ArchiveSafety.safeRelativePath(name) != null, "'$name' should resolve")
        }

        @Test
        fun `traversal inside the archive is allowed as long as it stays within the root`() {
            assertEquals("a/c.txt", ArchiveSafety.safeRelativePath("a/b/../c.txt"))
        }

        @Test
        fun `redundant segments are normalised away`() {
            assertEquals("a/b.txt", ArchiveSafety.safeRelativePath("./a//./b.txt"))
        }
    }

    @Nested
    @DisplayName("zip bombs")
    inner class ZipBombs {
        @Test
        fun `an entry that expands absurdly is rejected`() {
            val verdict =
                ArchiveSafety.inspect(
                    entryName = "bomb.bin",
                    declaredSize = 10L * 1024 * 1024 * 1024,
                    compressedSize = 42 * 1024,
                )

            assertEquals(ArchiveRejection.ENTRY_TOO_LARGE, (verdict as ArchiveEntryVerdict.Rejected).reason)
        }

        @Test
        fun `a plausible size with an implausible ratio is rejected`() {
            val verdict =
                ArchiveSafety.inspect(
                    entryName = "bomb.bin",
                    declaredSize = 100L * 1024 * 1024,
                    compressedSize = 1024,
                )

            assertEquals(
                ArchiveRejection.SUSPICIOUS_COMPRESSION_RATIO,
                (verdict as ArchiveEntryVerdict.Rejected).reason,
            )
        }

        @Test
        fun `a normally compressible document is accepted`() {
            val verdict =
                ArchiveSafety.inspect(
                    entryName = "lecture.txt",
                    declaredSize = 10L * 1024 * 1024,
                    compressedSize = 2L * 1024 * 1024,
                )

            assertTrue(verdict is ArchiveEntryVerdict.Accepted)
        }

        @Test
        fun `a tiny highly-compressible file is not mistaken for a bomb`() {
            val verdict =
                ArchiveSafety.inspect(
                    entryName = "empty-ish.txt",
                    declaredSize = 4096,
                    compressedSize = 8,
                )

            assertTrue(verdict is ArchiveEntryVerdict.Accepted, "small files compress well for boring reasons")
        }

        @Test
        fun `many small entries that add up are rejected in aggregate`() {
            val limits = ArchiveLimits.DEFAULT
            val entryBytes = 256L * 1024 * 1024
            val entries =
                (1..16).map {
                    ArchiveEntryMetadata("part-$it.bin", declaredSize = entryBytes, compressedSize = entryBytes / 2)
                }

            val verdict = ArchiveSafety.inspectArchive(entries, limits)

            assertFalse(verdict.isClean)
            assertTrue(verdict.rejected.any { it.reason == ArchiveRejection.ARCHIVE_TOO_LARGE })
            assertTrue(verdict.totalDeclaredBytes <= limits.maxTotalBytes)
        }

        @Test
        fun `an archive with too many entries is rejected outright`() {
            val limits = ArchiveLimits.DEFAULT.copy(maxEntries = 3)
            val entries = (1..4).map { ArchiveEntryMetadata("f-$it.txt", 10, 5) }

            val verdict = ArchiveSafety.inspectArchive(entries, limits)

            assertTrue(verdict.accepted.isEmpty())
            assertTrue(verdict.rejected.all { it.reason == ArchiveRejection.TOO_MANY_ENTRIES })
        }

        @Test
        fun `negative sizes are treated as malformed rather than trusted`() {
            val verdict = ArchiveSafety.inspect("odd.bin", declaredSize = -1, compressedSize = 10)

            assertEquals(ArchiveRejection.MALFORMED_METADATA, (verdict as ArchiveEntryVerdict.Rejected).reason)
        }
    }

    @Nested
    @DisplayName("whole archives")
    inner class WholeArchives {
        @Test
        fun `a clean archive passes and keeps normalised paths`() {
            val entries =
                listOf(
                    ArchiveEntryMetadata("./notes/week-1.md", 1000, 400),
                    ArchiveEntryMetadata("notes/week-2.md", 1000, 400),
                )

            val verdict = ArchiveSafety.inspectArchive(entries)

            assertTrue(verdict.isClean)
            assertEquals(listOf("notes/week-1.md", "notes/week-2.md"), verdict.accepted.map { it.path })
        }

        @Test
        fun `a poisoned entry is rejected without discarding the rest`() {
            val entries =
                listOf(
                    ArchiveEntryMetadata("notes/week-1.md", 1000, 400),
                    ArchiveEntryMetadata("../../../../data/data/dev.studyflow/databases/studyflow.db", 1000, 400),
                )

            val verdict = ArchiveSafety.inspectArchive(entries)

            assertEquals(1, verdict.accepted.size)
            assertEquals(ArchiveRejection.PATH_TRAVERSAL, verdict.rejected.single().reason)
        }

        @Test
        fun `two entries resolving to the same path are caught`() {
            val entries =
                listOf(
                    ArchiveEntryMetadata("notes/a.md", 1000, 400),
                    ArchiveEntryMetadata("./notes/../notes/a.md", 1000, 400),
                )

            val verdict = ArchiveSafety.inspectArchive(entries)

            assertEquals(1, verdict.accepted.size)
            assertEquals(ArchiveRejection.DUPLICATE_PATH, verdict.rejected.single().reason)
        }
    }
}
