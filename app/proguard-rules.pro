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