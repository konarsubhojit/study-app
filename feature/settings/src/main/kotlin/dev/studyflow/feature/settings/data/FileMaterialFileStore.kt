package dev.studyflow.feature.settings.data

import dev.studyflow.core.domain.lifecycle.MaterialFileStore
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The cached bytes of materials, in app-private storage (issue #78).
 *
 * Restored files are named after the material's id and nothing else: an archive's entry names are
 * attacker-controlled, and the surest way for one never to become a path is for it never to reach
 * the filesystem at all.
 */
public class FileMaterialFileStore(
    private val directory: File,
) : MaterialFileStore {
    override suspend fun open(localPath: String): InputStream? {
        val file = File(localPath)
        return if (file.isFile) file.inputStream() else null
    }

    override suspend fun store(
        materialId: String,
        content: InputStream,
    ): String {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("could not create the materials directory")
        }
        val destination = File(directory, materialId)
        destination.outputStream().use { output -> content.copyTo(output) }
        return destination.absolutePath
    }
}
