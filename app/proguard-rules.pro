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