# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep DataStore
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# Keep Gson
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.Continuation { *; }

# Keep Compose
-keepclassmembers class * extends androidx.compose.runtime.Composable { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**