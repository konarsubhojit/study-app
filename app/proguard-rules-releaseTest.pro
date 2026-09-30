# Extra rules for the *app* APK of the `releaseTest` build type only — never `release`, so nothing
# here reaches a shipped build.
#
# An instrumented test APK does not bundle the libraries it shares with the app under test; it
# resolves them from the app at runtime. Against a shrunk app that fails before any test starts:
# R8 has removed every library member only the test runner uses (`kotlin.LazyKt`, for one, crashes
# `AndroidJUnitRunner.onCreate`), and Gradle Managed Devices then reports zero tests as a pass.
#
# Only the *libraries* the test framework calls into are kept. The app's own code (`dev.studyflow`)
# and the Hilt/WorkManager bindings it relies on are deliberately not, so this build type still
# shrinks them exactly as `release` does — that is what the suite is here to check.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.test.** { *; }
-keep class androidx.tracing.** { *; }
-keep class androidx.collection.** { *; }
-keep class androidx.core.os.** { *; }
-keep class androidx.concurrent.futures.** { *; }
-keep class androidx.work.** { *; }
-dontwarn kotlinx.coroutines.debug.**
