package dev.studyflow.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project

/**
 * Configuration every Android module shares, whatever it produces (issue #11).
 *
 * SDK levels come from the catalogue, so a platform bump is a one-line change rather than a sweep
 * through every module. The namespace is derived from the module path, which keeps a new module's
 * build file free of boilerplate that only ever restates where the module lives; a module that
 * needs a different namespace can still set one.
 *
 * The packaging excludes drop the duplicated licence files that otherwise make two libraries with
 * the same metadata fail to merge into an APK.
 */
internal fun Project.configureAndroid(extension: CommonExtension) {
    with(extension) {
        namespace = defaultNamespace()
        compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()

        defaultConfig.minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
        defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        compileOptions.sourceCompatibility = JavaVersion.toVersion(javaVersion)
        compileOptions.targetCompatibility = JavaVersion.toVersion(javaVersion)

        packaging.resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
            "/META-INF/*.version",
        )

        // Unit test coverage is collected on the debug variant only, so a release build is never
        // instrumented. The report task is `createDebugUnitTestCoverageReport`.
        buildTypes.getByName("debug").enableUnitTestCoverage = true

        configureManagedDevices(this)
    }

    configureKotlin()
}

/**
 * A Gradle Managed Device, so an instrumented run is reproducible instead of "whatever emulator
 * was attached" (issue #65).
 *
 * Gradle creates, boots, uses and tears the device down itself, from a fixed system image: the same
 * API level and the same image on a laptop and on CI, and no state carried over from the previous
 * run to make a failure unreproducible. The image is an ATD (automated test device) build — no Play
 * services, no launcher, far less to boot — because a headless test run needs none of that.
 *
 * Running it is `./gradlew pixel6Api34DebugAndroidTest`; CI does so nightly rather than per pull
 * request, because downloading and booting an emulator does not belong in the inner loop.
 */
private fun configureManagedDevices(extension: CommonExtension) {
    extension.testOptions.managedDevices.localDevices.create("pixel6Api34").apply {
        device = "Pixel 6"
        sdkVersion = MANAGED_DEVICE_SDK
        systemImageSource = "aosp-atd"
    }
}

/**
 * API level of the managed device.
 *
 * Pinned rather than tracking `compileSdk`: the test run must not change because the build was
 * retargeted, and ATD images exist only for a subset of API levels.
 */
private const val MANAGED_DEVICE_SDK = 34

/** `:feature:timer` becomes `dev.studyflow.feature.timer`. */
private fun Project.defaultNamespace(): String =
    path.split(":")
        .filter { it.isNotEmpty() }
        .joinToString(separator = ".", prefix = "dev.studyflow.") { segment ->
            segment.replace("-", "").lowercase()
        }
