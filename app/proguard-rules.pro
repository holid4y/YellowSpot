# GeckoView ships consumer ProGuard rules through its AAR.
# Preserve its reflective entry points if optimizing the Java wrapper.
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.geckoview.**
