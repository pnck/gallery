# App-specific ProGuard/R8 rules.
#
# Moshi codegen, Room, Hilt, Retrofit and OkHttp ship their own consumer rules;
# nothing extra is required for them here. JNA/UniFFI keeps for the Rust
# transport core (EPIC-5) live in core-transport/consumer-rules.pro and merge
# automatically via the AAR.

# AppAuth uses reflection on browser descriptors.
-keep class net.openid.appauth.** { *; }

# Tink (pulled in by androidx.security:security-crypto) references errorprone's
# compile-only annotations; they are absent at runtime by design.
-dontwarn com.google.errorprone.annotations.**
