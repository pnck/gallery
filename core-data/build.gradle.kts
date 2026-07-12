// :core-data — local Source of Truth: Room, MediaStore scanner, WorkManager
// workers, Repository implementations (PRD §2.2). Android-specific by design.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.pnck.gallery.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    api(project(":core-domain"))
    implementation(project(":core-provider"))

    implementation(libs.kotlinx.coroutines.android)
    // FileProvider for exposing cacheDir originals to share/edit intents (PRD §9.1).
    implementation(libs.androidx.core.ktx)
    // api: GalleryDatabase (a RoomDatabase subtype) is provided to :app's DI graph,
    // so the supertype must be visible to consumers.
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
