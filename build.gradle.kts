// Declaring the plugins here — without applying them — puts them on the shared build classpath so
// the convention plugins in `:build-logic` can apply them by id. Modules never list them; adding a
// module changes nothing in this file (issue #11).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless) apply false
    id("studyflow.module-boundaries")
}
