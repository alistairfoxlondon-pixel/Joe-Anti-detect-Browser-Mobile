# Joe Browser release rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class androidx.webkit.** { *; }
-keep class com.joebrowser.app.** { *; }
-dontwarn javax.annotation.**
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
