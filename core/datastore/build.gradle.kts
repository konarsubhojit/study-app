plugins {
    id("studyflow.android.library")
    alias(libs.plugins.protobuf)
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.network)
    // Account deletion's erasers are domain ports; the stores that implement them live here.
    implementation(projects.core.domain)
    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.javalite)
    implementation(libs.security.crypto)
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
