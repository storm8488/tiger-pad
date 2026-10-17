# Paho loads its logger and its network modules by class name.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**

# ZXing (the pairing QR code): only its encoder is used, nothing loaded by name.
-dontwarn com.google.zxing.**

# org.json is the platform's: nothing to keep.

# OkHttp brings its own rules; these are the optional platforms it looks for.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# The names in a crash's stack trace stay readable.
-keepattributes SourceFile,LineNumberTable
