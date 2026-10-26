# The firmware's libfmjni.so binds JNI by these exact class and method names.
-keep class com.android.fmradio.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Keep stack traces readable in diagnostics reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Vendor framework classes are reached by reflection and are not part of the APK,
# so nothing to keep for them; silence warnings about them.
-dontwarn android.tw.john.**
-dontwarn android.microntek.**
