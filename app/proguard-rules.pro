# ---------------------------------------------------------------------------
# RadarDeal — R8 configuration
#
# The app has no reflection-heavy layers except kotlinx.serialization, Room and
# the JavaScript bridge used to read the user's own Vinted session. Everything
# below exists to keep those three working after shrinking.
# ---------------------------------------------------------------------------

# Keep line numbers so that release crash reports stay actionable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- kotlinx.serialization -------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations, AnnotationDefault
-dontnote kotlinx.serialization.**
-keepclassmembers class com.radardeal.app.**$$serializer { *; }
-keepclasseswithmembers class com.radardeal.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.radardeal.app.**
-keep class com.radardeal.app.<1> {
    static <1>$Companion Companion;
    *** Companion;
}

# --- Room ------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- WebView JavaScript bridge --------------------------------------------
# The bridge object is only reachable from JavaScript, so R8 cannot see the
# call sites and would otherwise strip the annotated methods.
-keepclassmembers class com.radardeal.app.web.** {
    @android.webkit.JavascriptInterface <methods>;
}

# --- OkHttp / Okio ---------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Coroutines ------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# --- WorkManager -----------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }
