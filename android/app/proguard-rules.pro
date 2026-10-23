# Proguard rules for Meisam VPN
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.meisam.vpn.** { *; }
