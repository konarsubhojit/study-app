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
val defaultApiBaseUrl = "https://api.studyflow.dev"
val apiBaseUrl: String = providers.gradleProperty("studyflow.apiBaseUrl").getOrElse(defaultApiBaseUrl)
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

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        // The server uses this to decide when an installed build is too old to serve.
        buildConfigField("String", "API_CLIENT_VERSION", "\"$appVersionName\"")
    }

    flavorDimensions += "backend"
    productFlavors {
        create("production") {
            dimension = "backend"
        }
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
