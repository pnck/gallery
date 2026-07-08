# JNA + UniFFI keep rules (Transport Design R2, ADR-0002).
#
# UniFFI's generated Kotlin reaches the Rust .so entirely through JNA reflection:
# JNA maps Java interfaces/structs to native symbols by name at runtime, so R8
# must not rename or strip any of it.

# JNA runtime itself.
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-dontwarn java.awt.**

# JNA maps Structure subclasses field-by-field via reflection; names + order matter.
-keep class * extends com.sun.jna.Structure { *; }
-keep interface * extends com.sun.jna.Library { *; }
-keep interface * extends com.sun.jna.Callback { *; }

# The generated UniFFI bindings (RustBuffer, UniffiLib, converters, callback
# interfaces) are all reflected over by JNA.
-keep class uniffi.gallery_transport.** { *; }
-keepclassmembers class uniffi.gallery_transport.** { *; }
