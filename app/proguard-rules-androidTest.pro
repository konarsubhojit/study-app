# Extra rules for the instrumented *test* APK of the `releaseTest` build type only; they never
# reach a shipped APK.
#
# `error_prone_annotations` (pulled in by espresso-core) declares `@IncompatibleModifiers` and
# `@RequiredModifiers` with a `javax.lang.model.element.Modifier[]` member — a compile-time-only
# annotation-processing type Android does not have. Nothing reads those annotations at runtime, but
# R8 refuses to link the test APK without this, so the `productionReleaseTest` suite never ran.
-dontwarn javax.lang.model.element.Modifier
