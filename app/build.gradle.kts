import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

plugins {
    id("studyflow.android.application")
    id("studyflow.compose")
    alias(libs.plugins.kotlin.serialization)
}

// Pointing the app at a local mock backend is a build flag rather than a code change (issue #63):
//   ./gradlew installDebug -Pstudyflow.apiBaseUrl=http://10.0.2.2:8080
// 10.0.2.2 is the host machine as seen from the emulator.
//
// The contract is served by the `api` Supabase Edge Function (ADR 0017), whose URL is
// `https://<project-ref>.supabase.co/functions/v1/api`. The gateway prefix stays in the path and
// the function strips it internally, so the base URL ends at the function name and `ApiEndpoint`
// paths are appended unchanged. The project ref is deployment configuration rather than a secret —
// it appears in every request URL — so it is supplied the same way the release signing values and
// version overrides are, never committed as a Kotlin constant:
//   ./gradlew assembleProductionRelease -Pstudyflow.supabaseProjectRef=<project-ref>
val defaultApiBaseUrl = "https://api.studyflow.dev"
val supabaseProjectRef: String? =
    providers
        .gradleProperty("studyflow.supabaseProjectRef")
        .orElse(providers.environmentVariable("STUDYFLOW_SUPABASE_PROJECT_REF"))
        .orNull
        ?.takeIf(String::isNotBlank)
        ?.also {
            require(Regex("""[a-z0-9]{8,}""").matches(it)) {
                "studyflow.supabaseProjectRef '$it' is not a Supabase project ref"
            }
        }
val apiBaseUrl: String =
    providers
        .gradleProperty("studyflow.apiBaseUrl")
        .getOrElse(
            supabaseProjectRef?.let { "https://$it.supabase.co/functions/v1/api" } ?: defaultApiBaseUrl,
        )

// The Google *Web application* OAuth client id, which is a public identifier and ships in the APK
// by design: Credential Manager needs it to ask for an ID token, and the server needs the same
// value in its GOOGLE_SERVER_CLIENT_ID secret to validate that token's audience. Do not "harden" it
// into a secret store — that hides a value the request already carries, and a *mismatch* with the
// server, not disclosure, is the failure that matters: audience validation then rejects every
// token and sign-in looks broken rather than misconfigured. The Android client id is a different
// value and will not work here.
val googleServerClientId: String =
    providers
        .gradleProperty("studyflow.googleServerClientId")
        .orElse(providers.environmentVariable("STUDYFLOW_GOOGLE_SERVER_CLIENT_ID"))
        .getOrElse("")
val versionProperties =
    Properties().apply {
        file("version.properties").inputStream().use(::load)
    }
val configuredVersionName =
    requireNotNull(versionProperties.getProperty("versionName")) {
        "app/version.properties must define versionName"
    }
val appVersionName =
    providers
        .gradleProperty("studyflow.versionName")
        .getOrElse(configuredVersionName)
        .also {
            require(Regex("""\d+\.\d+\.\d+""").matches(it)) {
                "StudyFlow version '$it' must use major.minor.patch semantic versioning"
            }
        }
val (majorVersion, minorVersion, patchVersion) = appVersionName.split('.').map(String::toLong)
require(minorVersion <= 999 && patchVersion <= 999) {
    "StudyFlow minor and patch versions must fit in three digits"
}
val localVersionCode =
    (majorVersion * 1_000_000 + minorVersion * 1_000 + patchVersion)
        .also {
            require(it in 1..Int.MAX_VALUE.toLong()) {
                "Local StudyFlow versionCode is outside Android's supported range"
            }
        }.toInt()
val appVersionCode =
    providers
        .gradleProperty("studyflow.versionCode")
        .orElse(providers.environmentVariable("GITHUB_RUN_NUMBER"))
        .map(String::toInt)
        .getOrElse(localVersionCode)
        .also {
            require(it > 0) { "StudyFlow versionCode must be positive" }
        }
val releaseStoreFile = providers.environmentVariable("STUDYFLOW_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("STUDYFLOW_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("STUDYFLOW_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("STUDYFLOW_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
require(releaseSigningValues.all { it == null } || releaseSigningValues.all { it != null }) {
    "Release signing requires store file, store password, key alias, and key password"
}

extensions.configure<ApplicationExtension> {
    defaultConfig {
        versionCode = appVersionCode
        versionName = appVersionName

        // The server uses this to decide when an installed build is too old to serve.
        buildConfigField("String", "API_CLIENT_VERSION", "\"$appVersionName\"")
    }

    flavorDimensions += "backend"
    productFlavors {
        create("production") {
            dimension = "backend"
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$googleServerClientId\"")
        }
        // The mock flavour is served entirely by `FakeStudyFlowBackend` and performs no network
        // I/O, so it is given no base URL at all: a build config field it never reads is an
        // invitation for someone to start reading it.
        create("mock") {
            dimension = "backend"
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
        }
    }

    if (releaseStoreFile != null) {
        val releaseSigning =
            signingConfigs.create("studyflowRelease") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        buildTypes.getByName("release").signingConfig = releaseSigning
    }
}

tasks.register("printVersionName") {
    group = "versioning"
    description = "Prints the canonical StudyFlow semantic version."
    inputs.property("versionName", appVersionName)
    doLast {
        logger.quiet(inputs.properties.getValue("versionName").toString())
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.designsystem)
    implementation(projects.core.network)
    implementation(projects.core.notifications)
    implementation(projects.core.scheduling)
    implementation(projects.core.storage)
    implementation(projects.feature.auth)
    implementation(projects.feature.history)
    implementation(projects.feature.home)
    implementation(projects.feature.insights)
    implementation(projects.feature.materials)
    implementation(projects.feature.settings)
    implementation(projects.feature.tasks)
    implementation(projects.feature.timer)
    implementation(libs.androidx.core.ktx)
    // Home-screen widgets and their theming (issue #61); the Quick Settings tile is a platform API.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    testImplementation(projects.core.testing)
    // ProductionReleaseSmokeInstrumentedTest reads logcat directly rather than through Espresso,
    // so it works the same way against a black-box, minified `productionReleaseTest` install.
    androidTestImplementation(libs.androidx.test.uiautomator)
}
