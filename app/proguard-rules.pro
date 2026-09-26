# Keep rules for the R8-minified release build.
#
# Only rules this build cannot derive for itself belong here. Every other dependency ships its own
# consumer rules inside its artifact — Room, Hilt, WorkManager, Ktor, kotlinx.serialization and App
# Startup all do — and the merged result of all of them is written to
# `app/build/outputs/mapping/<variant>/configuration.txt` by each release build.

# Proto DataStore messages (`core/datastore/src/main/proto`) are generated against
# protobuf-javalite, the one dependency here that ships no rules of its own: AndroidX and Tink both
# repackage protobuf and carry this rule for their own copy. Lite messages are parsed reflectively,
# with `MessageSchema` looking each field up by its declared name, so a renamed field fails with
# "Field defaultFocusMinutes_ ... not found" the first time settings are read — which is during
# startup, before any UI is drawn.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# A release crash is only actionable if its stack trace survives shrinking. These attributes keep
# file and line information in the APK so `mapping.txt` from the same build retraces it back to
# source; the rename drops the original file names without losing the line numbers.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
