# Mosaic release rules.
#
# R8 full mode is on (the AGP default). These keeps cover the four libraries
# that resolve types reflectively at runtime; everything else is free to shrink.

# --- SQLCipher: loaded through JNI, so R8 cannot see the references. ---
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }
-dontwarn net.zetetic.**

# --- Room / SQLite support library ---
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- kotlinx.serialization: generated serializers are found by name ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.mosaic.privatevault.sync.api.** {
    *** Companion;
}
-keepclasseswithmembers class app.mosaic.privatevault.sync.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.mosaic.privatevault.sync.api.**$$serializer { *; }

# --- Retrofit / OkHttp ---
# Retrofit reads the HTTP annotations reflectively at call time. Renaming them
# consistently would in principle still work, but a stripped annotation fails
# only at runtime, on the one code path that needs a network — so the service
# interface and the annotation types are kept verbatim.
-keepattributes Signature, Exceptions, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep interface app.mosaic.privatevault.sync.api.InstagramService { *; }
-keep class retrofit2.http.** { *; }
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- WorkManager instantiates workers by name ---
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# --- NotificationListenerService is instantiated by the system ---
-keep class app.mosaic.privatevault.sync.notification.MosaicNotificationListener { *; }

# --- Strip any logging that slipped past SafeLog, in release only. ---
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
