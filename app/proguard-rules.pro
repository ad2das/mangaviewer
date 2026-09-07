# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

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

# Gson is used broadly for intents, SharedPreferences, file caches, and Firebase sync.
# Keep field names stable while still allowing method shrinking/optimization.
-keepattributes Signature,*Annotation*
-keepclassmembers class ml.melun.mangaview.** {
    <fields>;
}

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# gl_presentation_callback.cpp resolves this callback by name/signature through JNI.
# Its only caller is native code, so R8 cannot infer that the method is live.
-keepclassmembers class ml.melun.mangaview.viewer.runtime.OwnedRendererCallback {
    public void onFramePresented(long, long, int, long);
}
