# protobuf-lite resolves fields reflectively from the schema string baked into each
# generated class, so a renamed field throws "Field theme_ for X not found" at the
# first DataStore read. Keeping the `*_` fields and the generated members is the
# minimum that keeps that lookup working.
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    static ** parseFrom(...);
    static ** newBuilder(...);
    ** toBuilder();
    ** getDefaultInstance();
}
-keep class dev.studyflow.core.datastore.proto.** { *; }
-dontwarn com.google.protobuf.**

# ---------------------------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------------------------
# kotlinx-serialization-core ships its own `META-INF/proguard` rules (verified in
# kotlinx-serialization-core-jvm-1.11.0.jar), and R8 reads those from every jar on the classpath,
# not only from AARs. That bundled rule already keeps the generated `$$serializer` and
# `Companion.serializer()` members for every `@Serializable` class in the app, which is what keeps
# `StudyFlowJson`'s `ignoreUnknownKeys = true` decoding of the `:core:network` DTOs
# (`KtorStudyFlowApi`) working. The two lines below are not filling a gap in that bundled rule; they
# pin the concrete package so a future kotlinx.serialization upgrade that narrows or drops its
# bundled rule fails a build instead of a release install.
-keepclassmembers @kotlinx.serialization.Serializable class dev.studyflow.core.network.model.** { *; }

# `AppRoute` (see androidx.navigation3 below) is the one `@Serializable` type in the app that is
# *polymorphic*: it is a sealed interface, and the default kotlinx.serialization discriminator for
# a sealed hierarchy is the encoded subtype's fully qualified class name, not a member. The bundled
# rule above keeps serializer *members* but never the class's own name, so R8 is free to rename
# `HomeRoute`, `TimerRoute` and friends — which would not fail the build, only fail to restore the
# navigation back stack across process death with an "unknown polymorphic type" exception. Keeping
# just the names (not blanket `-keep`) is enough, and lets R8 keep stripping unused members.
-keepnames class dev.studyflow.app.navigation.** implements androidx.navigation3.runtime.NavKey

# ---------------------------------------------------------------------------------------------
# Ktor client (ktor-client-core, -okhttp, -auth, -content-negotiation, -logging,
# ktor-serialization-kotlinx-json)
# ---------------------------------------------------------------------------------------------
# Unlike kotlinx.serialization and Room, none of these Ktor 3.6.0 artifacts ship a bundled
# consumer proguard file (checked every jar under `io.ktor:*:3.6.0`; none has a
# `META-INF/proguard` entry). `NetworkModule` and `HttpTokenRefresher` both construct their engine
# explicitly with `OkHttp.create()`, but `OkHttpEngine`/`OkHttpEngineContainer` are still registered
# as a `java.util.ServiceLoader` provider for `io.ktor.client.HttpClientEngineContainer`
# (`META-INF/services/io.ktor.client.HttpClientEngineContainer`), and Ktor's plugin pipeline
# (Auth, ContentNegotiation, Logging) looks its installed plugins up by the `AttributeKey` each
# plugin object registers itself under during `HttpClientConfig.install`. Renaming or stripping any
# of these breaks that lookup on the first network call rather than at build time.
-keep class io.ktor.client.engine.okhttp.OkHttpEngine { *; }
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-keepclassmembers class io.ktor.** {
    volatile <fields>;
}
-dontwarn io.ktor.**
# OkHttp's TLS handshake probes for optional providers that are never on an Android classpath.
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------------------------
# Room (:core:database — StudyFlowDatabase, its DAOs and entities)
# ---------------------------------------------------------------------------------------------
# `room-runtime`'s bundled rule (`-keep class * extends androidx.room.RoomDatabase { void
# <init>(); }`) already keeps `StudyFlowDatabase` *and* the generated `StudyFlowDatabase_Impl`,
# because the generated class extends the abstract database and the wildcard match is transitive.
# That is what protects `RoomDatabase.Builder.build()`, which resolves the generated
# implementation by computing `klass.canonicalName + "_Impl"` and loading it with
# `Class.forName` — a lookup that survives shrinking but not renaming. DAOs and entities are never
# looked up by name (the generated `_Impl` classes call `new FooDao_Impl(this)` directly), so they
# need no extra protection; this block only documents that and keeps `-dontwarn` quiet for the
# paging integration.
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------------------------
# Hilt (@EntryPoint) and androidx.hilt.work (@HiltWorker)
# ---------------------------------------------------------------------------------------------
# Both are already fully covered by bundled consumer rules and need nothing added here:
#  - `hilt-android` keeps every `@dagger.hilt.EntryPoint` interface with
#    `-keep,allowobfuscation,allowshrinking`, which is what lets
#    `SchedulingEntryPoint` (:core:scheduling) and `WidgetEntryPoint` (:app) survive the reflective
#    cast `EntryPointAccessors` performs.
#  - `androidx.hilt:hilt-work` keeps `-keepnames @androidx.hilt.work.HiltWorker class * extends
#    androidx.work.ListenableWorker`, which is what lets `HiltWorkerFactory` resolve
#    `ReminderDeliveryWorker` and `SyncWorker` (:core:scheduling) and the material upload worker by
#    the class name WorkManager stores in each `WorkSpec`.

# ---------------------------------------------------------------------------------------------
# Manifest-referenced components
# ---------------------------------------------------------------------------------------------
# AGP generates a keep rule for every `<activity>`, `<service>`, `<receiver>` and `<provider>`
# element in the merged manifest automatically (see
# `app/build/intermediates/aapt_proguard_file/*/aapt_rules.txt`), so `TimerWidgetReceiver`,
# `TodayTasksWidgetReceiver`, `StudyTimerTileService`, `StudyFlowInjectionReceiver`,
# `TimerRecoveryReceiver`, `TimerIntervalReceiver` and `ReminderAlarmReceiver` are already kept
# without any rule here. `AppStartupInitializer` is different: it is named only inside a
# `<meta-data>` *value* on `androidx.startup.InitializationProvider`, which aapt's rule generator
# does not parse, so `AppStartupInitializer.create(Context)` is resolved by
# `Class.forName(value).getDeclaredConstructor().newInstance()` against a name R8 is otherwise free
# to change.
-keep class dev.studyflow.app.startup.AppStartupInitializer { <init>(); }

# ---------------------------------------------------------------------------------------------
# Glance (home-screen widgets)
# ---------------------------------------------------------------------------------------------
# `glance-appwidget`'s bundled rule already keeps every `ActionCallback` (the widget button click
# handlers `TimerPlaybackAction`, `TimerStopAction`, `TaskCompleteAction`, `TaskSnoozeAction`
# resolve through) and the fields of its own internal protobuf-lite state messages. `TimerWidget`
# and `TodayTasksWidget` themselves are only ever constructed directly
# (`TimerWidgetReceiver.glanceAppWidget = TimerWidget()`), so R8 already traces that reference; the
# rule below is the same "keep the name of anything implementing the framework's widget type"
# safety net as the `NavKey` one above, in case a future widget is looked up by class name the way
# `AppWidgetManager` looks up its provider `<receiver>` (already covered by the manifest rule).
-keepnames class * extends androidx.glance.appwidget.GlanceAppWidget