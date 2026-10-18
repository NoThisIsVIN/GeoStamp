# ProGuard / R8 configuration for GeoStamp Camera

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# CameraX
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.view.** { *; }

# Google Play Services Location
-keep class com.google.android.gms.location.** { *; }
-keep class com.google.android.gms.common.** { *; }

# Coil image loading
-keep class coil.** { *; }

# DataStore
-keep class androidx.datastore.** { *; }
