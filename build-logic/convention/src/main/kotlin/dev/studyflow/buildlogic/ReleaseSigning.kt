package dev.studyflow.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.StringReader
import java.util.Properties

/**
 * Upload-key signing for the release build type (issue #71).
 *
 * Play App Signing holds the app signing key; this configures the *upload* key, which is the one
 * Google Play uses to verify who uploaded a bundle. Neither key material nor its passwords may ever
 * enter the repository, so the credentials are read from, in order:
 *
 * 1. a `keystore.properties` file outside version control (git-ignored, for local release builds),
 * 2. `STUDYFLOW_UPLOAD_*` environment variables (GitHub Actions secrets, for CI).
 *
 * With no credentials at all the release build type stays unsigned, so anybody can clone the
 * repository and run `assembleRelease`. That is a convenience, never a release path: pass
 * `-Pstudyflow.requireReleaseSigning=true` (as the release workflow does) to turn a missing or
 * partial credential set into a build failure instead of an unsigned artifact.
 *
 * See `docs/release/play-console.md` for key generation, storage, and the recovery procedure.
 */
internal fun Project.configureReleaseSigning(extension: ApplicationExtension) {
    val credentials = resolveUploadKeyCredentials()
    val required = providers.gradleProperty(REQUIRE_SIGNING_PROPERTY).orNull.toBoolean()

    if (credentials == null) {
        if (required) {
            error(
                "Release signing is required but no upload key credentials were found. Provide " +
                    "$KEYSTORE_PROPERTIES_FILE or the $ENV_PREFIX* environment variables; see " +
                    "docs/release/play-console.md.",
            )
        }
        logger.lifecycle(
            "No upload key credentials found: the release build will be unsigned and cannot be " +
                "uploaded to Google Play. See docs/release/play-console.md.",
        )
        return
    }

    val storeFile = file(credentials.storeFile)
    if (!storeFile.isFile) {
        error(
            "Upload keystore '${credentials.storeFile}' does not exist. The keystore is never " +
                "committed; see docs/release/play-console.md for how to restore it.",
        )
    }

    with(extension) {
        val releaseSigningConfig = signingConfigs.create(RELEASE) {
            this.storeFile = storeFile
            storePassword = credentials.storePassword
            keyAlias = credentials.keyAlias
            keyPassword = credentials.keyPassword
            // Both schemes: v1 keeps API 26 installs verifiable, v2 is what Play expects.
            enableV1Signing = true
            enableV2Signing = true
        }
        buildTypes.getByName(RELEASE) {
            signingConfig = releaseSigningConfig
        }
    }
}

/** The four values needed to sign with the upload key; a partial set is always an error. */
private data class UploadKeyCredentials(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

private fun Project.resolveUploadKeyCredentials(): UploadKeyCredentials? {
    val fromFile = uploadKeyPropertiesFile()
    val values = KEYS.associateWith { key ->
        fromFile?.getProperty(key.propertyName)?.takeIf(String::isNotBlank)
            ?: providers.environmentVariable(key.environmentName).orNull?.takeIf(String::isNotBlank)
    }

    val missing = values.filterValues { it == null }.keys
    if (missing.size == KEYS.size) {
        return null
    }
    if (missing.isNotEmpty()) {
        error(
            "Incomplete upload key credentials: missing " +
                missing.joinToString { "${it.propertyName}/${it.environmentName}" } +
                ". See docs/release/play-console.md.",
        )
    }

    return UploadKeyCredentials(
        storeFile = requireNotNull(values.getValue(CredentialKey.STORE_FILE)),
        storePassword = requireNotNull(values.getValue(CredentialKey.STORE_PASSWORD)),
        keyAlias = requireNotNull(values.getValue(CredentialKey.KEY_ALIAS)),
        keyPassword = requireNotNull(values.getValue(CredentialKey.KEY_PASSWORD)),
    )
}

/**
 * Reads the git-ignored credentials file through the provider API so that the configuration cache
 * is invalidated when it changes, rather than serving a stale signing configuration.
 */
private fun Project.uploadKeyPropertiesFile(): Properties? {
    val propertiesFile = rootProject.layout.projectDirectory.file(KEYSTORE_PROPERTIES_FILE)
    val contents = providers.fileContents(propertiesFile).asText.orNull ?: return null
    return Properties().apply { StringReader(contents).use(::load) }
}

private fun Project.error(message: String): Nothing = throw GradleException("${path}: $message")

private enum class CredentialKey(val propertyName: String, val environmentName: String) {
    STORE_FILE("storeFile", "${ENV_PREFIX}STORE_FILE"),
    STORE_PASSWORD("storePassword", "${ENV_PREFIX}STORE_PASSWORD"),
    KEY_ALIAS("keyAlias", "${ENV_PREFIX}KEY_ALIAS"),
    KEY_PASSWORD("keyPassword", "${ENV_PREFIX}KEY_PASSWORD"),
}

private val KEYS = CredentialKey.entries

private const val ENV_PREFIX = "STUDYFLOW_UPLOAD_"
private const val KEYSTORE_PROPERTIES_FILE = "keystore.properties"
private const val REQUIRE_SIGNING_PROPERTY = "studyflow.requireReleaseSigning"
private const val RELEASE = "release"
