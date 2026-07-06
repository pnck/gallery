// :core-domain — pure Kotlin, zero Android dependencies (PRD §2.2).
// Part of the reusable "virtual backend"; keep it KMP-portable.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    // paging-common is JVM/KMP-safe; exposes PagingData/PagingSource to the domain contract
    api(libs.androidx.paging.common)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
