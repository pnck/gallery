// :core-transport — the Android home of the Tier-1 transport core (Transport
// Design §2, EPIC-5). It owns three things the pure-JVM :core-network contract
// cannot:
//   1. the Rust `.so` (userspace SOCKS5 + upcoming WireGuard), built by cargo-ndk;
//   2. the UniFFI-generated Kotlin bindings (JNA) that call into that `.so`;
//   3. the NetworkTransport implementations that adapt the core to the insertion
//      layer (OutboundRouter) defined in :core-network.
//
// It depends on :core-network (the contract) but NOTHING depends on it except
// :app's DI — keeping invariant #8 (transport off == never integrated) intact.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.pnck.gallery.transport"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        // JNA + UniFFI reach the Rust core purely over TCP loopback; no extra ABI
        // filters here so CI can emit every ABI it is asked to.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // TransportController logs VPN yield/resume transitions via android.util.Log;
        // let JVM unit tests run through those calls instead of "not mocked" crashes.
        unitTests.isReturnDefaultValues = true
    }

    // The UniFFI bindings are generated Kotlin checked into src/main/uniffi (see
    // regen-bindings.sh). Keeping them separate from hand-written code makes the
    // "do not edit" boundary obvious and lets CI diff them for drift.
    sourceSets["main"].kotlin.srcDir("src/main/uniffi")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core-network"))
    implementation(libs.kotlinx.coroutines.android)
    // The Android-mode UniFFI cleaner is annotated @RequiresApi(33).
    implementation(libs.androidx.annotation)

    // UniFFI bindings call the Rust core through JNA. The @aar variant bundles
    // JNA's own per-ABI native lib; the plain jar would crash at load on device.
    implementation(libs.jna) {
        artifact { type = "aar" }
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ---------------------------------------------------------------------------
// Rust .so build (cargo-ndk). See ADR-0002.
//
// The Android NDK ships only x86_64 host toolchains, so this task can only run
// where the host is x86_64 (CI) — it CANNOT run on the arm64 dev container. It
// is therefore gated behind -Pgallery.buildRust=true and only then wired into
// the Android build. Without the flag the module still compiles (the bindings
// are pure Kotlin); the resulting APK simply carries no transport .so, which is
// exactly what we want locally where no emulator can load it anyway.
// ---------------------------------------------------------------------------
val buildRust = providers.gradleProperty("gallery.buildRust").orNull == "true"
val rustDir = rootProject.layout.projectDirectory.dir("rust")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

// ABIs to build. Override with -Pgallery.rustAbis=arm64-v8a,x86_64 (comma list).
val rustAbis = providers.gradleProperty("gallery.rustAbis").orElse("arm64-v8a,x86_64")

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compile the transport core .so for each Android ABI via cargo-ndk."
    workingDir = rustDir.asFile
    inputs.dir(rustDir.dir("src"))
    inputs.file(rustDir.file("Cargo.toml"))
    outputs.dir(jniLibsDir)

    val abiArgs = rustAbis.get().split(",").flatMap { listOf("-t", it.trim()) }
    // cargo-ndk maps Android ABI names to Rust targets and drops the .so into the
    // jniLibs/<abi>/ layout the Android packager expects.
    commandLine(
        listOf("cargo", "ndk") + abiArgs +
            listOf("-o", jniLibsDir.asFile.absolutePath, "build", "--release"),
    )

    doFirst {
        val ndk = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("NDK_HOME")
        require(!ndk.isNullOrBlank()) {
            "ANDROID_NDK_HOME must be set to build the Rust transport core."
        }
    }
}

if (buildRust) {
    // Make the .so a prerequisite of merging jniLibs into the APK/AAR.
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
        .configureEach { dependsOn(cargoNdkBuild) }
    tasks.named("preBuild").configure { dependsOn(cargoNdkBuild) }
}

// Convenience: regenerate the UniFFI Kotlin bindings from the debug host build.
// Requires a host Rust toolchain (see regen-bindings.sh for the container setup).
tasks.register<Exec>("regenerateUniffiBindings") {
    group = "rust"
    description = "Regenerate UniFFI Kotlin bindings into src/main/uniffi (host build)."
    workingDir = rootProject.projectDir
    commandLine("bash", "core-transport/regen-bindings.sh")
}
