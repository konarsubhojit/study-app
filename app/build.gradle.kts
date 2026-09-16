import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("studyflow.android.application")
}

// Pointing the app at a local mock backend is a build flag rather than a code change (issue #63):
//   ./gradlew installDebug -Pstudyflow.apiBaseUrl=http://10.0.2.2:8080
// 10.0.2.2 is the host machine as seen from the emulator.
val defaultApiBaseUrl = "https://api.studyflow.dev"
val apiBaseUrl: String = providers.gradleProperty("studyflow.apiBaseUrl").getOrElse(defaultApiBaseUrl)

extensions.configure<ApplicationExtension> {
    defaultConfig {
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        // The server uses this to decide when an installed build is too old to serve.
        buildConfigField("String", "API_CLIENT_VERSION", "\"$versionName\"")
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.network)
    implementation(projects.feature.auth)
    implementation(projects.feature.insights)
    implementation(projects.feature.materials)
    implementation(projects.feature.settings)
    implementation(projects.feature.tasks)
    implementation(projects.feature.timer)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.timber)
}
