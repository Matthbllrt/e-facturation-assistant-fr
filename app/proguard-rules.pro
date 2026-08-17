# Paho MQTT
-dontwarn org.eclipse.paho.**
-keep class org.eclipse.paho.client.mqttv3.** { *; }
# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.glasscontrol.dyson.** {
    *** Companion;
}
-keepclasseswithmembers class com.glasscontrol.dyson.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.glasscontrol.dyson.**$$serializer { *; }
# Glance / Compose
-keep class androidx.glance.** { *; }
