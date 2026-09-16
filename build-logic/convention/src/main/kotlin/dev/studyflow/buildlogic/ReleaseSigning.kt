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
 * The order applies per value, so a machine may keep the keystore path in the file and the
 * passwords in the environment; only a set that is incomplete across *both* sources is an error.
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
    val required = releaseSigningRequired()

    if (credentials == null) {
        if (required) {
            failBuild(
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

    // Relative paths resolve against the root project, where `keystore.properties` lives, rather
    // than against `:app`, so both sources agree on what a relative path means.
    val storeFile = rootProject.file(credentials.storeFile)
    if (!storeFile.isFile) {
        failBuild(
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
            // `minSdk` is 26, so every target supports APK Signature Scheme v2; the legacy JAR
            // signature would only add size.
            enableV1Signing = false
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
        failBuild(
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

/**
 * A bare `-Pstudyflow.requireReleaseSigning` resolves to an empty value, and a typo must not be
 * read as "not required" — the point of the flag is that an unsigned artifact becomes impossible.
 */
private fun Project.releaseSigningRequired(): Boolean {
    val value = providers.gradleProperty(REQUIRE_SIGNING_PROPERTY).orNull ?: return false
    if (value.isEmpty()) {
        return true
    }
    return value.toBooleanStrictOrNull()
        ?: failBuild("$REQUIRE_SIGNING_PROPERTY must be true or false, but was '$value'.")
}

private fun Project.failBuild(message: String): Nothing =
    throw GradleException("Project $path: $message")

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
