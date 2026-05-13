# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /path/to/android/sdk/tools/proguard/proguard-android.txt

# Keep WebRTC classes
-keep class org.webrtc.** { *; }
-keep class io.webrtc.** { *; }

# Keep Jitsi classes
-keep class org.jitsi.** { *; }

# Keep Java WebSocket
-keep class org.java_websocket.** { *; }

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep app models
-keep class com.phonemirror.app.** { *; }
