# LocalMind R8 / ProGuard Keep Rules

# Preserve stack trace line numbers for crash diagnostics while keeping APK compact
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# llama.cpp JNI bridge & native -> Kotlin stream callback
-keep class com.example.data.engine.LlamaCppNativeBridge {
    native <methods>;
}
-keepclassmembers class com.example.data.engine.LlamaCppNativeBridge {
    native <methods>;
}
-keep interface com.example.data.engine.LlamaStreamCallback { *; }
-keep class * implements com.example.data.engine.LlamaStreamCallback { *; }

# LiteRT & LiteRT-LM native JNI bridges and runtime types
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litert.**
-dontwarn com.google.ai.edge.litertlm.**

# kotlinx.serialization (generated serializers, Companion objects, nav @Serializable routes)
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.**$$serializer { *; }
-keepclassmembers class com.example.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.example.** { *; }

# Room entities, DAOs, and Database constructor
-keep class * extends androidx.room.RoomDatabase
-keep class com.example.data.local.** { *; }
-dontwarn androidx.room.**

# OkHttp / Coroutines / CameraX safe ignores
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_snuffers.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

