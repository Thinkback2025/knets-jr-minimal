# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Minimal ProGuard rules for clean release build
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Keep main application classes
-keep class com.knets.jr.** { *; }

# Keep device admin receiver
-keep class * extends android.app.admin.DeviceAdminReceiver { *; }

# Keep service classes  
-keep class * extends android.app.Service { *; }

# Keep manifest entries
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Standard Android rules
-dontwarn android.**
-dontwarn androidx.**

# Keep OkHttp and Gson classes
-keep class okhttp3.** { *; }
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.knets.jr.** { *; }