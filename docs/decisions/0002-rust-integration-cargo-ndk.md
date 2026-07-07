# ADR-0002 · Rust ↔ Android integration via cargo-ndk + uniffi-bindgen

- **Status**: Accepted (2026-07)
- **Amends**: Transport Design §2.2 / D-T1 (which specify Gobley + UniFFI → commonMain)

## Context

EPIC-5's Tier 1 is a Rust core (boringtun + smoltcp + SOCKS5) exposed to Kotlin
through UniFFI. The Transport Design doc (§2.2) picks **Gobley** (`dev.gobley.*`)
to generate the bindings, because Gobley emits into a KMP `commonMain` source set —
the right choice *once the app is KMP*.

Today the app is **Android-only**: there is no `:shared` KMP module, no
`commonMain`. Gobley's whole value proposition (one binding surface for
Android + iOS) does not apply yet, and it carries real cost now:

- Gobley is 0.x; its bindings can break between minor versions (Transport Design R1).
- It expects a KMP project layout; wiring it into a plain AGP library module is
  off its main path.
- It adds a JNA dependency + R8 keep rules to manage (Transport Design R2).

## Decision

For the Android-only MVP, build and bind the Rust core with the **direct
toolchain**, orchestrated by Gradle:

- **cargo-ndk** cross-compiles the crate to `.so` per ABI (arm64-v8a, armeabi-v7a,
  x86_64, x86), output straight into the module's `jniLibs`.
- **uniffi-bindgen** generates the Kotlin bindings into a normal source set.
- A couple of Gradle tasks wire `cargo ndk build` + `uniffi-bindgen generate` into
  the module's `preBuild`, so `./gradlew build` produces everything. CI just needs
  Rust + targets + the NDK (already on the runner).
- UniFFI's Kotlin runtime still uses JNA → the JNA R8 keep rules (Transport Design
  R2) are added regardless of Gobley.

The FFI surface stays exactly the 5 primitives the Transport Design §2.1 defines
(`start` / `localSocksPort` / `health` / `stop` / `setStateCallback`), plus one
addition for the login path — see below. Keeping that surface narrow is what makes
the eventual Gobley swap cheap.

## Consequences

- No dependency on a 0.x KMP plugin; the native build is transparent shell tooling
  that behaves the same locally and in CI.
- **When the app goes KMP** (Transport Design's future `:shared`), switch binding
  generation to Gobley so the same Rust core serves iOS too. The crate and its
  UniFFI interface do not change — only the Gradle wiring does. This ADR is the
  seam, not a dead end.
- Same-surface note for auth (ADR-0001): the earlier plan to feed a login browser's
  traffic through a TUN was dropped; device flow needs no VpnService, so the Rust
  core exposes **only** a local SOCKS5 (+ optional HTTP CONNECT) inbound — no TUN
  primitive is required.
