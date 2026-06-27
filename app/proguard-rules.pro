# Add project specific ProGuard rules here.
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Kotlinx Serialization - keep @Serializable class names for reflection
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn kotlinx.serialization.**
-keep,includedescriptorclasses class com.yage.opencode_client.data.model.** { *; }

# EncryptedSharedPreferences / Tink - errorprone annotations are compile-only
-dontwarn com.google.errorprone.annotations.**

# MikePenz markdown-renderer - keep classes used via reflection in Compose
-keep class com.mikepenz.markdown.** { *; }

# Hilt - applied via consumerProguardFiles from dependencies
# Retrofit/OkHttp - applied via consumerProguardFiles

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- Keep line numbers for readable release stack traces ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- R8: disable optimization ---
# R8 miscompiles synchronized blocks inside coroutines -> java.lang.VerifyError
# ("expected to be within a catch-all for an instruction where a monitor is held")
# on release (e.g. Android 16). Debug builds are unaffected because they skip R8.
# Disabling optimization avoids the invalid bytecode; shrinking + obfuscation
# are kept, so release APK size is essentially unchanged.
-dontoptimize
# --- Added: fix release ParameterizedType / serialization errors ---
# Retrofit API interface: keep generic return-type signatures so Retrofit reflection
# sees ParameterizedType (List<Session>, Map<...>) instead of raw Class.
-keep interface com.yage.opencode_client.data.api.OpenCodeApi { *; }

# kotlinx.serialization: keep serializer() reflection (R8 9-compatible form;
# the official -if/-keep templates with "**$*" are rejected by this R8 version).
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# App @Serializable models (data.model.**) already kept above via -keep ... { *; },
# which preserves their $Companion and $serializer accessors.
