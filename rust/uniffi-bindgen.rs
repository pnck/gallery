// Standalone UniFFI binding generator. Gradle runs this to emit the Kotlin
// bindings from the crate's exported interface. See ADR-0002.
fn main() {
    uniffi::uniffi_bindgen_main()
}
