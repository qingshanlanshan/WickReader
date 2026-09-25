# Readium parses and serializes JSON via reflection in a few places.
-keep class org.readium.** { *; }
-dontwarn org.readium.**
# Keep Kotlin metadata for kotlinx.serialization / reflection users.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn org.joda.time.**
-dontwarn com.google.errorprone.annotations.**
# ML Kit discovers its components by reflection (no-arg constructors of the registrars).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_translate.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-dontwarn org.xmlpull.v1.**
# sherpa-onnx reads Kotlin config fields and calls callbacks from native code.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn org.objectweb.asm.**
