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

# Dynamic feature entry points are instantiated reflectively by FeatureLoader using the class names
# in core's FeatureModules, so R8 can't see them as used. Keep the classes and their no-arg
# constructors (the implemented interfaces are kept by core's consumer rules).
-keep class com.hereliesaz.logkitty.feature.stats.StatsFeatureImpl { <init>(); }
-keep class com.hereliesaz.logkitty.feature.ads.AdsFeatureImpl { <init>(); }
-keep class com.hereliesaz.logkitty.feature.github.GitHubFeatureImpl { <init>(); }
