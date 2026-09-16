plugins {
    id("studyflow.android.library")
    alias(libs.plugins.protobuf)
}

dependencies {
    implementation(projects.core.model)
    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.javalite)
    testImplementation(libs.kotlinx.coroutines.test)
}

protobuf {
    protoc {
        artifact =
            libs.protobuf.protoc
                .get()
                .toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
