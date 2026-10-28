# Keep Kotlinx Serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.lingyicute.starrypass.**$$serializer { *; }
-keepclassmembers class io.github.lingyicute.starrypass.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.lingyicute.starrypass.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Bouncy Castle low-level crypto classes (Argon2BytesGenerator / HKDFBytesGenerator)
# Only lightweight crypto API is used, suppress unused JCE/JNDI optional references
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.jce.**
-dontwarn org.bouncycastle.jcajce.**
