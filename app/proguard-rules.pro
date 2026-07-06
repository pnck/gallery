# App-specific ProGuard/R8 rules.
#
# Moshi codegen, Room, Hilt, Retrofit and OkHttp ship their own consumer rules;
# nothing extra is required for them here.
#
# NOTE (Transport Design R2): when the Rust/UniFFI transport core lands (EPIC-5),
# JNA keep rules MUST be added here, otherwise release builds throw
# UnsatisfiedLinkError. See docs/BYOS-Transport-Layer-Design.md §2.2.

# AppAuth uses reflection on browser descriptors.
-keep class net.openid.appauth.** { *; }
