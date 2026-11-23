# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.bmkf.tv.data.model.**$$serializer { *; }
-keepclassmembers class com.bmkf.tv.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.bmkf.tv.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.bmkf.tv.data.model.** { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Coil
-dontwarn coil.**
