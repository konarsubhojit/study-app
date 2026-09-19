import com.android.build.api.dsl.ApplicationExtension

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

extensions.configure<ApplicationExtension> {
    val appVersionName = "1.0.0"

    defaultConfig {
        versionCode = 1
        versionName = appVersionName

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        // The server uses this to decide when an installed build is too old to serve.
        buildConfigField("String", "API_CLIENT_VERSION", "\"$appVersionName\"")
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
}
