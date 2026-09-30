import com.google.protobuf.gradle.*

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.floatingclock"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.floatingclock"
        minSdk = 31
        targetSdk = 36
        versionCode = 8
        versionName = "0.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Room migration tests inherit the app's serialization runtime; align core and JSON together.
    implementation(platform(libs.serialization.bom))
    implementation(libs.datastore)
    implementation(libs.room.runtime)
    implementation(libs.protobuf.javalite)
    ksp(libs.room.compiler)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.room.testing)
    implementation(libs.coroutines.android)
    implementation(project(":core:time"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

room { schemaDirectory("$projectDir/schemas") }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.asProvider().get()}" }
    generateProtoTasks { all().configureEach { builtins { create("java") { option("lite") } } } }
}

// The pure JVM module has no Android variants; include its tests in the CI entry point.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(":core:time:test")
}
