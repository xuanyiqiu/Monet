# Keep annotations used by kotlinx.serialization and Retrofit.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault

# kotlinx.serialization for the app's own @Serializable classes.
-keepclassmembers class dev.monet.app.** { *** Companion; }
-keepclasseswithmembers class dev.monet.app.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.monet.app.**$$serializer { *; }

# Retrofit service interface that Retrofit creates a dynamic proxy for.
-keep,allowobfuscation,allowshrinking interface dev.monet.app.core.network.MonetService

# Optional TLS providers referenced by OkHttp but not shipped on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
