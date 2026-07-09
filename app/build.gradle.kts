// :app — Presentation layer: Compose UI, MVI, navigation, Hilt DI assembly (PRD §2.2).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.github.pnck.gallery"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.pnck.gallery"
        minSdk = 26
        // PRD §6.3 suggests 34; Google Play now requires >= 35 for new submissions,
        // and the permission matrix in §6.3 already covers API 34+ behavior.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Device-flow OAuth (ADR-0001). Create a Google OAuth client of type
        // "TVs and Limited Input devices"; it has a client_secret (not a true
        // secret for a public client, but required). Keep OAuth config OUT of the
        // repo: put these in ~/.gradle/gradle.properties or CI secrets.
        buildConfigField(
            "String",
            "GOOGLE_OAUTH_CLIENT_ID",
            "\"${providers.gradleProperty("GALLERY_GOOGLE_CLIENT_ID").getOrElse("")}\"",
        )
        buildConfigField(
            "String",
            "GOOGLE_OAUTH_CLIENT_SECRET",
            "\"${providers.gradleProperty("GALLERY_GOOGLE_CLIENT_SECRET").getOrElse("")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

configurations.configureEach {
    resolutionStrategy {
        // Hilt 2.58 (last AGP 8.x-compatible release) ships kotlin-metadata-jvm 2.2.20,
        // which cannot read the 2.4.0 metadata emitted by Kotlin 2.3.x. Force the
        // matching metadata library; drop this with the AGP 9 / Hilt 2.60 migration.
        force("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
    }
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-provider"))
    implementation(project(":core-network"))
    implementation(project(":core-transport"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.work.runtime.ktx)
    // Encrypted persistence for the transport config (WG keys are sensitive).
    implementation(libs.androidx.security.crypto)

    // Retrofit stack assembled in DI (:app owns wiring; contracts live in :core-provider)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.moshi)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.telephoto.zoomable.coil3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for local unit tests (the framework's is a throwing stub).
    testImplementation(libs.org.json)
}
