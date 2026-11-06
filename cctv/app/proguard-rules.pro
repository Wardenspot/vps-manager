# libVLC вызывает Java-код из нативной части через JNI
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }
-dontwarn org.videolan.**
# ZXing (сканер QR)
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# security-crypto (Tink)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
# Встроенный VPN (AmneziaWG/WireGuard): нативная часть вызывает Java-классы
-keep class org.amnezia.awg.** { *; }
-dontwarn org.amnezia.awg.**
-keep class com.getkeepsafe.relinker.** { *; }
# API приложения «OpenVPN for Android»
-keep class de.blinkt.openvpn.api.** { *; }
# OkHttp 5
-dontwarn okhttp3.**
-dontwarn okio.**
